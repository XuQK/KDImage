package github.xuqk.kdimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import coil.ImageLoader
import coil.drawable.ScaleDrawable
import coil.request.ImageRequest
import coil.size.Size
import com.bumptech.glide.Glide
import java.io.InputStream

/**
 * Author: XuQK
 * Email: xuqiankun@amberweather.com
 *
 * Created Time: 2023/2/20 14:37
 */

/**
 * Coil 框架的 gif Drawable 获取函数
 *
 * @param imageLoader 此 imageLoader 需要实现加载 gif 功能
 * @return 返回一个 动态图 的实现类，是 Drawable 的子类，实现了 [Animatable] 接口
 */
fun generateStreamToAnimatedDrawableConverterForCoil(
    context: Context,
    imageLoader: ImageLoader
): suspend (InputStream) -> Drawable? = {
    try {
        val byteArray = it.readBytes()
        val drawable = imageLoader.execute(
            ImageRequest.Builder(context)
                .data(byteArray)
                .size(Size.ORIGINAL)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build()
        ).drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (drawable as? ScaleDrawable)?.child
        } else {
            drawable
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Coil 框架的 svg Drawable 获取函数，固定宽高 1000
 *
 * @param imageLoader 此 imageLoader 需要实现加载 svg 功能
 * @return 返回一个 svg 原始大小的的 [BitmapDrawable] 对象
 */
fun generateStreamToSvgDrawableConverterForCoil(context: Context, imageLoader: ImageLoader): suspend (InputStream) -> Drawable? =
    {
        try {
            val byteArray = it.readBytes()
            imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(byteArray)
                    .size(1000, 1000)
                    .bitmapConfig(Bitmap.Config.ARGB_8888)
                    .build()
            ).drawable
        } catch (e: Exception) {
            null
        }
    }

/**
 * Glide 框架的 gif Drawable 获取函数，Glide 本身需要加载 GIF 加载功能
 *
 * @return 返回一个 动态图 的实现类，是 Drawable 的子类，实现了 [Animatable] 接口
 */
fun generateStreamToAnimatedDrawableConverterForGlide(context: Context): suspend (InputStream) -> Drawable? = {
    try {
        val byteArray = it.readBytes()
        Glide.with(context).asGif().load(byteArray).submit().get()
    } catch (e: Exception) {
        null
    }
}

/**
 * Glide 框架的 svg Drawable 获取函数，Glide 本身需要有 SVG 加载功能
 *
 * @return 返回一个 svg 原始大小的的 [BitmapDrawable] 对象
 */
fun generateStreamToSvgDrawableConverterForGlide(context: Context): suspend (InputStream) -> Drawable? = {
    try {
        val byteArray = it.readBytes()
        Glide.with(context).load(byteArray).submit().get()
    } catch (e: Exception) {
        null
    }
}
