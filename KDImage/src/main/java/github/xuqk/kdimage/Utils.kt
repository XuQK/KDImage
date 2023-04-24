package github.xuqk.kdimage

import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.compose.ui.unit.IntSize
import java.io.InputStream

/**
 * Author: XuQK
 * Email: xuqiankun@amberweather.com
 *
 * Created Time: 2023/2/14 17:03
 */
private const val enableDebugLog = false

internal fun log(any: Any?) {
    if (enableDebugLog) {
        val trace = Throwable().stackTrace[1]
        Log.d(trace.fileName, "${trace.methodName}: $any")
    }
}

/**
 * 仅获取图片尺寸
 */
internal suspend fun getImageSize(
    inputStream: suspend () -> InputStream?,
    sampleSize: Int = 1,
    streamToSvgDrawableConverter: (suspend (InputStream) -> Drawable?)? = null
): IntSize {
    val bo = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inSampleSize = sampleSize
    }
    inputStream()?.use { it ->
        BitmapFactory.decodeStream(
            it,
            null,
            bo
        )
    }
    if (bo.outWidth < 0) {
        // 表明不是位图，可能是 SVG，进行尝试
        streamToSvgDrawableConverter?.let { converter ->
            inputStream()?.use { converter(it) }?.let { svg ->
                return IntSize(svg.intrinsicWidth, svg.intrinsicHeight)
            }
        } ?: return IntSize.Zero
    } else {
        return IntSize(bo.outWidth, bo.outHeight)
    }
}

/**
 * @return true 表示支持动态图显示
 */
internal fun isApplicableAnimated(imageType: ImageType): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        imageType == ImageType.GIF || imageType == ImageType.WEBP_ANIMATE || imageType == ImageType.HEIF_ANIMATED
    } else {
        imageType == ImageType.GIF
    }
}
