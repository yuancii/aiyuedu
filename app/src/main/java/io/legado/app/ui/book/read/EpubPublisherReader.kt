package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubPublisherSession
import io.legado.app.model.localBook.epubFragment
import io.legado.app.model.localBook.epubTextPosition
import io.legado.app.model.localBook.epubTextRange
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.Locale

/** A publication renderer inside the existing reader. Navigation and annotations use ReadBook. */
class EpubPublisherReader(
    private val activity: ReadBookActivity,
    private val container: FrameLayout,
    private val onModeChanged: () -> Unit,
) {
    var active = false
        private set
    var selectedText = ""
        private set
    var positionVersion = 0L
        private set
    private var selected: JSONObject? = null
    private var owner: Book? = null
    private var session: EpubPublisherSession? = null
    private var webView: WebView? = null
    private var chapters = emptyList<BookChapter>()
    private var currentPath = ""
    private var ready = false
    private var version = 0
    private var job: Job? = null
    private var savedLocator: JSONObject? = null
    private var pendingLocator: JSONObject? = null
    private var pendingFragment: String? = null
    private var fromDocument = false
    private var keepPosition = false
    private var lastNativeChapter = -1
    private var lastNativePosition = -1
    private var lastPaint = ""
    private var lastStyle: JSONObject? = null
    private var annotations = JSONObject()
    private val fontSources = mutableMapOf<String, String>()
    private val script by lazy {
        activity.assets.open("epub/publisher-reader.js").bufferedReader().use { it.readText() }
    }

    fun show(fromNative: Boolean) {
        if (active) return
        val book = ReadBook.book ?: return
        active = true
        owner = book
        version++
        val request = version
        lastStyle = style()
        annotations = runCatching { JSONObject(book.config.epubPublisherAnchors.orEmpty()) }
            .getOrDefault(JSONObject())
        container.isVisible = true
        updateBackground()
        onModeChanged()
        job = activity.lifecycleScope.launch {
            var opened: EpubPublisherSession? = null
            try {
                val loaded = withContext(IO) { EpubPublisherSession(book).also { opened = it } }
                if (!active || request != version) { withContext(IO) { loaded.close() }; return@launch }
                session = loaded
                loaded.textTransform = textTransform()
                chapters = withContext(IO) { appDb.bookChapterDao.getChapterList(book.bookUrl) }
                require(chapters.isNotEmpty())
                createWebView()
                val restored = if (fromNative) null else runCatching {
                    JSONObject(book.config.epubPublisherLocator.orEmpty())
                }.getOrNull()
                showNativePosition(restored)
                while (isActive && active) {
                    delay(700)
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        capture()
                        paintAnnotations()
                    }
                }
            } catch (error: CancellationException) {
                opened?.close()
                throw error
            } catch (error: Exception) {
                opened?.close()
                if (active && request == version) fail(error.localizedMessage.orEmpty())
            }
        }
    }

    fun hide(after: () -> Unit) {
        capture {
            owner?.config?.epubUsePublisherLayout = false
            ReadBook.saveRead()
            dispose()
            onModeChanged()
            after()
        }
    }

    fun onNativeContent(resetPosition: Boolean = false) {
        if (!active || session == null) return
        if (owner?.bookUrl != ReadBook.book?.bookUrl) { dispose(); return }
        if (fromDocument || keepPosition && ReadBook.durChapterIndex == lastNativeChapter) {
            keepPosition = false
            lastNativeChapter = ReadBook.durChapterIndex
            lastNativePosition = ReadBook.durChapterPos
            paintAnnotations()
            return
        }
        keepPosition = false
        if (ReadBook.durChapterIndex != lastNativeChapter ||
            resetPosition && ReadBook.durChapterPos != lastNativePosition) showNativePosition()
        else paintAnnotations()
    }

    private fun nativeText(): String = ReadBook.curTextChapter?.pages?.joinToString("") { it.text }.orEmpty()

    private fun showNativePosition(restored: JSONObject? = null) {
        val renderer = session ?: return
        val chapter = chapters.getOrNull(ReadBook.durChapterIndex) ?: return
        val start = renderer.pathForChapter(chapter.url) ?: return
        val text = nativeText()
        val offset = ReadBook.durChapterPos.coerceIn(0, text.length)
        val quote = text.substring(offset).take(64)
        lastNativeChapter = ReadBook.durChapterIndex
        lastNativePosition = ReadBook.durChapterPos
        val request = ++version
        activity.lifecycleScope.launch {
            try {
                val hint = restored?.takeIf { it.optInt("chapterIndex", -1) == chapter.index }
                val path = hint?.optString("href")?.takeIf(renderer::isDocument) ?: withContext(IO) {
                    renderer.documentForQuote(start,
                        chapters.getOrNull(chapter.index + 1)?.url?.let(renderer::pathForChapter), quote)
                }
                if (!active || request != version) return@launch
                val position = hint ?: JSONObject().put("quote", quote)
                    .put("fraction", if (text.isEmpty()) 0.0 else offset.toDouble() / text.length)
                navigate(path, epubFragment(chapter.url), position)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (request == version) fail(error.localizedMessage.orEmpty()) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                activity.onReadScroll(distanceY.toInt())
                return false
            }
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                evaluate("window.legadoPublisher.event()") { value ->
                    val tap = json(value) ?: return@evaluate
                    val scale = activity.resources.displayMetrics.density
                    val x = tap.optDouble("x").toFloat() * scale + container.left
                    val y = tap.optDouble("y").toFloat() * scale + container.top
                    when (tap.optString("type")) {
                        "menu" -> activity.showMenuBar()
                        "annotation" -> {
                            val id = tap.optString("id")
                            if (id.startsWith("h:")) ReadBook.highlights.firstOrNull {
                                it.time.toString() == id.removePrefix("h:")
                            }?.let { activity.onHighlightClick(it, x, y) }
                            else id.removePrefix("r:").toLongOrNull()?.let {
                                activity.onHighlightRuleClick(it, x, y)
                            }
                        }
                    }
                }
                return false
            }
        })
        val view = object : WebView(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                gestures.onTouchEvent(event)
                return super.onTouchEvent(event)
            }
            override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
                super.startActionMode(selectionActions(callback))
            override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
                super.startActionMode(selectionActions(callback), type)
        }
        webView = view
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = session?.fixedLayout == true
            builtInZoomControls = true
            displayZoomControls = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val font = synchronized(fontSources) { fontSources[request.url.toString()] }
                if (font != null) return runCatching {
                    val input = if (font.startsWith("content:")) activity.contentResolver.openInputStream(Uri.parse(font))
                        else File(font).inputStream()
                    WebResourceResponse("font/ttf", null, requireNotNull(input))
                }.getOrElse { EpubPublisherSession.blocked() }
                return session?.response(request.url.toString()) ?: EpubPublisherSession.blocked()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return true
                val renderer = session ?: return true
                val path = renderer.pathForUrl(request.url.toString()) ?: return true
                if (!renderer.isDocument(path)) return true
                capture { navigate(path, request.url.fragment) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!active || session?.pathForUrl(url) != currentPath) return
                val anchors = JSONArray()
                chapters.forEach {
                    if (session?.pathForChapter(it.url) == currentPath) epubFragment(it.url)?.let { id ->
                        anchors.put(JSONObject().put("id", id).put("index", it.index))
                    }
                }
                val position = pendingLocator
                view.evaluateJavascript(script + "\nwindow.legadoPublisherAnchors=$anchors;", null)
                ready = true
                applyStyle()
                view.evaluateJavascript("window.legadoPublisher.restore(${position ?: "null"}," +
                    JSONObject.quote(pendingFragment.orEmpty()) + ");", null)
                paintAnnotations()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(error.description.toString())
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.isForMainFrame) fail(error.reasonPhrase)
            }
        }
    }

    private fun selectionActions(original: ActionMode.Callback) = object : ActionMode.Callback2() {
        private fun addActions(menu: Menu) {
            listOf(R.id.menu_highlight to R.string.highlight, R.id.menu_dict to R.string.dict,
                R.id.menu_bookmark to R.string.bookmark, R.id.menu_reader_more to R.string.text_menu_zone_more).forEach { (id, title) ->
                if (menu.findItem(id) == null)
                    menu.add(0, id, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = original.onCreateActionMode(mode, menu)
            addActions(menu)
            return created
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = original.onPrepareActionMode(mode, menu)
            addActions(menu)
            return changed
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
        override fun onDestroyActionMode(mode: ActionMode) { original.onDestroyActionMode(mode) }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in listOf(R.id.menu_highlight, R.id.menu_dict, R.id.menu_bookmark, R.id.menu_reader_more))
                return original.onActionItemClicked(mode, item)
            withSelection {
                if (item.itemId == R.id.menu_reader_more) activity.showPublisherTextActions(selected!!)
                else {
                    activity.onMenuItemSelected(item.itemId)
                    clearSelection()
                }
                mode.finish()
            }
            return true
        }
    }

    private fun navigate(path: String, fragment: String? = null, position: JSONObject? = null) {
        val renderer = session ?: return
        version++
        val same = ready && currentPath == path
        ready = false
        currentPath = path
        pendingLocator = position
        pendingFragment = fragment
        savedLocator = null
        lastPaint = ""
        selected = null
        selectedText = ""
        if (same) {
            ready = true
            evaluate("window.legadoPublisher.restore(${position ?: "null"},${JSONObject.quote(fragment.orEmpty())})") {}
            paintAnnotations()
        } else webView?.loadUrl(renderer.url(path, fragment))
    }

    private fun evaluate(expression: String, after: (String) -> Unit) {
        val request = version
        if (!ready || !active) return
        webView?.evaluateJavascript(expression) { if (active && request == version) after(it) }
    }

    private fun json(value: String): JSONObject? = runCatching {
        when (val result = JSONTokener(value).nextValue()) {
            is JSONObject -> result
            is String -> JSONObject(result)
            else -> null
        }
    }.getOrNull()

    fun capture(after: () -> Unit = {}) {
        if (!active || !ready) { after(); return }
        evaluate("window.legadoPublisher.restored ? window.legadoPublisher.capture() : null") { value ->
            json(value)?.let { state ->
                val chapter = state.optInt("chapter", chapterForPath())
                savedLocator = state.put("href", currentPath).put("chapterIndex", chapter)
                owner?.config?.epubPublisherLocator = savedLocator.toString()
                if (!fromDocument && chapter != ReadBook.durChapterIndex && chapter in chapters.indices) {
                    openNativeChapter(chapter, state.optString("quote")) { updateNativePosition(state); after() }
                    return@evaluate
                }
                updateNativePosition(state)
            }
            after()
        }
    }

    private fun updateNativePosition(state: JSONObject) {
        if (fromDocument || ReadBook.curTextChapter?.isCompleted != true ||
            ReadBook.curTextChapter?.position != ReadBook.durChapterIndex) return
        val text = nativeText()
        val previous = ReadBook.durChapterPos
        if (text.isNotEmpty()) ReadBook.durChapterPos = epubTextPosition(text,
            state.optString("quote"), state.optDouble("fraction", 0.0))
        lastNativeChapter = ReadBook.durChapterIndex
        lastNativePosition = ReadBook.durChapterPos
        if (previous != ReadBook.durChapterPos) {
            positionVersion++
            activity.onPublisherPositionChanged()
            ReadBook.saveRead(pageChanged = true)
        }
    }

    private fun chapterForPath(): Int {
        val renderer = session ?: return ReadBook.durChapterIndex
        val spineIndex = renderer.spine.indexOf(currentPath)
        val same = chapters.filter { renderer.pathForChapter(it.url) == currentPath }
        return same.firstOrNull { epubFragment(it.url).isNullOrEmpty() }?.index
            ?: same.firstOrNull()?.index ?: chapters.lastOrNull {
            val start = renderer.spine.indexOf(renderer.pathForChapter(it.url))
            start >= 0 && start < spineIndex
        }?.index ?: ReadBook.durChapterIndex
    }

    private fun openNativeChapter(index: Int, quote: String, after: () -> Unit) {
        if (fromDocument) return
        fromDocument = true
        ReadBook.openChapter(index, upContent = false, highlightAnchorText = quote) {
            fromDocument = false
            lastNativeChapter = ReadBook.durChapterIndex
            lastNativePosition = ReadBook.durChapterPos
            lastPaint = ""
            paintAnnotations()
            after()
        }
    }

    private fun withSelection(after: () -> Unit) {
        evaluate("window.legadoPublisher.selection()") { value ->
            val range = json(value) ?: return@evaluate
            selected = range
            selectedText = range.optString("quote")
            activity.positionPublisherSelection(range)
            val chapter = range.optInt("chapter", chapterForPath())
            if (chapter != ReadBook.durChapterIndex) openNativeChapter(chapter, selectedText, after)
            else after()
        }
    }

    fun clearSelection() {
        evaluate("window.legadoPublisher.clearSelection()") {}
        selected = null
        selectedText = ""
    }

    fun selectionHighlight(style: HighlightStyle): BookHighlight? {
        val book = owner ?: return null
        val chapter = ReadBook.curTextChapter ?: return null
        if (!chapter.isCompleted) return null
        val selection = selected ?: return null
        val range = epubTextRange(nativeText(), selectedText, selection.optDouble("fraction", 0.0)) ?: return null
        return BookHighlight(bookUrl = book.bookUrl, chapterUrl = chapter.chapter.url,
            bookName = book.name, bookAuthor = book.author, chapterIndex = chapter.position,
            chapterPos = range.first, chapterPosEnd = range.second,
            layoutTitleLength = chapter.layoutTitleLength, chapterName = chapter.title,
            bookText = nativeText().substring(range.first, range.second)).apply { applyStyle(style) }
    }

    fun remember(highlight: BookHighlight) {
        selected?.let {
            val hint = JSONObject(it.toString()).put("href", currentPath)
            annotations.put(highlight.time.toString(), hint)
            owner?.config?.epubPublisherAnchors = annotations.toString()
            ReadBook.saveRead()
        }
    }

    fun bookmark(after: (Bookmark?) -> Unit) {
        fun create(): Bookmark? {
            val book = owner ?: return null
            val chapter = ReadBook.curTextChapter ?: return null
            val quote = selectedText.ifBlank { savedLocator?.optString("quote").orEmpty() }
            val hint = selected ?: savedLocator
            val position = epubTextPosition(nativeText(), quote, hint?.optDouble("fraction", 0.0) ?: 0.0)
            return book.createBookMark().apply {
                chapterIndex = chapter.position
                chapterPos = position
                chapterName = chapter.title
                bookText = quote
            }
        }
        if (selected != null) after(create()) else capture { after(create()) }
    }

    fun visibleBookmarks(bookmarks: List<Bookmark>, after: (List<Bookmark>) -> Unit) {
        capture {
            val start = ReadBook.durChapterPos
            val locator = savedLocator
            val end = locator?.let { epubTextRange(nativeText(), it.optString("endQuote"),
                it.optDouble("endFraction", 0.0))?.first } ?: start
            after(bookmarks.filter { it.chapterIndex == ReadBook.durChapterIndex &&
                it.chapterPos in start..end.coerceAtLeast(start) })
        }
    }

    fun turnPage(next: Boolean) {
        evaluate("window.legadoPublisher.turn(${if (next) 1 else -1},${ReadBook.pageAnim() != 4})") { moved ->
            if (moved == "true") return@evaluate
            val renderer = session ?: return@evaluate
            val path = renderer.spine.getOrNull(renderer.spine.indexOf(currentPath) + if (next) 1 else -1)
                ?: return@evaluate
            capture { navigate(path, position = if (next) null else JSONObject().put("scroll", 1)) }
        }
    }

    fun onConfigChanged(preservePosition: Boolean) {
        if (!active) return
        keepPosition = preservePosition
        val current = style()
        val previous = lastStyle
        val keys = owner?.config?.epubPublisherStyleOverrides.orEmpty().toMutableSet()
        current.keys().forEach { key ->
            if (previous != null && current.opt(key)?.toString() != previous.opt(key)?.toString()) keys.add(key)
        }
        if (previous?.optInt("bgAlpha") != current.optInt("bgAlpha")) keys.add("background")
        owner?.config?.epubPublisherStyleOverrides = keys.toList()
        lastStyle = current
        applyStyle()
        if (previous?.optInt("chineseConverter") != current.optInt("chineseConverter")) {
            capture {
                val renderer = session ?: return@capture
                renderer.textTransform = textTransform()
                val position = savedLocator?.let { JSONObject(it.toString()) }
                position?.optString("quote")?.let { quote ->
                    position.put("quote", renderer.textTransform?.invoke(quote) ?: quote)
                }
                ready = false
                navigate(currentPath, position = position)
            }
        }
        ReadBook.saveRead()
    }

    private fun style() = JSONObject().apply {
        val config = ReadBookConfig
        put("textSize", config.textSize)
        put("textBold", config.textBold)
        put("textFont", config.textFont)
        put("letterSpacing", config.letterSpacing)
        put("lineSpacingExtra", config.lineSpacingExtra)
        put("paragraphSpacing", config.paragraphSpacing)
        put("paragraphIndent", config.paragraphIndent.length)
        put("titleMode", config.titleMode)
        put("titleFont", config.resolvedTitleFont)
        put("titleSize", config.titleSize)
        put("titleBold", config.titleBold)
        put("titleColor", cssColor(config.titleTextColor))
        put("titleLineSpacingExtra", config.titleLineSpacingExtra)
        put("titleSpacing", JSONArray(listOf(config.titleTopSpacing, config.titleBottomSpacing)))
        put("textFullJustify", config.textFullJustify)
        put("bgAlpha", config.bgAlpha)
        put("chineseConverter", AppConfig.chineseConverterType)
        put("textColor", cssColor(config.textColor))
        put("background", "${config.durConfig.curBgType()}:${config.durConfig.curBgStr()}:${AppConfig.isNightTheme}")
        put("padding", JSONArray(listOf(config.paddingTop, config.paddingRight, config.paddingBottom, config.paddingLeft)))
    }

    private fun applyStyle() {
        val current = style()
        val font = ReadBookConfig.textFont
        if (font.isNotEmpty()) current.put("fontUrl", fontUrl(font))
        if (ReadBookConfig.resolvedTitleFont.isNotEmpty())
            current.put("titleFontUrl", fontUrl(ReadBookConfig.resolvedTitleFont))
        val keys = owner?.config?.epubPublisherStyleOverrides.orEmpty().toMutableSet()
        if (AppConfig.isNightTheme) keys.addAll(listOf("background", "textColor"))
        updateBackground()
        evaluate("window.legadoPublisher.applyStyle($current,${JSONArray(keys.toList())})") {}
    }

    private fun updateBackground() {
        val mean = ReadBookConfig.bgMeanColor
        val base = ColorDrawable(Color.rgb(Color.red(mean), Color.green(mean), Color.blue(mean)))
        val image = ReadBookConfig.bg?.constantState?.newDrawable()?.mutate()
        image?.alpha = (ReadBookConfig.bgAlpha.coerceIn(0, 100) * 2.55).toInt()
        container.background = if (image == null) base else LayerDrawable(arrayOf(base, image))
    }

    private fun fontUrl(path: String): String {
        val url = session?.url("__legado_font__/${path.hashCode()}").orEmpty()
        synchronized(fontSources) { fontSources[url] = path }
        return url
    }

    private fun paintAnnotations() {
        if (!active || !ready) return
        val chapter = ReadBook.curTextChapter ?: return
        val items = JSONArray()
        val text = nativeText()
        ReadBook.ruleMatchesOfChapter(chapter).forEach { match ->
            if (match.start >= 0 && match.end <= text.length && match.end > match.start) items.put(
                JSONObject().put("id", "r:${match.ruleId}").put("quote", text.substring(match.start, match.end))
                    .put("fraction", match.start.toDouble() / text.length.coerceAtLeast(1))
                    .put("paint", paint(match.style)))
        }
        ReadBook.highlights.filter { it.chapterIndex == chapter.position }.forEach { highlight ->
            val hint = annotations.optJSONObject(highlight.time.toString())?.takeIf {
                it.optString("quote").filterNot(Char::isWhitespace) == highlight.bookText.filterNot(Char::isWhitespace)
            }
            if (hint != null && hint.optString("href") != currentPath) return@forEach
            val item = hint?.let { JSONObject(it.toString()) } ?: JSONObject()
                .put("quote", highlight.bookText)
                .put("fraction", highlight.chapterPos.toDouble() / text.length.coerceAtLeast(1))
            items.put(item.put("id", "h:${highlight.time}").put("paint", paint(highlight.styleObj())))
        }
        val value = items.toString()
        if (value == lastPaint) return
        lastPaint = value
        evaluate("window.legadoPublisher.annotations($items)") {}
    }

    private fun paint(style: HighlightStyle) = JSONObject().apply {
        if (style.fill != 0) put("fill", cssColor(style.fill))
        put("rounded", style.resolvedFillShape != HighlightStyle.FillShape.RECTANGLE)
        if (style.textColor != 0) put("textColor", cssColor(style.textColor))
        put("bold", style.bold)
        put("italic", style.italic)
        style.resolvedFontSize?.let { put("fontSize", it) }
        style.resolvedLetterSpacing?.let { put("letterSpacing", it) }
        if (style.resolvedFontPath.isNotEmpty()) put("fontUrl", fontUrl(style.resolvedFontPath))
        style.underline?.let {
            val kind = when (it.kind) {
                HighlightStyle.Kind.DASHED -> "dashed"
                HighlightStyle.Kind.DOTTED -> "dotted"
                HighlightStyle.Kind.DOUBLE -> "double"
                else -> "solid"
            }
            put("underline", "${it.width}px $kind ${cssColor(it.color)}")
        }
        style.box?.let { put("box", cssColor(it.color)) }
        style.strike?.let { put("strike", cssColor(it.color)) }
        style.shadow?.let { put("shadow", "${it.dx}px ${it.dy}px ${it.radius}px ${cssColor(it.color)}") }
        style.emphasis?.let { put("emphasis", cssColor(it.color)) }
    }

    private fun cssColor(color: Int) = String.format(Locale.US, "rgba(%d,%d,%d,%.3f)",
        Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color) / 255.0)

    private fun textTransform(): ((String) -> String)? = when (AppConfig.chineseConverterType) {
        1 -> ChineseUtils::t2s
        2 -> ChineseUtils::s2t
        else -> null
    }

    private fun fail(message: String) {
        activity.toastOnUi(activity.getString(R.string.epub_publisher_error, message))
        owner?.config?.epubUsePublisherLayout = false
        ReadBook.saveRead()
        dispose()
        onModeChanged()
    }

    fun onPause() { capture { ReadBook.saveRead() }; webView?.onPause() }
    fun onResume() { webView?.onResume() }

    fun dispose() {
        active = false
        ready = false
        version++
        job?.cancel()
        job = null
        webView?.let { container.removeView(it); it.stopLoading(); it.destroy() }
        webView = null
        session?.close()
        session = null
        container.isVisible = false
        owner = null
        currentPath = ""
        lastPaint = ""
        fromDocument = false
        clearSelection()
        onModeChanged()
    }
}
