@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.graphics.Rect
import android.graphics.drawable.StateListDrawable
import android.animation.ValueAnimator
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.core.view.get
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.SourceSharePassphraseImportPolicy
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.isIgnoredAppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.glass.BottomBarGlass
import io.legado.app.ui.widget.glass.ViewBackdrop
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.clearClip
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getClipText
import io.legado.app.utils.isCreated
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener,
    MainViewModel.CallBack {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idRss = 2
    private val idMy = 3
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition = 0
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 4
    private val EXIT_INTERVAL = 2000L
    /** 悬浮底栏玻璃表面透明度 0~255，来源 AppConfig.bottomBarGlassAlpha */
    private val glassSurfaceAlpha = mutableStateOf(204)
    /** 底栏玻璃底色（0 = 跟随主题），来源 AppConfig.bottomBarSurfaceColor */
    private val bottomBarSurfaceTint = mutableStateOf(0)
    /** 胶囊滑动速度 0~100，来源 AppConfig.bottomBarCapsuleSpeed */
    private val bottomBarCapsuleSpeed = mutableStateOf(50)
    /** 胶囊上下边距(dp)，来源 AppConfig.bottomBarCapsuleInset */
    private val bottomBarCapsuleInset = mutableStateOf(11)
    /** 底栏抖动/位移动效：按下时的左右抖动动画 */
    private var barShakeAnimator: ValueAnimator? = null
    /** 拖动跟随：每帧向目标靠拢的循环动画（"一直"缓慢跟随，不设结束时长） */
    private var barFollowAnimator: ValueAnimator? = null
    private var barFollowTargetX = 0f
    /** 悬浮底栏玻璃模糊半径(dp)，来源 AppConfig.bottomBarGlassBlur */
    private val glassBlurDp = mutableStateOf(24)
    /** 选中胶囊宽度（占单格百分比），来源 AppConfig.bottomBarIndicatorWidth */
    private val indicatorWidthPercent = mutableStateOf(82)
    /** 内容区滚动控件的原始底部 padding，用于叠加底栏留白而不重复累加 */
    private val contentBasePadding = WeakHashMap<View, Int>()
    /** 悬浮底栏当前选中项下标（等于 ViewPager 位置） */
    private val bottomBarSelectedIndex = mutableStateOf(0)
    /** 悬浮底栏可见项数量 */
    private val bottomBarItemCount = mutableStateOf(4)
    /** 悬浮底栏是否被按下（驱动玻璃层按压反馈） */
    private val bottomBarPressed = mutableStateOf(false)
    /** 拖动底栏时手指的横向比例(0..1)；-1 表示当前不在拖动 */
    private val bottomBarDragFraction = mutableStateOf(-1f)
    /** 底栏动效颜色（选中胶囊 + 按压光晕），来源 AppConfig.bottomBarEffectColor */
    private val bottomBarEffectColor = mutableStateOf(0)
    private val realPositions = arrayOf(idBookshelf, idExplore, idRss, idMy)
    private val menuIdToSlot = linkedMapOf(
        R.id.menu_bookshelf to "bookshelf",
        R.id.menu_discovery to "home",
        R.id.menu_rss to "notes",
        R.id.menu_my_config to "settings",
    )
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var onUpBooksBadgeView: BadgeView? = null
    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private var passphraseReadGeneration = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upHomePage()
        upBottomBarSkin()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                binding.viewPagerMain.currentItem = 0
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //隐私协议
            if (!privacyPolicy()) return@launch
            //版本更新
            upVersion()
            //设置本地密码
            setLocalPassword()
            notifyAppCrash()
            //备份同步
            backupSync()
            //设置回调
            viewModel.setActivityCallback(this@MainActivity)
            //自动更新书源
            binding.viewPagerMain.postDelayed(1000) {
                viewModel.ruleSubsUp()
            }
            scheduleSourceSharePassphraseRead(1500)
            //自动更新书籍
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                binding.viewPagerMain.postDelayed(2000) {
                    viewModel.upAllBookToc()
                }
            }
            binding.viewPagerMain.postDelayed(3000) {
                viewModel.postLoad()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SourceSharePassphraseImportPolicy.shouldScheduleOnResume(
                privacyPolicyOk = LocalConfig.privacyPolicyOk
            )
        ) {
            scheduleSourceSharePassphraseRead(500)
        }
    }

    override fun onPause() {
        super.onPause()
        pendingPassphraseRead = false
        passphraseReadGeneration++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingPassphraseRead && canAwaitPassphraseWindowFocus()) {
            readSourceSharePassphrase(200)
        } else if (hasFocus) {
            pendingPassphraseRead = false
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf ->
                viewPagerMain.setCurrentItem(0, false)

            R.id.menu_discovery ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)

            R.id.menu_rss ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)

            R.id.menu_my_config ->
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
        return false
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }

            R.id.menu_discovery -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[1] as? ExploreFragment)?.compressExplore()
                }
            }
        }
    }

    private fun initView() = binding.run {
        root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val keyboardHeight = windowInsets.imeHeight
            view.bottomPadding = keyboardHeight
            if (keyboardHeight > 0) view.doOnLayout {
                currentFocus?.takeIf { it.onCheckIsTextEditor() }?.let { input ->
                    input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
                }
            }
            windowInsets
        }
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = 3
        viewPagerMain.adapter = adapter
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        // 按下反馈交给玻璃层：底栏子项会消费触摸事件，setOnTouchListener 收不到，
        // 所以在 ThemeBottomNavigationVIew 的 dispatchTouchEvent 里回调
        bottomNavigationView.onPressChanged = { pressed ->
            bottomBarPressed.value = pressed
            if (pressed) {
                updateTabMagnify(-1f)
                shakeBar()
                scaleBarUp()
            } else {
                bottomNavigationView.resetItemScale()
                resetBarScale()
            }
        }
        // 在底栏上按住横向拖动：胶囊跟手，松手切到手指所在的那一格
        bottomNavigationView.onDragFractionChanged = { fraction ->
            bottomBarDragFraction.value = fraction
            updateTabMagnify(fraction)
            shiftBar(fraction)
        }
        bottomNavigationView.onDragEnd = { fraction ->
            val count = bottomBarItemCount.value
            if (count > 0) {
                val index = (fraction * count).toInt().coerceIn(0, count - 1)
                // 先改选中态再结束拖动，避免胶囊从手指位置跳回旧格再滑过去
                bottomBarSelectedIndex.value = index
                binding.viewPagerMain.setCurrentItem(index, false)
            }
            bottomBarDragFraction.value = -1f
            bottomNavigationView.resetItemScale()
            resetBarShift()
            resetBarScale()
        }
        if (AppConfig.isEInkMode) {
            bottomNavigationView.setBackgroundResource(R.drawable.bg_eink_border_top)
        }
        // 悬浮底栏：导航栏高度作为「容器下外边距」，而不是底栏内部 padding。
        // 若留在底栏内部，图标会被整体顶高，玻璃面板下沿留白会明显大于上沿。
        bottomBarContainer.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            val target = height + 12.dpToPx()
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target
                view.layoutParams = lp
            }
            windowInsets.inset(0, 0, 0, height)
        }
        upBottomBarGlass()
        applyBottomBarLayout()
        bottomBarSelectedIndex.value = viewPagerMain.currentItem
        initBottomBarGlass()
        binding.viewPagerMain.post { applyContentBottomInset() }
    }

    /**
     * 读取设置并刷新悬浮底栏的液态玻璃参数。
     * 透明度与模糊半径都是 Compose state，改完立即重组生效。
     */
    private fun upBottomBarGlass() {
        glassSurfaceAlpha.value = AppConfig.bottomBarGlassAlpha.coerceIn(0, 100) * 255 / 100
        glassBlurDp.value = AppConfig.bottomBarGlassBlur.coerceIn(0, 60)
        bottomBarSurfaceTint.value = AppConfig.bottomBarSurfaceColor
        bottomBarCapsuleSpeed.value = AppConfig.bottomBarCapsuleSpeed.coerceIn(0, 100)
        bottomBarCapsuleInset.value = AppConfig.bottomBarCapsuleInset.coerceIn(0, 24)
        indicatorWidthPercent.value = AppConfig.bottomBarIndicatorWidth.coerceIn(40, 120)
        bottomBarEffectColor.value = AppConfig.bottomBarEffectColor
    }

    /**
     * 悬浮液态玻璃底栏。
     *
     * 底栏的交互、菜单显隐、皮肤、角标全部仍由 [ThemeBottomNavigationVIew] 负责，
     * 它只是透明；玻璃由同一容器内的 ComposeView 在它下层绘制。
     *
     * 注意：背景模糊依赖 RenderEffect，需要 Android 12(API 31) 及以上；
     * 更低版本库内部会直接跳过模糊，只剩半透明面板（不会崩）。
     */
    private fun initBottomBarGlass() {
        val glassBase = if (ColorUtils.isColorLight(ThemeStore.bottomBackground(this))) {
            Color.White
        } else {
            Color(0xFF121212)
        }
        val backdrop = ViewBackdrop(binding.viewPagerMain)
        binding.bottomBarGlass.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        binding.bottomBarGlass.setContent {
            // 底色：自定义色（alpha>0）优先，否则沿用主题白/黑；透明度仍由设置项控制
            val surfaceTint = bottomBarSurfaceTint.value
            val glassSurface = if ((surfaceTint ushr 24) != 0) Color(surfaceTint) else glassBase
            BottomBarGlass(
                backdrop = backdrop,
                surfaceColor = glassSurface.copy(alpha = glassSurfaceAlpha.value / 255f),
                blurRadius = glassBlurDp.value.dp,
                indicatorColor = Color(bottomBarEffectColor.value),
                itemCount = bottomBarItemCount.value,
                selectedIndex = bottomBarSelectedIndex.value,
                pressed = bottomBarPressed.value,
                indicatorWidthPercent = indicatorWidthPercent.value,
                dragFraction = bottomBarDragFraction.value,
                slideStiffness = 180f + bottomBarCapsuleSpeed.value * 4.8f,
                capsuleInset = bottomBarCapsuleInset.value.dp
            )
        }
        // 保险：Glass 的 Compose 内容首次测量若撑满可用高度，会把 wrap_content 的
        // 底栏容器顶成整屏高、底栏被挤到屏幕顶端。布局完成后强制它与底栏等高。
        binding.bottomNavigationView.doOnLayout { bar ->
            val glass = binding.bottomBarGlass
            if (glass.layoutParams.height != bar.height) {
                glass.layoutParams = glass.layoutParams.apply { height = bar.height }
            }
        }
    }

    /**
     * 按下底栏时的左右抖动：阻尼衰减的一次横向摆动（整条底栏，含玻璃与图标）。
     */
    private fun shakeBar() {
        val bar = binding.bottomBarContainer
        barShakeAnimator?.cancel()
        val amp = 6.dpToPx().toFloat()
        val animator = ValueAnimator.ofFloat(
            0f, -amp, amp * 0.7f, -amp * 0.4f, amp * 0.15f, 0f
        ).apply {
            duration = 300
            addUpdateListener { bar.translationX = it.animatedValue as Float }
        }
        barShakeAnimator = animator
        animator.start()
    }

    /**
     * 拖动胶囊时，整条底栏朝胶囊所在的一侧**持续缓慢**滑一点。
     *
     * 位移量 = 胶囊中心相对底栏中心的偏移 × 0.20，上限 ±36dp；
     * 时长不设上限：拖动期间由一个循环 animator 每帧向目标靠拢（约 8%/帧），
     * 所以底栏会"一直"跟着走，松手才回位。
     */
    private fun shiftBar(fraction: Float) {
        val bar = binding.bottomBarContainer
        barShakeAnimator?.cancel()
        if (bar.width <= 0) return
        val amp = 36.dpToPx().toFloat()
        barFollowTargetX = ((fraction - 0.5f) * bar.width * 0.20f).coerceIn(-amp, amp)
        if (barFollowAnimator == null) {
            barFollowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    bar.translationX += (barFollowTargetX - bar.translationX) * 0.08f
                }
                start()
            }
        }
    }

    /** 停止拖动跟随（松手时调用） */
    private fun stopBarFollow() {
        barFollowAnimator?.cancel()
        barFollowAnimator = null
        barFollowTargetX = 0f
    }

    /**
     * 按下底栏时整条底栏轻微放大（胶囊停止/松手时复原）。
     */
    private fun scaleBarUp() {
        binding.bottomBarContainer.animate()
            .scaleX(1.025f).scaleY(1.025f)
            .setDuration(140)
            .start()
    }

    private fun resetBarScale() {
        binding.bottomBarContainer.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(180)
            .start()
    }

    /** 松手后底栏回到原位 */
    private fun resetBarShift() {
        val bar = binding.bottomBarContainer
        barShakeAnimator?.cancel()
        stopBarFollow()
        bar.animate().translationX(0f).setDuration(180).start()
    }

    /**
     * 图标放大镜：按下/拖动时让胶囊附近的图标放大，松手恢复。
     *
     * @param centerXFraction 胶囊中心横向比例(0..1)；-1 表示用当前选中项定位
     */
    private fun updateTabMagnify(centerXFraction: Float) {
        val bar = binding.bottomNavigationView
        val scale = AppConfig.bottomBarMagnify.coerceIn(100, 200) / 100f
        if (scale <= 1.001f || bar.width <= 0) {
            bar.resetItemScale()
            return
        }
        val count = bottomBarItemCount.value.coerceAtLeast(1)
        val centerX = if (centerXFraction in 0f..1f) {
            centerXFraction * bar.width
        } else {
            (bottomBarSelectedIndex.value + 0.5f) * bar.width / count
        }
        bar.magnifyItems(centerX, scale)
    }

    /**
     * 应用悬浮底栏的尺寸设置（高度、左右边距）。
     * 玻璃层必须与底栏等高，否则玻璃面板会大于或小于图标区域。
     */
    private fun applyBottomBarLayout() {
        val marginH = AppConfig.bottomBarMargin.dpToPx()
        val barHeight = AppConfig.bottomBarHeight.dpToPx()

        val containerLp = binding.bottomBarContainer.layoutParams as ViewGroup.MarginLayoutParams
        if (containerLp.marginStart != marginH || containerLp.marginEnd != marginH) {
            containerLp.marginStart = marginH
            containerLp.marginEnd = marginH
            binding.bottomBarContainer.layoutParams = containerLp
        }
        if (binding.bottomNavigationView.layoutParams.height != barHeight) {
            binding.bottomNavigationView.layoutParams.height = barHeight
            binding.bottomNavigationView.requestLayout()
        }
        if (binding.bottomBarGlass.layoutParams.height != barHeight) {
            binding.bottomBarGlass.layoutParams.height = barHeight
            binding.bottomBarGlass.requestLayout()
        }
        binding.viewPagerMain.post { applyContentBottomInset() }
    }

    /**
     * 悬浮底栏会盖住内容区底部，必须给滚动控件补足底部留白，
     * 否则列表拉到最下面时最后几项会被底栏挡住。
     * 原始 padding 记录在 [contentBasePadding] 里，避免重复累加。
     */
    private fun applyContentBottomInset() {
        val space = bottomBarOccupiedHeight()
        if (space <= 0) return
        forEachScrollable(binding.viewPagerMain) { scrollable ->
            val base = contentBasePadding.getOrPut(scrollable) { scrollable.paddingBottom }
            val target = base + space
            if (scrollable.paddingBottom != target) {
                scrollable.clipToPadding = false
                scrollable.setPadding(
                    scrollable.paddingLeft,
                    scrollable.paddingTop,
                    scrollable.paddingRight,
                    target
                )
            }
        }
    }

    /** 底栏实际占用的高度 = 玻璃面板高度 + 下外边距(含导航栏高度) */
    private fun bottomBarOccupiedHeight(): Int {
        val bar = binding.bottomBarContainer
        if (bar.height <= 0) return 0
        val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
        return lp.bottomMargin + bar.height
    }

    private fun forEachScrollable(root: View, action: (ViewGroup) -> Unit) {
        if (root is RecyclerView || root is NestedScrollView || root is ScrollView) {
            action(root as ViewGroup)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                forEachScrollable(root.getChildAt(i), action)
            }
        }
    }

    /**
     * 用户隐私与协议
     */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resume(false)
            }
        }
    }

    /**
     * 版本更新日志
     */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()) {
                    AppUpdate.gitHubUpdate.check(lifecycleScope)
                        .onSuccess {
                            if (isIgnoredAppUpdate(it.tagName, LocalConfig.ignoreUpdateVersion)) {
                                return@onSuccess
                            }
                            if (supportFragmentManager.isStateSaved) return@onSuccess
                            showDialogFragment(
                                UpdateDialog(it)
                            )
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(
                getString(R.string.help),
                help,
                TextDialog.Mode.MD,
                showToc = true,
            )
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else {
            block.resume(null)
        }
    }

    /**
     * 设置本地密码
     */
    private suspend fun setLocalPassword() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.password != null) {
            block.resume(null)
            return@sc
        }
        alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            onDismiss {
                block.resume(null)
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton {
                LocalConfig.password = ""
            }
        }
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton {
                        LocalConfig.lastBackup = maxOf(
                            LocalConfig.lastBackup,
                            lastBackupFile.lastModify,
                        )
                    }
                    okButton {
                        viewModel.restoreWebDav(
                            lastBackupFile.displayName,
                            lastBackupFile.lastModify,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(0)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                upBottomBarSkin()
                if (it) {
                    viewPagerMain.setCurrentItem(bottomMenuCount - 1, false)
                }
            }
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_SKIN) {
            upBottomBarSkin()
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_GLASS) {
            upBottomBarGlass()
            applyBottomBarLayout()
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery
            menu.findItem(R.id.menu_rss).isVisible = showRss
        }
        var index = 0
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        if (showRss) {
            index++
            realPositions[index] = idRss
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        bottomBarItemCount.value = bottomMenuCount
        adapter.notifyDataSetChanged()
    }

    private fun scheduleSourceSharePassphraseRead(delayMillis: Long) {
        passphraseReadGeneration++
        pendingPassphraseRead = true
        if (hasWindowFocus()) {
            readSourceSharePassphrase(delayMillis, passphraseReadGeneration)
        }
    }

    private fun canAwaitPassphraseWindowFocus(): Boolean {
        return SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
            privacyPolicyOk = LocalConfig.privacyPolicyOk,
            isFinishing = isFinishing,
            isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isFragmentStateSaved = supportFragmentManager.isStateSaved
        )
    }

    private fun readSourceSharePassphrase(
        delayMillis: Long,
        generation: Int = passphraseReadGeneration
    ) {
        pendingPassphraseRead = false
        binding.viewPagerMain.postDelayed(delayMillis) {
            if (generation != passphraseReadGeneration) return@postDelayed
            val hasWindowFocus = hasWindowFocus()
            if (!hasWindowFocus) {
                pendingPassphraseRead = canAwaitPassphraseWindowFocus()
            }
            if (!SourceSharePassphraseImportPolicy.canReadClipboard(
                    privacyPolicyOk = LocalConfig.privacyPolicyOk,
                    isFinishing = isFinishing,
                    isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    isFragmentStateSaved = supportFragmentManager.isStateSaved,
                    hasWindowFocus = hasWindowFocus
                )
            ) {
                return@postDelayed
            }
            val text = getClipText()?.takeIf { it.isNotBlank() } ?: return@postDelayed
            if (text == lastPassphraseText) return@postDelayed
            when (val result = SourceSharePassphrase.decode(text)) {
                SourceSharePassphrase.DecodeResult.NotFound -> {
                    lastPassphraseText = null
                }

                SourceSharePassphrase.DecodeResult.Invalid -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_invalid)
                }

                SourceSharePassphrase.DecodeResult.Expired -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_expired)
                }

                is SourceSharePassphrase.DecodeResult.Success -> {
                    lastPassphraseText = text
                    clearClip()
                    val value = result.value
                    when (value.type) {
                        SourceSharePassphrase.Type.BOOK_SOURCE ->
                            showDialogFragment(ImportBookSourceDialog(value.url))

                        SourceSharePassphrase.Type.RSS_SOURCE ->
                            showDialogFragment(ImportRssSourceDialog(value.url))

                        SourceSharePassphrase.Type.DICT_RULE ->
                            showDialogFragment(ImportDictRuleDialog(value.url))

                        SourceSharePassphrase.Type.REPLACE_RULE ->
                            showDialogFragment(ImportReplaceRuleDialog(value.url))

                        SourceSharePassphrase.Type.TOC_RULE ->
                            showDialogFragment(ImportTxtTocRuleDialog(value.url))

                        SourceSharePassphrase.Type.TTS_RULE ->
                            showDialogFragment(ImportHttpTtsDialog(value.url))

                        SourceSharePassphrase.Type.AUTO_TASK ->
                            showDialogFragment(ImportAutoTaskDialog(value.url))
                    }
                }
            }
        }
    }

    private fun upBottomBarSkin() {
        val skin = BottomBarSkinManager.active
        if (skin.isEmpty() || !BottomBarSkinManager.hasSkin(skin)) {
            binding.bottomNavigationView.applySkin(null, 0)
            return
        }
        val sizePx = 30.dpToPx()
        val map = HashMap<Int, StateListDrawable>()
        menuIdToSlot.forEach { (id, slot) ->
            BottomBarSkinManager.getStateDrawable(skin, slot, sizePx)?.let { map[id] = it }
        }
        binding.bottomNavigationView.applySkin(map, sizePx)
    }

    private fun upHomePage() {
        when (AppConfig.defaultHomePage) {
            "bookshelf" -> {}
            "explore" -> if (AppConfig.showDiscovery) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)
            }

            "rss" -> if (AppConfig.showRSS) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)
            }

            "my" -> binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            bottomBarSelectedIndex.value = position
            binding.bottomNavigationView.menu[realPositions[position]].isChecked = true
            binding.viewPagerMain.post { applyContentBottomInset() }
        }

    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idExplore && any is ExploreFragment)
                || (fragmentId == idRss && any is RssFragment)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idRss -> RssFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }

    }

    override fun openImportUi(type:Int, source: String) {
        when (type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(source)
            )
            1 -> showDialogFragment(
                ImportRssSourceDialog(source)
            )
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(source)
            )
        }
    }

}
