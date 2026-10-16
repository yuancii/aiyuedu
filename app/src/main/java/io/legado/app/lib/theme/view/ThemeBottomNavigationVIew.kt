package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    private val themeIconTint: ColorStateList

    /**
     * 按下状态回调：按下 true，抬起/取消 false。驱动玻璃层的按压反馈。
     *
     * 注意必须在 dispatchTouchEvent 里取，不能用 setOnTouchListener：
     * 触摸事件会被底栏的子项消费，OnTouchListener 收不到。
     */
    var onPressChanged: ((Boolean) -> Unit)? = null

    /** 拖动中的横向位置比例(0..1)，用于让选中胶囊跟手 */
    var onDragFractionChanged: ((Float) -> Unit)? = null

    /** 抬起时的横向位置比例(0..1)，用于把选中项切到手指所在的那一格 */
    var onDragEnd: ((Float) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val fraction = if (width > 0) (ev.x / width).coerceIn(0f, 1f) else 0f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onPressChanged?.invoke(true)
                onDragFractionChanged?.invoke(fraction)
            }

            MotionEvent.ACTION_MOVE -> onDragFractionChanged?.invoke(fraction)

            MotionEvent.ACTION_UP -> {
                onPressChanged?.invoke(false)
                onDragEnd?.invoke(fraction)
            }

            MotionEvent.ACTION_CANCEL -> onPressChanged?.invoke(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** menu id -> 默认矢量图标; applySkin(null) 时据此还原 */
    private val defaultIcons = mapOf(
        R.id.menu_bookshelf to R.drawable.ic_bottom_books,
        R.id.menu_discovery to R.drawable.ic_bottom_explore,
        R.id.menu_rss to R.drawable.ic_bottom_rss_feed,
        R.id.menu_my_config to R.drawable.ic_bottom_person,
    )

    init {
        val transparentNavBar = context.transparentNavBar
        val bgColor = if (transparentNavBar) context.backgroundColor else context.bottomBackground
        // 悬浮液态玻璃底栏：本控件不再自绘背景与阴影，
        // 玻璃表面与投影由同一容器内的 BottomBarGlass(Compose) 负责。
        // bgColor 仍用于判断文字明暗。
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        val textIsDark = ColorUtils.isColorLight(bgColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
        themeIconTint = colorStateList
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        // 不使用 Material 的 itemBackground 水波纹：在"拖动切换选中项"时
        // 它由子项自行管理生命周期，程序化改选中态后不会被正确取消，
        // 会残留粉红色波纹在原来那一格上。
        // 点击反馈改由 BottomBarGlass 的按压光晕承担（完全可控，不会残留）。
        isItemHorizontalTranslationEnabled = false
        itemBackground = Color.TRANSPARENT.toDrawable()

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
