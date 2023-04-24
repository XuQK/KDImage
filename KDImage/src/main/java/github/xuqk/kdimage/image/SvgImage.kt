package github.xuqk.kdimage.image

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
 * Created Time: 2023/2/17 11:07
 *
 * Svg 图片的加载组件
 */
@Composable
internal fun SvgImage(
    drawBounds: Rect,
    imageInputStream: suspend () -> InputStream?,
    streamToSvgDrawableConverter: suspend (InputStream) -> Drawable?,
    onStateChange: (state: KdImageState) -> Unit
) {
    var drawable: Drawable? by remember { mutableStateOf(null) }
    LaunchedEffect(imageInputStream, streamToSvgDrawableConverter) {
        withContext(Dispatchers.IO) {
            drawable = imageInputStream()?.use { streamToSvgDrawableConverter(it) }?.apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
            if (drawable == null) {
                onStateChange(KdImageState.FAILED)
            } else {
                onStateChange(KdImageState.SUCCEED)
            }
        }
    }
    Box(modifier = Modifier
        .fillMaxSize()
        .drawWithContent {
            drawable?.let {
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
