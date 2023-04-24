package github.xuqk.kdimage

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.imageLoader
import java.io.InputStream

private data class Image(
    val name: String,
    val size: String,
    val resolution: String
)

class MainActivity : ComponentActivity() {
    private val imageList = listOf(
        Image(
            name = "a.jpg",
            size = "1.8 M",
            resolution = "1214 x 3096",
        ),
        Image(
            name = "b.jpg",
            size = "22.1 MB",
            resolution = "11935 x 8554",
        ),
        Image(
            name = "c.jpg",
            size = "28.9 MB",
            resolution = "29213 x 1200",
        ),
        Image(
            name = "d.png",
            size = "2.6 MB",
            resolution = "7800 x 6240",
        ),
        Image(
            name = "e.svg",
            size = "46 KB",
            resolution = "-",
        ),
        Image(
            name = "f.jpg",
            size = "27 KB",
            resolution = "500 x 500",
        ),
        Image(
            name = "h.gif",
            size = "677 KB",
            resolution = "799 x 799",
        ),
        Image(
            name = "i.webp",
            size = "666 KB",
            resolution = "799 x 799",
        ),
        Image(
            name = "",
            size = "?",
            resolution = "?",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    // GIF 支持
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                    // SVG 支持
                    add(SvgDecoder.Factory())
                }.build()
        )

        setContent {
            ImagePager(imageList = imageList)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImagePager(imageList: List<Image>) {
    val context = LocalContext.current
    HorizontalPager(pageCount = imageList.size, beyondBoundsPageCount = 1) {
        var loadState by remember { mutableStateOf(KdImageState.LOADING) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray)
        ) {
            KdImage(
                imageInputStream = { context.getInputStream(imageList[it]) },
                streamToAnimatedDrawableConverter = generateStreamToAnimatedDrawableConverterForCoil(
                    context = context,
                    imageLoader = context.imageLoader
                ),
                streamToSvgDrawableConverter = generateStreamToSvgDrawableConverterForCoil(
                    context = context,
                    imageLoader = context.imageLoader
                ),
                onStateChange = { loadState = it },
                onClick = { Toast.makeText(context, "单击", Toast.LENGTH_SHORT).show() },
                onLongPress = { Toast.makeText(context, "长按", Toast.LENGTH_SHORT).show() }
            )
            with(imageList[it]) {
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(12.dp),
                    text = "format: ${name.substringAfter(".")}, size: $size, resolution: $resolution",
                    color = Color.White
                )
            }

            when (loadState) {
                KdImageState.LOADING -> {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { }) {
                        Text(
                            modifier = Modifier.align(Alignment.Center),
                            text = "正在加载...",
                            fontSize = 36.sp,
                            color = Color.White
                        )
                    }
                }
                KdImageState.FAILED -> {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { }) {
                        Text(
                            modifier = Modifier.align(Alignment.Center),
                            text = "加载失败",
                            fontSize = 36.sp,
                            color = Color.White
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

private fun Context.getInputStream(image: Image): InputStream? {
    return try {
        assets.open(image.name)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}