package io.legado.app.ui.widget.glass

import android.view.View
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import io.legado.app.lib.theme.backgroundColor

/**
 * 把目标 View（主 ViewPager）的绘制内容当作"背景层"采样。
 *
 * backdrop 库原本要求背景由 Compose 自己绘制（rememberLayerBackdrop），
 * 但 Legado 的主内容区是 View 体系的 ViewPager，所以这里实现 [Backdrop] 接口，
 * 在玻璃层绘制时把 ViewPager 的内容按窗口坐标偏移重绘一遍，
 * 之后的 blur / 折射就作用在这份真实内容上，玻璃才是"透过去"而不是纯色。
 *
 * 注意：本方法运行在整帧绘制过程中，任何异常都会冒泡出去、
 * 中断同一帧后续兄弟视图的绘制（表现为底栏图标整块消失）。
 * 因此这里必须吞掉所有异常——采样失败最多是"没有玻璃"，绝不能让界面残缺。
 */
class ViewBackdrop(
    private val target: View,
    private val magnification: Float = 1f
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    private val targetLocation = IntArray(2)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        try {
            val coords = coordinates ?: return
            if (!coords.isAttached || !target.isAttachedToWindow) return

            val origin = coords.positionInWindow()
            target.getLocationInWindow(targetLocation)
            // View.draw 不会补偿目标自身的滚动。ViewPager 切到“我的”后
            // scrollX 已经是数个屏宽，漏减它会采到空白或别的页面。
            val dx = targetLocation[0] - origin.x - target.scrollX
            val dy = targetLocation[1] - origin.y - target.scrollY

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val save = native.save()
                try {
                    // 用当前背景与页面替换采样区，避免未放大的旧文字透出成重影。
                    native.drawColor(target.context.backgroundColor)
                    val scale = magnification.coerceIn(1f, 2f)
                    if (scale > 1f) {
                        // 先绕胶囊中心放大，再把页面移到采样位置。
                        // 使用原始布局尺寸，不把折射效果的额外采样边距算进中心。
                        native.scale(
                            scale,
                            scale,
                            coords.size.width / 2f,
                            coords.size.height / 2f
                        )
                    }
                    native.translate(dx, dy)
                    target.draw(native)
                } finally {
                    native.restoreToCount(save)
                }
            }
        } catch (t: Throwable) {
            // 兜底：放弃本次背景采样。宁可没有玻璃，也不能让整帧绘制中断。
        }
    }
}
