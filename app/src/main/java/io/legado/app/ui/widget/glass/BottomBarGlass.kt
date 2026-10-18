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
 * 液态玻璃底栏面板（按 kyant0 backdrop 的分层思路组装）。
 *
 * 自下而上的图层：
 *  - L0 背景采样：`ViewBackdrop` + `vibrancy()` + `blur()` + `lens()`（真实内容透出）
 *  - L1 表面着色：竖向渐变（上略实、下略透），避免下沿发白
 *  - L2 边缘光：1dp 内描边（上亮下暗）+ 顶部一条镜面高光
 *  - L3 选中胶囊：外发光 → 本体渐变 → 顶部内高光 → 上亮下暗亮边
 *  - L4 动效：弹簧滑动 / 拖动跟手 / 按压与拖动时放大提亮
 *
 * 胶囊按下或拖动时会放大，并直接跟手滑动（不再做"从原格延伸"的拉伸效果）。
 * 图标本身的放大由 [io.legado.app.ui.main.MainActivity] 通过
 * `ThemeBottomNavigationVIew.magnifyItems` 负责。
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
    cornerRadius: Dp = 28.dp,
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

    // 拖动中 pressed 常为 false，所以提亮/放大要同时看拖动状态
    val dragging = dragFraction in 0f..1f
    val pressScale by animateFloatAsState(
        targetValue = when {
            pressed -> 1.45f
            dragging -> 1.18f
            else -> 1f
        },
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
                        // L0 提饱和 + 背景模糊 + 边缘折射/色散
                        vibrancy()
                        if (blurRadius > 0.dp) {
                            blur(blurRadius.toPx())
                        }
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
                    val width = size.width
                    val height = size.height
                    val radius = cornerRadius.toPx()
                    val shapeCorner = CornerRadius(radius, radius)

                    // L1 表面着色：竖向渐变（上略实、下略透），底部不能更实否则下沿发白
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                surfaceColor,
                                surfaceColor.copy(
                                    alpha = (surfaceColor.alpha * 0.88f).coerceIn(0f, 1f)
                                )
                            ),
                            startY = 0f,
                            endY = height
                        )
                    )

                    // L2 边缘光：1dp 内描边（上亮下暗）+ 顶部镜面高光
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.26f),
                                Color.White.copy(alpha = 0.04f)
                            ),
                            startY = 0f,
                            endY = height
                        ),
                        cornerRadius = shapeCorner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                            startY = 0f,
                            endY = (height * 0.22f).coerceAtMost(10.dp.toPx())
                        ),
                        cornerRadius = shapeCorner,
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    // 按压/拖动光晕：跟随手指的柔光，替代 Material 水波纹
                    if (dragging) {
                        val glowCenter = Offset(dragFraction * width, height / 2f)
                        val glowRadius = height * 1.15f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    indicatorColor.copy(alpha = 0.22f + 0.16f * pressBoost),
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
                        val itemWidth = width / itemCount
                        val widthRatio = indicatorWidthPercent.coerceIn(40, 120) / 100f
                        val capsuleHeight = height * 0.62f * pressScale
                        val capsuleWidth = (itemWidth * widthRatio * pressScale)
                            .coerceAtMost(width)
                        val top = (height - capsuleHeight) / 2f
                        val corner = CornerRadius(capsuleHeight / 2f, capsuleHeight / 2f)

                        // 胶囊跟手：拖动时 capsulePosition 已 snap 到手指位置，
                        // 尺寸由 pressScale 放大（按下 1.45× / 拖动 1.18×）
                        val centerX = (capsulePosition + 0.5f) * itemWidth
                        val left = (centerX - capsuleWidth / 2f)
                            .coerceIn(0f, (width - capsuleWidth).coerceAtLeast(0f))
                        val drawWidth = capsuleWidth
                        val capsuleCenter = Offset(left + drawWidth / 2f, height / 2f)

                        // 外发光：让胶囊"浮"在玻璃上
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    indicatorColor.copy(alpha = 0.20f + 0.14f * pressBoost),
                                    Color.Transparent
                                ),
                                center = capsuleCenter,
                                radius = drawWidth * 0.75f
                            ),
                            radius = drawWidth * 0.75f,
                            center = capsuleCenter
                        )

                        // 本体：竖向渐变制造厚度
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
                            size = Size(drawWidth, capsuleHeight),
                            cornerRadius = corner
                        )

                        // 顶部内高光：上半段白雾
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.20f + 0.16f * pressBoost),
                                    Color.Transparent
                                ),
                                startY = top,
                                endY = top + capsuleHeight * 0.55f
                            ),
                            topLeft = Offset(left, top),
                            size = Size(drawWidth, capsuleHeight),
                            cornerRadius = corner
                        )

                        // 亮边：上亮下暗
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.42f + 0.20f * pressBoost),
                                    Color.White.copy(alpha = 0.10f + 0.06f * pressBoost)
                                ),
                                startY = top,
                                endY = top + capsuleHeight
                            ),
                            topLeft = Offset(left, top),
                            size = Size(drawWidth, capsuleHeight),
                            cornerRadius = corner,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            )
    )
}
