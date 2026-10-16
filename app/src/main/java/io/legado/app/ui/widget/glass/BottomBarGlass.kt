package io.legado.app.ui.widget.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃底栏面板。
 *
 * 图层内容（全部被裁剪在圆角形状内）：
 *  1. vibrancy 提饱和 + blur 背景模糊 + lens 边缘折射/色散 —— 玻璃质感
 *  2. 表面着色 —— 由"透明度"设置控制
 *  3. 选中项胶囊 —— 渐变玻璃质感 + 细亮边，跟手拖动 / 弹簧滑动，按下放大并提亮
 *
 * ⚠️ 这里**只能**用 fillMaxWidth()，绝不能用 fillMaxSize()：
 * ComposeView 放在 wrap_content 的父容器里，首次测量拿到的是 AT_MOST
 * （可用高度=整屏），fillMaxSize() 会撑满该最大值，把底栏容器顶成整屏高，
 * 底栏随即被挤到屏幕顶端而"消失"。高度由父容器与 XML 的 match_parent 决定。
 *
 * @param surfaceColor   玻璃表面着色（含透明度），透明度由设置项控制
 * @param blurRadius     背景模糊半径，0 表示不模糊（设置项控制）
 * @param indicatorColor 选中项胶囊颜色
 * @param itemCount      底栏可见项数量
 * @param selectedIndex  当前选中项下标（对应 ViewPager 位置）
 * @param pressed        底栏是否被按下（驱动按压反馈）
 * @param indicatorWidthPercent 选中胶囊宽度占单格宽度的百分比（设置项控制）
 * @param dragFraction   拖动中手指的横向比例(0..1)；-1 表示不在拖动
 */
@Composable
fun BottomBarGlass(
    backdrop: Backdrop,
    surfaceColor: Color,
    blurRadius: Dp,
    indicatorColor: Color,
    itemCount: Int,
    selectedIndex: Int,
    pressed: Boolean,
    indicatorWidthPercent: Int,
    dragFraction: Float,
    cornerRadius: Dp = 28.dp
) {
    val glassShape = RoundedCornerShape(cornerRadius)

    // 选中胶囊位置（单位：格）。拖动时直接跟手，非拖动时弹性滑到选中格。
    // 用 Animatable 而非 animateFloatAsState：松手时才能从"手指所在位置"
    // 继续动画，否则会先跳回旧格再滑过去。
    val capsuleIndex = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex, dragFraction, itemCount) {
        if (dragFraction in 0f..1f && itemCount > 0) {
            capsuleIndex.snapTo(dragFraction * itemCount - 0.5f)
        } else {
            capsuleIndex.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f)
            )
        }
    }
    // 在组合期读取，保证位置变化会触发重绘（onDrawSurface 不在 observeReads 内）
    val capsulePosition = capsuleIndex.value

    // 按压反馈：按下时胶囊明显放大 + 提亮（尺寸与亮度双重反馈）。
    // 放大倍数取 1.45（原 1.12/1.32 都不够明显），弹簧放慢让形变看得见。
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1.45f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "bottomBarPress"
    )
    // 0..1 的按压进度，用于同时提亮
    val pressBoost = ((pressScale - 1f) / 0.45f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { glassShape },
                effects = {
                    // 本段运行在绘制路径上，一旦抛异常会中断整帧、连带吃掉底栏图标，
                    // 因此必须整体兜住（例如 lens 对不支持的形状会抛异常）
                    try {
                        // 提饱和：参考库所有液态玻璃组件都会调用，缺了会显得灰白
                        vibrancy()
                        if (blurRadius > 0.dp) {
                            blur(blurRadius.toPx())
                        }
                        // 折射 + 色散：玻璃边缘的"液态"观感来源，需要 Android 13+
                        lens(
                            refractionHeight = 24.dp.toPx(),
                            refractionAmount = 24.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    } catch (_: Throwable) {
                    }
                },
                // 必须裁剪：玻璃层录制的背景是矩形，且会因模糊半径向四周外扩
                // (padding)，不裁剪就会在圆角之外露出灰色矩形边角。
                layerBlock = {
                    clip = true
                    shape = glassShape
                },
                onDrawSurface = {
                    drawRect(surfaceColor)
                    // 按压光晕：跟随手指的柔光，替代 Material 水波纹。
                    // 水波纹在"拖动切换选中项"时会残留在原来那一格；这个由
                    // pressed 状态驱动，抬起即消失，不可能残留。
                    if (pressed && dragFraction in 0f..1f) {
                        val glowCenter = Offset(dragFraction * size.width, size.height / 2f)
                        val glowRadius = size.height * 1.15f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    indicatorColor.copy(alpha = 0.30f * pressBoost),
                                    Color.Transparent
                                ),
                                center = glowCenter,
                                radius = glowRadius
                            ),
                            radius = glowRadius,
                            center = glowCenter
                        )
                    }
                    if (itemCount > 0) {
                        val itemWidth = size.width / itemCount
                        val widthRatio = indicatorWidthPercent.coerceIn(40, 120) / 100f
                        val capsuleWidth = (itemWidth * widthRatio * pressScale)
                            .coerceAtMost(size.width)
                        val capsuleHeight = size.height * 0.62f * pressScale
                        val centerX = (capsulePosition + 0.5f) * itemWidth
                        val left = centerX - capsuleWidth / 2f
                        val top = (size.height - capsuleHeight) / 2f
                        val corner = CornerRadius(capsuleHeight / 2f, capsuleHeight / 2f)

                        // 玻璃质感：竖向渐变(上亮下深)制造厚度感，再叠一圈细亮边。
                        // 纯色块看着是个色斑，渐变+亮边才有"玻璃颗粒"的观感。
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    indicatorColor.copy(alpha = 0.34f + 0.22f * pressBoost),
                                    indicatorColor.copy(alpha = 0.14f + 0.12f * pressBoost)
                                ),
                                startY = top,
                                endY = top + capsuleHeight
                            ),
                            topLeft = Offset(left, top),
                            size = Size(capsuleWidth, capsuleHeight),
                            cornerRadius = corner
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.22f + 0.20f * pressBoost),
                            topLeft = Offset(left, top),
                            size = Size(capsuleWidth, capsuleHeight),
                            cornerRadius = corner,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            )
    )
}
