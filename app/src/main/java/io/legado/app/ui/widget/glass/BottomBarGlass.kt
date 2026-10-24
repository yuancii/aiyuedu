package io.legado.app.ui.widget.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.roundToInt

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
 * @param indicatorColor 按压光晕颜色（默认跟随主题强调色）
 * @param itemCount      底栏可见项数量
 * @param selectedIndex  当前选中项下标（对应 ViewPager 位置）
 * @param pressed        底栏是否被按下（驱动按压反馈）
 * @param indicatorWidthPercent 选中胶囊宽度占单格宽度的百分比（设置项控制）
 * @param dragFraction   拖动中手指的横向比例(0..1)；-1 表示不在拖动
 */
@Composable
fun BottomBarGlass(
    backdrop: Backdrop,
    capsuleBackdrop: Backdrop,
    surfaceColor: Color,
    blurRadius: Dp,
    indicatorColor: Color,
    itemCount: Int,
    selectedIndex: Int,
    pressed: Boolean,
    indicatorWidthPercent: Int,
    dragFraction: Float,
    capsuleColor: Color,
    capsuleOpacity: Int,
    refraction: Dp,
    capsuleDragRefraction: Dp,
    capsuleContentScale: Int,
    chromaticAberration: Boolean,
    edgeLight: Int,
    capsuleEdgeLight: Int,
    capsulePressScale: Int,
    capsuleBounce: Int,
    cornerRadius: Dp = 28.dp,
    /** 胶囊滑动刚度（spring stiffness），由「胶囊滑动速度」设置控制；420 = 原手感 */
    slideStiffness: Float = 420f,
    /** 胶囊宽高同时缩放；100 为默认尺寸 */
    capsuleSizePercent: Int = 100,
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
                animationSpec = spring(
                    dampingRatio = 1f - capsuleBounce.coerceIn(0, 100) * 0.008f,
                    stiffness = slideStiffness
                )
            )
        }
    }
    // 在组合期读取，保证位置变化会触发重绘（onDrawSurface 不在 observeReads 内）
    val capsulePosition = if (dragFraction in 0f..1f && itemCount > 0) {
        dragFraction * itemCount - 0.5f
    } else {
        capsuleIndex.value
    }

    // 拖动采用比点击更轻的放大，避免胶囊遮住相邻图标。
    val dragging = dragFraction in 0f..1f
    val maximumPressScale = capsulePressScale.coerceIn(100, 150) / 100f
    val pressScale by animateFloatAsState(
        targetValue = when {
            dragging -> 1f + (maximumPressScale - 1f) * 0.6f
            pressed -> maximumPressScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "bottomBarPress"
    )
    // 0..1 的按压进度，用于同时提亮
    val pressBoost = if (maximumPressScale > 1f) {
        ((pressScale - 1f) / (maximumPressScale - 1f)).coerceIn(0f, 1f)
    } else {
        0f
    }
    val edgeStrength = edgeLight.coerceIn(0, 100) / 100f
    val capsuleAlpha = capsuleOpacity.coerceIn(0, 100) / 100f
    val capsuleEdgeStrength = if (capsuleAlpha > 0f) {
        capsuleEdgeLight.coerceIn(0, 100) / 100f
    } else {
        0f
    }

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { glassShape },
                    effects = {
                        // 绘制路径上的效果需要兜底，避免单帧失败影响底栏图标。
                        try {
                            vibrancy()
                            if (blurRadius > 0.dp) blur(blurRadius.toPx())
                            if (refraction > 0.dp) {
                                lens(
                                    refractionHeight = refraction.toPx(),
                                    refractionAmount = refraction.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = chromaticAberration
                                )
                            }
                        } catch (_: Throwable) {
                        }
                    },
                    // 仅玻璃表面裁成圆角；胶囊在后面的独立 Canvas 中绘制。
                    layerBlock = {
                        clip = true
                        shape = glassShape
                    },
                    // 表面着色与边缘光在独立 Canvas 实时绘制，避免颜色被背景图层缓存。
                    onDrawSurface = {}
                )
        )
        Canvas(Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f || itemCount <= 0) return@Canvas

            val radius = cornerRadius.toPx().coerceIn(0f, minOf(width, height) / 2f)
            val shapeCorner = CornerRadius(radius, radius)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor,
                        surfaceColor.copy(alpha = surfaceColor.alpha * 0.88f)
                    ),
                    startY = 0f,
                    endY = height
                ),
                size = Size(width, height),
                cornerRadius = shapeCorner
            )
            if (edgeStrength > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.42f * edgeStrength),
                            Color.White.copy(alpha = 0.07f * edgeStrength)
                        ),
                        startY = 0f,
                        endY = height
                    ),
                    cornerRadius = shapeCorner,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f * edgeStrength),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = (height * 0.22f).coerceAtMost(10.dp.toPx())
                    ),
                    cornerRadius = shapeCorner,
                    style = Stroke(width = 1.2.dp.toPx())
                )
                if (chromaticAberration) {
                    val inset = 1.dp.toPx()
                    val fringeRadius = (radius - inset).coerceAtLeast(0f)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8FE6FF).copy(alpha = 0.24f * edgeStrength),
                                Color.Transparent,
                                Color(0xFFFF9BC8).copy(alpha = 0.22f * edgeStrength)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(width, height)
                        ),
                        topLeft = Offset(inset, inset),
                        size = Size(
                            (width - inset * 2f).coerceAtLeast(0f),
                            (height - inset * 2f).coerceAtLeast(0f)
                        ),
                        cornerRadius = CornerRadius(fringeRadius, fringeRadius),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }

        // 拖动胶囊独立采样当前页面，页面内容的放大由 capsuleBackdrop 完成。
        // 放在底栏表面着色之后、胶囊着色之前，避免表面颜色盖掉水滴效果。
        if (dragging && itemCount > 0 &&
            (capsuleDragRefraction > 0.dp || capsuleContentScale > 100)
        ) {
            BoxWithConstraints(Modifier.matchParentSize()) {
                if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    if (width > 0f && height > 0f) {
                        val itemWidth = width / itemCount
                        val sizeScale = capsuleSizePercent.coerceIn(60, 125) / 100f * pressScale
                        val capsuleHeight = height * 0.88f * sizeScale
                        val capsuleWidth = (itemWidth *
                            indicatorWidthPercent.coerceIn(40, 120) / 100f * sizeScale)
                            .coerceAtMost(width)
                        val top = (height - capsuleHeight) / 2f
                        val centerX = (capsulePosition + 0.5f) * itemWidth
                        val left = (centerX - capsuleWidth / 2f)
                            .coerceIn(0f, (width - capsuleWidth).coerceAtLeast(0f))
                        val density = LocalDensity.current
                        val lensWidth = with(density) { capsuleWidth.toDp() }
                        val lensHeight = with(density) { capsuleHeight.toDp() }
                        val capsuleShape = RoundedCornerShape(percent = 50)

                        Box(
                            Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                                .size(lensWidth, lensHeight)
                                .drawBackdrop(
                                    backdrop = capsuleBackdrop,
                                    shape = { capsuleShape },
                                    highlight = null,
                                    shadow = null,
                                    effects = {
                                        try {
                                            if (capsuleDragRefraction > 0.dp) {
                                                lens(
                                                    refractionHeight = capsuleDragRefraction.toPx() * 0.55f,
                                                    refractionAmount = capsuleDragRefraction.toPx(),
                                                    depthEffect = true,
                                                    chromaticAberration = chromaticAberration
                                                )
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    },
                                    layerBlock = {
                                        clip = true
                                        shape = capsuleShape
                                    },
                                    onDrawSurface = {}
                                )
                        )
                    }
                }
            }
        }

        Canvas(Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f || itemCount <= 0) return@Canvas

            // 动态内容在玻璃裁剪层之外绘制；页面切换不会复用旧胶囊画面。
            if (dragging) {
                val glowCenter = Offset(dragFraction * width, height / 2f)
                val glowRadius = height * 1.15f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            indicatorColor.copy(
                                alpha = indicatorColor.alpha * (0.08f + 0.12f * pressBoost)
                            ),
                            Color.Transparent
                        ),
                        center = glowCenter,
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = glowCenter
                )
            }

            val itemWidth = width / itemCount
            val sizeScale = capsuleSizePercent.coerceIn(60, 125) / 100f * pressScale
            val capsuleHeight = height * 0.88f * sizeScale
            val capsuleWidth = (itemWidth * indicatorWidthPercent.coerceIn(40, 120) / 100f *
                sizeScale).coerceAtMost(width)
            val top = (height - capsuleHeight) / 2f
            val corner = CornerRadius(capsuleHeight / 2f, capsuleHeight / 2f)
            val centerX = (capsulePosition + 0.5f) * itemWidth
            val left = (centerX - capsuleWidth / 2f)
                .coerceIn(0f, (width - capsuleWidth).coerceAtLeast(0f))
            val capsuleCenter = Offset(left + capsuleWidth / 2f, height / 2f)

            if (pressBoost > 0.01f || dragging) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            indicatorColor.copy(
                                alpha = indicatorColor.alpha * 0.16f * pressBoost
                            ),
                            Color.Transparent
                        ),
                        center = capsuleCenter,
                        radius = capsuleWidth * 0.75f
                    ),
                    radius = capsuleWidth * 0.75f,
                    center = capsuleCenter
                )
            }
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        capsuleColor.copy(
                            alpha = (capsuleColor.alpha * capsuleAlpha *
                                (1f + 0.16f * pressBoost)).coerceAtMost(1f)
                        ),
                        capsuleColor.copy(alpha = capsuleColor.alpha * capsuleAlpha * 0.62f)
                    ),
                    startY = top,
                    endY = top + capsuleHeight
                ),
                topLeft = Offset(left, top),
                size = Size(capsuleWidth, capsuleHeight),
                cornerRadius = corner
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(
                            alpha = (0.10f + 0.06f * pressBoost) * capsuleAlpha
                        ),
                        Color.Transparent
                    ),
                    startY = top,
                    endY = top + capsuleHeight * 0.55f
                ),
                topLeft = Offset(left, top),
                size = Size(capsuleWidth, capsuleHeight),
                cornerRadius = corner
            )
            if (capsuleEdgeStrength > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(
                                alpha = (0.68f + 0.12f * pressBoost) * capsuleEdgeStrength
                            ),
                            Color.White.copy(alpha = 0.16f * capsuleEdgeStrength)
                        ),
                        startY = top,
                        endY = top + capsuleHeight
                    ),
                    topLeft = Offset(left, top),
                    size = Size(capsuleWidth, capsuleHeight),
                    cornerRadius = corner,
                    style = Stroke(width = 1.dp.toPx())
                )
                if (chromaticAberration) {
                    val inset = 0.8.dp.toPx()
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF86DCFF).copy(alpha = 0.34f * capsuleEdgeStrength),
                                Color.Transparent,
                                Color(0xFFFF8FBF).copy(alpha = 0.30f * capsuleEdgeStrength)
                            ),
                            start = Offset(left, top),
                            end = Offset(left + capsuleWidth, top + capsuleHeight)
                        ),
                        topLeft = Offset(left + inset, top + inset),
                        size = Size(
                            (capsuleWidth - inset * 2f).coerceAtLeast(0f),
                            (capsuleHeight - inset * 2f).coerceAtLeast(0f)
                        ),
                        cornerRadius = CornerRadius(
                            (capsuleHeight / 2f - inset).coerceAtLeast(0f),
                            (capsuleHeight / 2f - inset).coerceAtLeast(0f)
                        ),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
    }
}
