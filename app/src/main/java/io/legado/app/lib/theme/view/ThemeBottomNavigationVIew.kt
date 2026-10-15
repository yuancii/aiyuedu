package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.help.config.AppConfig
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    private val themeIconTint: ColorStateList
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pageScrollProgress = 0f

    /** menu id -> 默认矢量图标; applySkin(null) 时据此还原 */
    private val defaultIcons = mapOf(
        R.id.menu_bookshelf to R.drawable.ic_bottom_books,
        R.id.menu_discovery to R.drawable.ic_bottom_explore,
        R.id.menu_rss to R.drawable.ic_bottom_rss_feed,
        R.id.menu_my_config to R.drawable.ic_bottom_person,
    )

    init {
        // The parent BlurView draws the translucent glass surface. Keeping this view
        // transparent lets the live backdrop blur remain visible behind every item.
        setWillNotDraw(false)
        background = Color.TRANSPARENT.toDrawable()
        elevation = 0f
        val bgColor = if (context.transparentNavBar) context.backgroundColor else context.bottomBackground
        val textIsDark = ColorUtils.isColorLight(bgColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
        themeIconTint = colorStateList
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        // Keep equal-width slots so the active capsule can travel without moving
        // neighboring tabs, including while the ViewPager is being swiped.
        isItemHorizontalTranslationEnabled = false
        // Avoid Material's accent-colored tap ripple; the selected capsule and
        // the existing subtle press motion provide the feedback instead.
        itemBackground = Color.TRANSPARENT.toDrawable()
        setItemActiveIndicatorEnabled(false)
        updateActiveIndicatorColor()

        // A small press-in followed by a springy release makes taps feel tangible
        // while returning false keeps Material's normal click and accessibility flow.
        post { installPressFeedback() }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    /**
     * 应用/取消底栏图集。
     * @param iconMap menu id -> StateListDrawable; 传 null 表示恢复默认主题图标
     * @param iconSizePx 有皮肤时的图标尺寸(像素)
     */
    fun applySkin(iconMap: Map<Int, StateListDrawable>?, iconSizePx: Int) {
        if (iconMap == null) {
            itemIconTintList = themeIconTint
            itemIconSize = 24.dpToPx()
            defaultIcons.forEach { (id, res) ->
                menu.findItem(id)?.icon = defaultIcon(res, tinted = false)
            }
        } else {
            itemIconTintList = null
            itemIconSize = iconSizePx
            defaultIcons.keys.forEach { id ->
                menu.findItem(id)?.icon =
                    iconMap[id] ?: defaultIcon(defaultIcons.getValue(id), tinted = true)
            }
        }
    }

    /** A quiet inset capsule separates the selected tab from the glass tray. */
    fun updateActiveIndicatorColor() {
        val bgColor = if (context.transparentNavBar) context.backgroundColor else context.bottomBackground
        val indicatorColor = ColorUtils.withAlpha(
            if (ColorUtils.isColorLight(bgColor)) Color.BLACK else Color.WHITE,
            if (AppConfig.isEInkMode) 0f else 0.12f
        )
        activePillPaint.color = indicatorColor
        invalidate()
    }

    /** Tracks the ViewPager drag so the selected capsule follows the finger. */
    fun setPageScrollProgress(position: Int, offset: Float) {
        val itemCount = visibleMenuItems().size.coerceAtLeast(1)
        pageScrollProgress = (position + offset.coerceIn(0f, 1f))
            .coerceIn(0f, (itemCount - 1).toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val menuView = getChildAt(0) as? ViewGroup ?: return
        val items = visibleMenuItems()
        if (items.isEmpty() || AppConfig.isEInkMode) return

        val progress = pageScrollProgress.coerceIn(0f, (items.size - 1).toFloat())
        val fromIndex = progress.toInt()
        val toIndex = (fromIndex + 1).coerceAtMost(items.size - 1)
        val fraction = progress - fromIndex
        val from = items[fromIndex]
        val to = items[toIndex]
        val inset = 4.dpToPx()
        val left = menuView.left + (from.left + (to.left - from.left) * fraction) + inset
        val right = menuView.left + (from.right + (to.right - from.right) * fraction) - inset
        val top = menuView.top.toFloat() + inset
        val bottom = menuView.bottom.toFloat() - inset
        val radius = (bottom - top) / 2f
        canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, activePillPaint)
    }

    private fun visibleMenuItems(): List<android.view.View> {
        val menuView = getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until menuView.childCount)
            .map(menuView::getChildAt)
            .filter { it.visibility == android.view.View.VISIBLE }
    }

    fun refreshPressFeedback() {
        post { installPressFeedback() }
    }

    private fun installPressFeedback() {
        val menuView = getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(index)
            itemView.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate().scaleX(0.95f).scaleY(0.95f)
                            .setDuration(90L)
                            .start()
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(230L)
                            .setInterpolator(OvershootInterpolator(1.35f))
                            .start()
                    }
                }
                false
            }
        }
    }

    private fun defaultIcon(resId: Int, tinted: Boolean) =
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            if (tinted) DrawableCompat.setTintList(this, themeIconTint)
        }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
