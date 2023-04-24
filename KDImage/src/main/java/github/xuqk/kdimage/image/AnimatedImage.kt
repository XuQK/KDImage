package github.xuqk.kdimage.image

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import github.xuqk.kdimage.KdImageState
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Author: XuQK
 * site: https://github.com/XuQK
 * Email: xuqiankun66@gmail.com
 *
 * Created Time: 2023/2/17 11:06
 *
 * 动态图加载组件，包括 GIF，动态 webp，动态 heif
 */
@Composable
internal fun AnimatedImage(
    drawBounds: Rect,
    imageInputStream: suspend () -> InputStream?,
    streamToAnimatedDrawableConverter: suspend (InputStream) -> Drawable?,
    onStateChange: (state: KdImageState) -> Unit
) {
    var drawable: Drawable? by remember { mutableStateOf(null) }
    var invalidateTick by remember { mutableStateOf(0) }
    val drawableCallback = remember(drawable) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                invalidateTick++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            }
        }
    }

    LaunchedEffect(imageInputStream, streamToAnimatedDrawableConverter) {
        withContext(Dispatchers.IO) {
            drawable = imageInputStream()?.use { streamToAnimatedDrawableConverter(it) }
            drawable?.let {
                it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
            }
            (drawable as? Animatable)?.start()
        }
        if (drawable == null) {
            onStateChange(KdImageState.FAILED)
        } else {
            onStateChange(KdImageState.SUCCEED)
        }
    }
    Box(modifier = Modifier
        .fillMaxSize()
        .drawWithContent {
            if (invalidateTick > Int.MAX_VALUE - 2) return@drawWithContent
            drawable?.let {
                if (it.callback == null) {
                    it.callback = drawableCallback
                }
                (it as? Animatable)?.let { anim ->
                    if (!anim.isRunning) {
                        anim.start()
                    }
                }
                clipRect {
                    withTransform(
                        transformBlock = {
                            translate(drawBounds.left, drawBounds.top)
                            scale(drawBounds.width / it.intrinsicWidth, Offset.Zero)
                        }
                    ) {
                        it.draw(drawContext.canvas.nativeCanvas)
                    }
                }
            }
        }
    )
}
