package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import io.legado.app.R

/**
 * 与 [SeekBarPreference] 功能完全一致，只是换了竖排布局：
 * 标题/摘要在上、调节滑杆在下，两者分成两行。
 *
 * 单独建类而不是改共用布局，是为了不影响同样使用
 * [SeekBarPreference] 的其它设置项（如"欢迎页显示时长"）。
 */
class VerticalSeekBarPreference(context: Context, attrs: AttributeSet) :
    SeekBarPreference(context, attrs) {

    init {
        layoutResource = R.layout.view_preference_seekbar_vertical
    }
}
