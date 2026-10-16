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
class ViewBackdrop(private val target: View) : Backdrop {

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

            val barX = coords.positionInWindow().x
            val barY = coords.positionInWindow().y
            target.getLocationInWindow(targetLocation)
            val dx = targetLocation[0] - barX
            val dy = targetLocation[1] - barY

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val save = native.save()
                try {
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
