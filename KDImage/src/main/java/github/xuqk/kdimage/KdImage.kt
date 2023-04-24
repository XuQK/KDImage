package github.xuqk.kdimage

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import github.xuqk.kdimage.image.AnimatedImage
import github.xuqk.kdimage.image.NormalImage
import github.xuqk.kdimage.image.SvgImage
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sqrt

/**
 * Author: XuQK
 * site: https://github.com/XuQK
 * Email: xuqiankun66@gmail.com
 *
 * Created Time: 2023/2/8 14:28
 *
 * 总原则，图片尺寸最大不超过图片本身的像素大小
 *
 * 大图浏览要解决的两个问题：
 * 1. 图片解析到内存中，占用内存过大导致 OOM。
 * 2. 完整图片一次性绘制到 Canvas 中，如果图片 size 过大，会导致越界错误。
 *
 * 解决方法，采用分片绘制：
 * 1. 对于支持区域解码的图片，采用区域解码分片，此方式，会需要缩略图，缩略图大小定为图片在画布中完全居中显示出来的大小。
 * 2. 对于不支持的，直接对完整 bitmap 分片，此操作可能导致 OOM 问题，所以需要限制采样率，规定最小采样率为初始采样率的 1/4。
 *
 * 图片初始大小规则，参见 [getInitialBounds] 方法注释。
 *
 * 双击缩放规则，参见 [synthesizedPointerInput] 方法说明
 *
 * 缩放上限：
 * 图片原图大小短边长度比对应 canvas 边的 2 倍还小的，上限为该对应 canvas 边的两倍。
 * 图片原图大小短边长度比对应 canvas 边的 2 倍还大的，上限为图片原始大小。
 *
 * 图片缩放限制：图片短边
 */

enum class KdImageState {
    LOADING,
    FAILED,
    SUCCEED,
}

internal fun getImageInputStream(context: Context, data: String?): InputStream? {
    data ?: return null
    return when {
        data.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(data))
        data.startsWith("/") -> FileInputStream(File(data))
        data.startsWith("file://") -> context.contentResolver.openInputStream(Uri.parse(data))
        else -> return null
    }
}

/**
 * @param data 图片文件的路径，目前支持三种模式：
 * 1. 绝对路径
 * 2. content://
 * 3. file://
 *
 * @param imageInputStream 图片文件的 inputStream 获取方法
 * @param streamToAnimatedDrawableConverter 将 stream 转换成动态 Drawable 的方法，得到的 Drawable 需要继承 [Drawable] 且实现了 [Animatable] 接口
 * @param streamToSvgDrawableConverter 将 stream 转换成 SVG Drawable 的方法，一般是转成 BitmapDrawable
 *
 * [data] 和 [imageInputStream]，根据实际情况传入一种即可，二者都传入且都不为 null 的情况下，会优先选择 [imageInputStream]
 */
