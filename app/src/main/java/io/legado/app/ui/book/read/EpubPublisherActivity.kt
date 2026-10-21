package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityEpubPublisherBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isEpub
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.EpubPublisherSession
import io.legado.app.model.localBook.EpubTocNode
import io.legado.app.model.localBook.epubTextPosition
import io.legado.app.model.localBook.epubFragment
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Publisher content is a separate renderer; native caches and annotations remain unchanged. */
class EpubPublisherActivity : BaseActivity<ActivityEpubPublisherBinding>(imageBg = false) {
    override val binding by viewBinding(ActivityEpubPublisherBinding::inflate)
    private var book: Book? = null
    private var session: EpubPublisherSession? = null
    private var webView: WebView? = null
    private var chapters = emptyList<BookChapter>()
    private var toc = emptyList<EpubTocNode>()
    private var currentPath = ""
    private var chapterIndex = 0
    private var nativePosition = 0
    private var locator: JSONObject? = null
    private var pendingLocator: JSONObject? = null
    private var pendingFragment: String? = null
    private var loadVersion = 0
    private var ready = false
    private var leaving = false
    private var documentFailed = false
    private var lastPersistedState: String? = null
    private val positioningScript by lazy { assets.open("epub/publisher-reader.js").bufferedReader().use { it.readText() } }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.previous.setOnClickListener { adjacent(-1) }
        binding.next.setOnClickListener { adjacent(1) }
        binding.contents.setOnClickListener { showContents() }
        onBackPressedDispatcher.addCallback(this) { capture { persist(); finish() } }
        lifecycleScope.launch {
            try {
                // The native reader saves through this queue in onPause, before this activity opens.
                val loaded = withContext(IO) {
                    ReadBook.executor.submit<Book?> {
                        intent.getStringExtra("bookUrl")?.let(appDb.bookDao::getBook)
                    }.get()
                } ?: error(getString(R.string.no_book))
                require(loaded.isEpub)
                book = loaded
                binding.titleBar.title = loaded.name
                withContext(IO) {
                    chapters = appDb.bookChapterDao.getChapterList(loaded.bookUrl)
                    if (chapters.isEmpty()) {
                        chapters = EpubFile.getChapterList(loaded)
                        appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    }
                    toc = EpubFile.getToc(loaded)
                    session = EpubPublisherSession(loaded)
                }
                chapterIndex = intent.getIntExtra("index", loaded.durChapterIndex)
                    .coerceIn(0, chapters.lastIndex)
                nativePosition = intent.getIntExtra("chapterPos", loaded.durChapterPos)
                val saved = savedInstanceState?.getString("publisherLocator")
                    ?: loaded.readConfig?.epubPublisherLocator
                val restored = runCatching { JSONObject(saved.orEmpty()) }.getOrNull()
                val requestedChapter = chapters[chapterIndex]
                val firstPath = requireNotNull(session?.pathForChapter(requestedChapter.url))
                val incoming = savedInstanceState == null && intent.hasExtra("publisherQuote")
                val landingPath = if (incoming) withContext(IO) {
                    session!!.documentForQuote(firstPath,
                        chapters.getOrNull(chapterIndex + 1)?.url?.let(session!!::pathForChapter),
                        intent.getStringExtra("publisherQuote").orEmpty())
                } else firstPath
                locator = if (!incoming && (savedInstanceState != null ||
                    restored?.optInt("chapterIndex", -1) == chapterIndex)) restored else null
                pendingLocator = if (incoming) JSONObject().put("quote", intent.getStringExtra("publisherQuote"))
                    .put("fraction", intent.getDoubleExtra("publisherFraction", 0.0)) else locator
                pendingFragment = epubFragment(requestedChapter.url)
                createWebView()
                navigate(locator?.optString("href")?.takeIf { session?.isDocument(it) == true } ?: landingPath,
                    pendingFragment, pendingLocator)
                lifecycleScope.launch {
                    while (isActive) {
                        delay(1000)
                        if (!leaving && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            capture { if (!leaving) persist() }
                        }
                    }
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + IO) { session?.close() }
                throw error
            } catch (error: Exception) { showError(error.localizedMessage.orEmpty()) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = WebView(this)
        webView = view
        binding.content.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        view.settings.apply {
            javaScriptEnabled = true // Only app-owned evaluateJavascript; publication scripts are removed.
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = session?.fixedLayout == true
            builtInZoomControls = true
            displayZoomControls = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                session?.response(request.url.toString()) ?: EpubPublisherSession.blocked()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return true
                val target = request.url.toString()
                val path = session?.pathForUrl(target) ?: return true
                if (session?.isDocument(path) != true) return true
                capture {
                    persist()
                    locator = null
                    navigate(path, request.url.fragment, null)
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (documentFailed || session?.pathForUrl(url) != currentPath) return
                restoreDocumentPosition(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { documentFailed = true; showError(error.description.toString()) }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.isForMainFrame) { documentFailed = true; showError(error.reasonPhrase) }
            }
        }
    }

    private fun restoreDocumentPosition(view: WebView) {
        val anchors = JSONArray()
        chapters.forEach { chapter ->
            if (session?.pathForChapter(chapter.url) == currentPath) {
                epubFragment(chapter.url)?.let {
                    anchors.put(JSONObject().put("id", it).put("index", chapter.index))
                }
            }
        }
        view.evaluateJavascript(positioningScript + "\nwindow.legadoPublisherAnchors=$anchors;" +
            "window.legadoPublisher.restore(${pendingLocator ?: "null"},${JSONObject.quote(pendingFragment.orEmpty())});", null)
        ready = true
        binding.loading.isVisible = false
        binding.status.setText(R.string.epub_publisher_help)
    }

    private fun navigate(path: String, fragment: String? = null, position: JSONObject? = null) {
        val renderer = session ?: return
        val sameDocument = ready && currentPath == path
        loadVersion++
        ready = false
        documentFailed = false
        currentPath = path
        pendingFragment = fragment
        pendingLocator = position
        chapterIndex = chapters.firstOrNull { renderer.pathForChapter(it.url) == path &&
            !fragment.isNullOrEmpty() && epubFragment(it.url) == fragment }?.index
            ?: position?.optInt("chapterIndex", -1)?.takeIf { it in chapters.indices }
            ?: chapters.lastOrNull { renderer.pathForChapter(it.url) == path && epubFragment(it.url).isNullOrEmpty() }
            ?.index ?: chapters.firstOrNull { renderer.pathForChapter(it.url) == path }?.index
            ?: chapters.filter { chapter ->
                val start = renderer.spine.indexOf(renderer.pathForChapter(chapter.url))
                start >= 0 && start <= renderer.spine.indexOf(path)
            }.maxByOrNull { renderer.spine.indexOf(renderer.pathForChapter(it.url)) }?.index ?: chapterIndex
        if (position == null) { nativePosition = 0; locator = null }
        binding.loading.isVisible = true
        val index = renderer.spine.indexOf(path)
        binding.previous.isEnabled = index > 0
        binding.next.isEnabled = index >= 0 && index < renderer.spine.lastIndex
        webView?.let { view ->
            if (sameDocument) restoreDocumentPosition(view) else view.loadUrl(renderer.url(path, fragment))
        }
    }

    private fun adjacent(direction: Int) {
        if (leaving) return
        val renderer = session ?: return
        val target = renderer.spine.getOrNull(renderer.spine.indexOf(currentPath) + direction) ?: return
        capture { persist(); navigate(target) }
    }

    private fun showContents() {
        val renderer = session ?: return
        val entries = toc.ifEmpty {
            chapters.map { EpubTocNode(it.index, null, 0, it.title, it.url) }
        }.filter { it.href?.let(renderer::pathForChapter)?.let(renderer::isDocument) == true }
        if (entries.isEmpty()) return
        AlertDialog.Builder(this).setTitle(R.string.chapter_list)
            .setItems(entries.map { "  ".repeat(it.depth.coerceAtMost(12)) + it.title }.toTypedArray()) { _, index ->
                val href = entries[index].href ?: return@setItems
                val path = renderer.pathForChapter(href) ?: return@setItems
                capture { persist(); navigate(path, epubFragment(href)) }
            }.show()
    }

    private fun capture(after: () -> Unit = {}) {
        val version = loadVersion
        val view = webView
        if (!ready || view == null) { after(); return }
        view.evaluateJavascript("window.legadoPublisher && window.legadoPublisher.restored ? JSON.stringify(window.legadoPublisher.capture()) : null") { value ->
            if (version == loadVersion && !isDestroyed) {
                runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull()?.let {
                    chapterIndex = it.optInt("chapter", chapterIndex).coerceIn(0, chapters.lastIndex)
                    locator = it.put("href", currentPath).put("chapterIndex", chapterIndex)
                }
            }
            after()
        }
    }

    private fun persist(enabled: Boolean = true, after: (() -> Unit)? = null) {
        val currentBook = book ?: return
        val chapter = chapters.getOrNull(chapterIndex)
        val position = locator?.toString()
        val quote = locator?.optString("quote").orEmpty()
        val fraction = locator?.optDouble("fraction", 0.0) ?: 0.0
        val fallbackPosition = nativePosition
        val stateKey = "$enabled:${chapter?.index}:$position:$fallbackPosition"
        if (after == null && lastPersistedState == stateKey) return
        lastPersistedState = stateKey
        ReadBook.executor.execute {
            try {
                val raw = if (quote.isNotBlank() && chapter != null) BookHelp.getContent(currentBook, chapter).orEmpty() else ""
                val text = if (raw.isNotEmpty() && chapter != null) {
                    val (title, content) = ReadBook.processChapterContent(currentBook, chapter, raw)
                    val heading = if (io.legado.app.help.config.ReadBookConfig.titleMode != 2 || chapter.isVolume)
                        title + "\n" else ""
                    heading + content.toString()
                } else ""
                val offset = if (text.isNotEmpty()) epubTextPosition(text, quote, fraction) else fallbackPosition
                appDb.bookDao.updateEpubPublisherState(currentBook.bookUrl, enabled, position,
                    chapter?.index ?: currentBook.durChapterIndex, offset, chapter?.title ?: currentBook.durChapterTitle)
                if (after != null) runOnUiThread { after() }
            } catch (error: Exception) {
                runOnUiThread { lastPersistedState = null; leaving = false; showError(error.localizedMessage.orEmpty()) }
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, R.id.menu_epub_publisher, 0, R.string.epub_switch_to_reader)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_epub_publisher && !leaving && book != null) {
            leaving = true
            capture {
                persist(enabled = false) {
                    val url = book!!.bookUrl
                    lifecycleScope.launch {
                        val updated = withContext(IO) { appDb.bookDao.getBook(url) } ?: return@launch
                        val nativeIntent = Intent(this@EpubPublisherActivity, ReadBookActivity::class.java)
                            .putExtra("bookUrl", url).putExtra("index", updated.durChapterIndex)
                            .putExtra("chapterPos", updated.durChapterPos)
                        // Reuse native layout's text-anchor restoration after actual pagination completes.
                        locator?.optString("quote")?.takeIf(String::isNotBlank)?.let {
                            nativeIntent.putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, -1)
                                .putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it)
                        }
                        startActivity(nativeIntent)
                        finish()
                    }
                }
            }
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showError(message: String) {
        binding.loading.isVisible = false
        binding.status.text = getString(R.string.epub_publisher_error, message)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        locator?.let { outState.putString("publisherLocator", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (!leaving) capture { if (!leaving) persist() }
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() { super.onResume(); webView?.onResume() }

    override fun onDestroy() {
        ready = false
        webView?.let { binding.content.removeView(it); it.stopLoading(); it.destroy() }
        session?.close()
        super.onDestroy()
    }
}