@Composable
fun KdImage(
    data: String? = null,
    imageInputStream: (suspend () -> InputStream?)? = null,
    streamToAnimatedDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    streamToSvgDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    onStateChange: ((state: KdImageState) -> Unit)? = null,
    onClick: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    if (imageInputStream == null) {
        KdImage(
            data = data,
            streamToAnimatedDrawableConverter = streamToAnimatedDrawableConverter,
            streamToSvgDrawableConverter = streamToSvgDrawableConverter,
            onStateChange = onStateChange,
            onClick = onClick,
            onLongPress = onLongPress,
        )
    } else {
        KdImage(
            imageInputStream = imageInputStream,
            streamToAnimatedDrawableConverter = streamToAnimatedDrawableConverter,
            streamToSvgDrawableConverter = streamToSvgDrawableConverter,
            onStateChange = onStateChange,
            onClick = onClick,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun KdImage(
    data: String?,
    streamToAnimatedDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    streamToSvgDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    onStateChange: ((state: KdImageState) -> Unit)? = null,
    onClick: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val context = LocalContext.current
    KdImage(
        imageInputStream = { getImageInputStream(context, data) },
        streamToAnimatedDrawableConverter = streamToAnimatedDrawableConverter,
        streamToSvgDrawableConverter = streamToSvgDrawableConverter,
        onStateChange = onStateChange,
        onClick = onClick,
        onLongPress = onLongPress,
    )
}

@Composable
private fun KdImage(
    imageInputStream: suspend () -> InputStream?,
    streamToAnimatedDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    streamToSvgDrawableConverter: (suspend (InputStream) -> Drawable?)? = null,
    onStateChange: ((state: KdImageState) -> Unit)? = null,
    onClick: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    var state by remember { mutableStateOf(KdImageState.LOADING) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // 图片原始大小
    var originalImageSize by remember { mutableStateOf(IntSize.Zero) }
    // 图片实际格式
    var imageType: ImageType by remember { mutableStateOf(ImageType.UNKNOWN) }
    // 初始的图片绘制范围，即 FIT 模式的范围
    var initialBounds by remember { mutableStateOf(Rect.Zero) }
    // 图片可放大到的最大宽高
    var maxWidth by remember { mutableStateOf(0f) }
    var maxHeight by remember { mutableStateOf(0f) }
    // 结合缩放和移动过后的绘制范围
    var drawBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(state) {
        onStateChange?.invoke(state)
    }

    LaunchedEffect(canvasSize) {
        // 此处是为计算图片的初始 bounds
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        launch(Dispatchers.Default) {
            // 获取图片类型
            imageType = getImageType(imageInputStream)
            // 获取图片原始尺寸
            originalImageSize = getImageSize(imageInputStream, streamToSvgDrawableConverter = streamToSvgDrawableConverter)
            // 计算图片的第一位置 bounds
            initialBounds = getInitialBounds(originalImageSize, canvasSize)
            drawBounds = initialBounds
            log("初始化完毕, imageType=$imageType，originalImageSize=$originalImageSize, initialBounds=$initialBounds")
            // 计算最大缩放尺寸
            if (canvasSize.width / initialBounds.width > canvasSize.height / initialBounds.height) {
                // 宽度未填满 canvas，以宽度为标准
                maxWidth = if (originalImageSize.width > canvasSize.width * 2) {
                    originalImageSize.width.toFloat()
                } else {
                    canvasSize.width * 2f
                }
                maxHeight = maxWidth / initialBounds.width * initialBounds.height
            } else {
                // 高度未填满 canvas，以高度为标准
                maxHeight = if (originalImageSize.height > canvasSize.height * 2) {
                    originalImageSize.height.toFloat()
                } else {
                    canvasSize.height * 2f
                }
                maxWidth = maxHeight / initialBounds.height * initialBounds.width
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onSizeChanged { canvasSize = it.toSize() }
        .synthesizedPointerInput(
            initialBounds = { initialBounds },
            currBounds = { drawBounds },
            canvasSize = { canvasSize },
            changeBoundsTo = { newBounds ->
                // 这里是关于缩放上限的代码描述
                if (newBounds.width > maxWidth) {
                    if (drawBounds.width < maxWidth) {
                        drawBounds = Rect(
                            maxWidth * newBounds.left / newBounds.width,
                            maxHeight * newBounds.top / newBounds.height,
                            maxWidth * newBounds.right / newBounds.width,
                            maxHeight * newBounds.bottom / newBounds.height
                        )
                    }
                } else {
                    drawBounds = newBounds
                }
            },
            onClick = onClick,
            onLongPress = onLongPress,
        )
    ) {
        if (initialBounds != Rect.Zero) {
            // 此处判断通过，说明数据初始化完成，然后才能显示图片
            if (isApplicableAnimated(imageType) && streamToAnimatedDrawableConverter != null) {
                AnimatedImage(
                    drawBounds = drawBounds,
                    imageInputStream = imageInputStream,
                    streamToAnimatedDrawableConverter = streamToAnimatedDrawableConverter,
                    onStateChange = { state = it }
                )
            } else if (imageType == ImageType.SVG && streamToSvgDrawableConverter != null) {
                SvgImage(
                    drawBounds = drawBounds,
                    imageInputStream = imageInputStream,
                    streamToSvgDrawableConverter = streamToSvgDrawableConverter,
                    onStateChange = { state = it }
                )
            } else {
                NormalImage(
                    imageType = imageType,
                    originalImageSize = originalImageSize,
                    canvasSize = canvasSize,
                    drawBounds = drawBounds,
                    imageInputStream = imageInputStream,
                    onStateChange = { state = it }
                )
            }
        }
    }
}

/**
 * 根据图片原始大小和给出的画布大小，计算图片显示出来的 Bounds，具体规则：
 * 1. 如果图片两边长都小于对应的 canvas 边长的一半，采用 FIT 模式缩放到 canvas 一半大小。
 * 2. 如果图片两边长任意一边大于对应的 canvas 边长的一半，采用 FIT 模式缩放到 canvas 大小。
 */
private fun getInitialBounds(originalImageSize: IntSize, canvasSize: Size): Rect {
    val useHalfCanvas = originalImageSize.width * 2 < canvasSize.width && originalImageSize.height * 2 < canvasSize.height
    val finalCanvasSize = if (useHalfCanvas) {
        canvasSize / 2f
    } else {
        canvasSize
    }

    val canvasAspectRadio = finalCanvasSize.width / finalCanvasSize.height
    val drawableAspectRadio =
        originalImageSize.width / originalImageSize.height.toFloat()
    val bounds = if (canvasAspectRadio > drawableAspectRadio) {
        // 图片比例比屏幕窄
        val finalDrawableHeight = finalCanvasSize.height
        val finalDrawableWidth = drawableAspectRadio * finalDrawableHeight
        Rect(
            (finalCanvasSize.width - finalDrawableWidth) / 2,
            0f,
            (finalCanvasSize.width - finalDrawableWidth) / 2 + finalDrawableWidth,
            finalDrawableHeight
        )
    } else {
        // 图片比例比屏幕宽
        val finalDrawableWidth = finalCanvasSize.width
        val finalDrawableHeight = finalDrawableWidth / drawableAspectRadio
        Rect(
            0f,
            (finalCanvasSize.height - finalDrawableHeight) / 2,
            finalDrawableWidth,
            (finalCanvasSize.height - finalDrawableHeight) / 2 + finalDrawableHeight
        )
    }
    if (useHalfCanvas) {
        return bounds.translate(Offset(canvasSize.width / 4, canvasSize.height / 4))
    } else {
        return bounds
    }
}

/**
 * 双击缩放规则：
 * 1. 双击时，如果图片当前大小比初始大小大，缩放到初始大小。
 * 2. 反之，以双击的触摸点为中点，放大到 FILL 状态。
 */
private fun Modifier.synthesizedPointerInput(
    initialBounds: () -> Rect,
    currBounds: () -> Rect,
    canvasSize: () -> Size,
    changeBoundsTo: (Rect) -> Unit,
    onClick: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
) = composed {
    val scope = rememberCoroutineScope()
    var selfConsume = remember { false }
    var flingJob: Job? = remember { null }
    pointerInput(Unit) {
        detectSynthesizedGestures(
            onFirstDown = {
                flingJob?.cancel()
                flingJob = null
            },
            onDoubleTap = {
                // 有任何放大的图片，双击后都会到原始大小，否则，放大到 FILL 状态
                val newBounds = if (currBounds().width > initialBounds().width) {
                    initialBounds()
                } else {
                    if (canvasSize().width / initialBounds().width > canvasSize().height / initialBounds().height) {
                        // 放大到宽一致
                        initialBounds().calculateNewBoundsForScale(it, canvasSize().width / initialBounds().width, canvasSize())
                    } else {
                        // 放大到高一致
                        initialBounds().calculateNewBoundsForScale(it, canvasSize().height / initialBounds().height, canvasSize())
                    }
                }
                scope.launch {
                    Animatable(currBounds(), Rect.VectorConverter).animateTo(newBounds) {
                        changeBoundsTo(value)
                    }
                }
            },
            onLongPress = onLongPress,
            onTap = onClick,
            onDragStart = { position, prevPosition ->
                // 此处只需要判断左右滑动，然后接管事件，判定规则如下，优先级逐级降低
                // 1. 当前绘制范围任何部分超出画布时，才考虑接管手势
                // 2. 纵向滑动距离超过横向滑动距离时，必定接管手势
                // 3. 横向滑动距离超过纵向滑动距离时，根据当前 drawBounds 贴边情况和横向滑动方向判断是否接管手势
                if (currBounds().width > canvasSize().width || currBounds().height > canvasSize().height) {
                    // 条件 1 成立
                    val result = position - prevPosition
                    selfConsume = if (result.x.absoluteValue < result.y.absoluteValue) {
                        // 条件 2 成立
                        true
                    } else {
                        // 条件 3
                        if (position.x > prevPosition.x) {
                            // 手指往右移动，判定左边有没有到边
                            currBounds().left < 0
                        } else {
                            // 手指往左移动，判定右边有没有触边
                            currBounds().right > canvasSize().width
                        }
                    }
                }
            },
            onDrag = { change, dragAmount ->
                if (selfConsume) {
                    change.consume()
                    changeBoundsTo(
                        currBounds().calculateNewBoundsForMove(dragAmount, canvasSize())
                    )
                }
            },
            onDragEnd = { velocity ->
                val velocityDirection = sqrt(velocity.x * velocity.x + velocity.y * velocity.y)
                if (velocityDirection > 1000) {
                    flingJob = scope.launch {
                        var prevValue = 0f
                        val sin = velocity.y / velocityDirection
                        val cos = velocity.x / velocityDirection
                        animateDecay(
                            0f,
                            sqrt(velocity.x * velocity.x + velocity.y * velocity.y),
                            FloatExponentialDecaySpec()
                        ) { value, velocity ->
                            if (value == 0f) return@animateDecay

                            val currValue = value - prevValue
                            val oldBounds = currBounds()
                            val offset = Offset(currValue * cos, currValue * sin)
                            changeBoundsTo(
                                currBounds().calculateNewBoundsForMove(
                                    offset,
                                    canvasSize()
                                )
                            )
                            if (oldBounds == currBounds() || currValue > -1 && currValue < 1) {
                                cancel()
                            }
                            prevValue = value
                        }
                    }
                }
            },
            onGesture = { event, centroid, pan, zoom, rotation ->
                event.changes.forEach { it.consume() }
                changeBoundsTo(
                    currBounds().calculateNewBoundsForScale(centroid, zoom, canvasSize())
                        .calculateNewBoundsForMove(pan, canvasSize())
                )
            },
            onGestureEndOrCancel = {
                selfConsume = false
                if (currBounds().width < initialBounds().width || currBounds().height < initialBounds().height) {
                    scope.launch {
                        Animatable(
                            currBounds(),
                            Rect.VectorConverter
                        ).animateTo(initialBounds()) {
                            changeBoundsTo(value)
                        }
                    }
                }
            },
        )
    }
}

private fun Rect.calculateNewBoundsForScale(center: Offset, scaleFraction: Float, canvasSize: Size): Rect {
    val newBounds = Rect(
        center.x - (center.x - left) * scaleFraction,
        center.y - (center.y - top) * scaleFraction,
        center.x + (right - center.x) * scaleFraction,
        center.y + (bottom - center.y) * scaleFraction,
    )

    return newBounds.fitBounds(canvasSize)
}

private fun Rect.calculateNewBoundsForMove(offset: Offset, canvasSize: Size): Rect {
    if (offset == Offset.Zero) return this
    return translate(offset).fitBounds(canvasSize)
}

/**
 * 调整 Rect 最终位置
 */
private fun Rect.fitBounds(canvasSize: Size): Rect {
    var offsetX = 0f
    var offsetY = 0f
    // 调整 x 位置
    if (width <= canvasSize.width) {
        offsetX = (canvasSize.width - width) / 2 - left
    } else {
        if (left > 0) {
            offsetX = -left
        } else if (right < canvasSize.width) {
            offsetX = canvasSize.width - right
        }
    }
    // 调整 y 位置
    if (height <= canvasSize.height) {
        offsetY = (canvasSize.height - height) / 2 - top
    } else {
        if (top > 0) {
            offsetY = -top
        } else if (bottom < canvasSize.height) {
            offsetY = canvasSize.height - bottom
        }
    }
    return translate(offsetX, offsetY)
}
