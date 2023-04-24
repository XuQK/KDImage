package github.xuqk.kdimage.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import androidx.annotation.Px
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import github.xuqk.kdimage.ImageType
import github.xuqk.kdimage.KdImageState
import github.xuqk.kdimage.getImageSize
import github.xuqk.kdimage.log
import java.io.InputStream
import java.lang.Float.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Author: XuQK
 * site: https://github.com/XuQK
 * Email: xuqiankun66@gmail.com
 *
 * Created Time: 2023/2/17 11:07
 *
 * 一般图片加载组件，静态图。
 *
 * 根据当前绘制范围和 canvasSize 计算出来的采样率不等于初始采样率时，进行区域解码和分片绘制。
 *
 * 如果图片支持区域解码，分片时会进行区域解码，
 * 如果不支持，会将整个图直接进行分片。
 */
@Composable
internal fun NormalImage(
    imageType: ImageType,
    originalImageSize: IntSize,
    canvasSize: Size,
    drawBounds: Rect,
    imageInputStream: suspend () -> InputStream?,
    onStateChange: (state: KdImageState) -> Unit
) {
    // 使用区域解码时，它就是缩略图，不使用时，它就是根据缩放获取到的原图
    var thumbImage: ImageBitmap? by remember { mutableStateOf(null) }
    // 初始的图片绘制范围，即 scaleFraction = 1，没有任何位移时的绘制范围
    var initialSampleSize: Int by remember { mutableStateOf(0) }
    // 结合缩放和移动过后的绘制范围
    val canRegionDecode: Boolean by remember(originalImageSize, canvasSize, imageType) {
        mutableStateOf(
            imageType == ImageType.JPG ||
                imageType == ImageType.PNG ||
                imageType == ImageType.WEBP ||
                imageType == ImageType.HEIF
        )
    }

    val currSampleSize by remember(originalImageSize, drawBounds) {
        mutableStateOf(
            getSampleSize(
                originalImageSize.width.toFloat(),
                originalImageSize.height.toFloat(),
                drawBounds.width,
                drawBounds.height
            ).coerceAtLeast(getMinSampleSize(imageType, initialSampleSize))
        )
    }

    // 每分片标准大小为画布大小
    val tileWidth by remember(canvasSize.width) {
        mutableStateOf((canvasSize.width).toInt())
    }
    val tileHeight by remember(canvasSize.height) {
        mutableStateOf((canvasSize.height).toInt())
    }
    var tileMatrix: List<List<Tile>> by remember { mutableStateOf(emptyList()) }

    LaunchedEffect(canvasSize) {
        // 此处是为计算图片的初始 bounds
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // 获取初始采样率和对应缩略图
            initialSampleSize = getSampleSize(
                originalImageSize.width.toFloat(),
                originalImageSize.height.toFloat(),
                canvasSize.width,
                canvasSize.height
            )
            thumbImage = getImage(imageInputStream, initialSampleSize)
            log("originalImageSize = $originalImageSize, canvasSize = $canvasSize, 得到初始采样率：$initialSampleSize")
        }
        // 缩略图加载成功，即认为加载成功
        if (thumbImage == null) {
            onStateChange(KdImageState.FAILED)
        } else {
            onStateChange(KdImageState.SUCCEED)
        }
    }

    // 采样率变化时，重新取图
    LaunchedEffect(currSampleSize, initialSampleSize) {
        if (drawBounds.width <= 0 || drawBounds.height <= 0) return@LaunchedEffect
        if (initialSampleSize < 1) return@LaunchedEffect
        log("采样率变化，重新取图 currSampleSize=$currSampleSize")
        withContext(Dispatchers.IO) {
            log("采用分区解码：$canRegionDecode")
            // 首先，由于采样率变化，需要释放当前所有分片 bitmap 资源
            tileMatrix.forEach { r -> r.forEach { c -> c.release() } }
            tileMatrix = emptyList()
            // 使用区域解码
            if (currSampleSize < initialSampleSize) {
                // 当前采样率小于初始（缩略图）采样率时，进行分片（由于逻辑限制，初始采样率一定是最大的采样率）
                tileMatrix = if (canRegionDecode) {
                    splitToTileMatrixForReginDecode(imageInputStream, currSampleSize, tileWidth, tileHeight) ?: return@withContext
                } else {
                    splitToTileMatrixForWhole(imageInputStream, currSampleSize, tileWidth, tileHeight) ?: return@withContext
                }
                log("当前采样率($currSampleSize)小于初始采样率($initialSampleSize)，进行切片，切片结果：row=${tileMatrix.size}, column=${tileMatrix.firstOrNull()?.size}")
            }
        }
    }

    val scope = rememberCoroutineScope()
    val recomposeScope = currentRecomposeScope
    Box(modifier = Modifier
        .fillMaxSize()
        .drawWithContent {
            // 首先绘制缩略图
            thumbImage?.let { ti ->
                clipRect {
                    withTransform(
                        transformBlock = {
                            translate(drawBounds.left, drawBounds.top)
                            scale(drawBounds.width / ti.width, Offset.Zero)
                        }
                    ) {
                        drawImage(
                            image = ti,
                            topLeft = Offset.Zero,
                        )
                    }
                }
            }

            if (tileMatrix.isNotEmpty()) {
                // 使用分片
                log("分片绘制开始 drawBounds=$drawBounds")
                // 再绘制具体的分片
                val fraction: Float = drawBounds.width / tileMatrix[0][0].fullWidth
                for (rowIndex in tileMatrix.indices) {
                    val rowFirstTile = tileMatrix[rowIndex][0]
                    // 排除上边无法显示到画布上的 tile
                    if (drawBounds.top < 0) {
                        // tile 上边落在 canvas 上侧，不会绘制在 canvas 上，忽略此 tile
                        if ((rowFirstTile.y.toFloat() + rowFirstTile.height) / rowFirstTile.fullHeight < -drawBounds.top / drawBounds.height) {
                            // 释放资源
                            repeat(tileMatrix[0].size) {
                                tileMatrix[rowIndex][it].release()
                            }
                            continue
                        }
                    }
                    // 排除下边无法显示到画布上的 tile
                    if (drawBounds.bottom > canvasSize.height) {
                        // tile 下边落在 canvas 下侧，不会绘制在 canvas 上，忽略此 tile
                        if (rowFirstTile.y.toFloat() / rowFirstTile.fullHeight > (drawBounds.height - (drawBounds.bottom - canvasSize.height)) / drawBounds.height) {
                            // 释放资源
                            repeat(tileMatrix[0].size) {
                                tileMatrix[rowIndex][it].release()
                            }
                            continue
                        }
                    }
                    for (columnIndex in tileMatrix[rowIndex].indices) {
                        val tile = tileMatrix[rowIndex][columnIndex]
                        // 排除左边无法显示到画布上的 tile
                        if (drawBounds.left < 0) {
                            // tile 左边落在 canvas 左侧，不会绘制在 canvas 上，忽略此 tile
                            if ((tile.x.toFloat() + tile.width) / tile.fullWidth < -drawBounds.left / drawBounds.width) {
                                // 释放资源
                                tile.release()
                                continue
                            }
                        }
                        // 排除右边无法显示到画布上的 tile
                        if (drawBounds.right > canvasSize.width) {
                            // tile 右边落在 canvas 右侧，不会绘制在 canvas 上，忽略此 tile
                            if (tile.x.toFloat() / tile.fullWidth > (drawBounds.width - (drawBounds.right - canvasSize.width)) / drawBounds.width) {
                                tile.release()
                                continue
                            }
                        }

                        log("绘制分片：row=$rowIndex, column=$columnIndex")
                        clipRect {
                            withTransform(
                                transformBlock = {
                                    translate(drawBounds.left, drawBounds.top)
                                    scale(fraction, Offset.Zero)
                                }
                            ) {
                                if (tile.image == null) {
                                    scope.launch {
                                        if (tile.prepareImage()) {
                                            recomposeScope.invalidate()
                                        }
                                    }
                                } else {
                                    drawImage(
                                        image = tile.image!!,
                                        topLeft = Offset(tile.x.toFloat(), tile.y.toFloat()),
                                    )
                                }
                            }
                        }
                    }
                }
                log("分片绘制结束 drawBounds=$drawBounds")
            }
        }
    )
}

/**
 * 获取原图和绘制范围相对应的采样率，结果始终为 2 的幂，采样原则为 FIT
 */
private fun getSampleSize(
    @Px srcWidth: Float,
    @Px srcHeight: Float,
    @Px dstWidth: Float,
    @Px dstHeight: Float,
): Int {
    return Integer.highestOneBit(
        max(srcWidth / dstWidth, srcHeight / dstHeight)
            .toInt()
            .coerceAtLeast(1)
    )
}

/**
 * 获取最小的采样率，对于无法分片解码的图像格式（比如 bmp），最小采样率需要酌情拉高，以减轻内存压力
 */
private fun getMinSampleSize(
    imageType: ImageType,
    initialSampleSize: Int,
): Int {
    return if (imageType == ImageType.JPG ||
        imageType == ImageType.PNG ||
        imageType == ImageType.WEBP ||
        imageType == ImageType.HEIF
    ) {
        1
    } else {
        (initialSampleSize / 4).coerceAtLeast(1)
    }
}

private suspend fun getImage(inputStreamFetcher: suspend () -> InputStream?, sampleSize: Int = 1): ImageBitmap? {
    val bitmap = inputStreamFetcher()?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }
    return bitmap?.asImageBitmap()
}

private suspend fun splitToTileMatrixForReginDecode(
    inputStream: suspend () -> InputStream?,
    currSampleSize: Int,
    tileWidth: Int,
    tileHeight: Int
): List<List<Tile>>? = withContext(Dispatchers.IO) {
    val newTileMatrix = mutableListOf<List<Tile>>()
    // 根据当前采样率获取到 bitmap 总大小
    val bitmapSize = getImageSize(inputStream, currSampleSize)
    // 当前采样率 bitmap 大小超限，进行分片绘制
    var nextX = 0
    var nextY = 0
    var nextTileWidth = 0
    var nextTileHeight = 0
    inputStream()?.use { inStream ->
        val regionDecoder = BitmapRegionDecoder.newInstance(inStream, false) ?: return@withContext null
        val fullWidth = regionDecoder.width
        val fullHeight = regionDecoder.height
        while (true) {
            ensureActive()
            // 横切
            val newTileRow = mutableListOf<Tile>()
            // 剩余高度不超过分片预定宽度的 1.5 倍时，直接将剩余高度分成一片
            val remainingHeight = bitmapSize.height - nextY
            nextTileHeight = if (remainingHeight < tileHeight * 1.5f) {
                remainingHeight
            } else {
                tileHeight
            }
            nextX = 0
            while (true) {
                ensureActive()
                // 纵切
                // 剩余宽度不超过分片预定宽度的 1.5 倍时，直接将剩余宽度分成一片
                val remainingWidth = bitmapSize.width - nextX
                nextTileWidth = if (remainingWidth < tileWidth * 1.5f) {
                    remainingWidth
                } else {
                    tileWidth
                }
                val left = nextX
                val top = nextY
                val nextWidth = nextTileWidth
                val nextHeight = nextTileHeight
                newTileRow.add(
                    Tile(
                        originImageFetcher = {
                            regionDecoder.decodeRegion(
                                android.graphics.Rect(
                                    left * currSampleSize,
                                    top * currSampleSize,
                                    left * currSampleSize + nextWidth * currSampleSize,
                                    top * currSampleSize + nextHeight * currSampleSize
                                ),
                                BitmapFactory.Options().apply {
                                    inSampleSize = currSampleSize
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                            )?.asImageBitmap()
                        },
                        x = nextX,
                        y = nextY,
                        width = nextWidth * currSampleSize,
                        height = nextHeight * currSampleSize,
                        fullWidth = fullWidth / currSampleSize,
                        fullHeight = fullHeight / currSampleSize,
                    )
                )
                nextX += nextTileWidth
                if (nextX >= bitmapSize.width) break
            }
            newTileMatrix.add(newTileRow)

            nextY += nextTileHeight
            if (nextY >= bitmapSize.height) break
        }
        return@withContext newTileMatrix
    } ?: return@withContext null
}

private suspend fun splitToTileMatrixForWhole(
    inputStream: suspend () -> InputStream?,
    currSampleSize: Int,
    tileWidth: Int,
    tileHeight: Int
): List<List<Tile>>? = withContext(Dispatchers.IO) {
    val newTileMatrix = mutableListOf<List<Tile>>()
    // 根据当前采样率获取到 bitmap 总大小
    val bitmapSize = getImageSize(inputStream, currSampleSize)
    // 当前采样率 bitmap 大小超限，进行分片绘制
    var nextX = 0
    var nextY = 0
    var nextTileWidth = 0
    var nextTileHeight = 0
    val bitmap = getImage(inputStream, currSampleSize) ?: return@withContext null
    while (true) {
        ensureActive()
        // 横切
        val newTileRow = mutableListOf<Tile>()
        // 剩余高度不超过分片预定宽度的 1.5 倍时，直接将剩余高度分成一片
        val remainingHeight = bitmapSize.height - nextY
        nextTileHeight = if (remainingHeight < tileHeight * 1.5f) {
            remainingHeight
        } else {
            tileHeight
        }
        nextX = 0
        while (true) {
            ensureActive()
            // 纵切
            // 剩余宽度不超过分片预定宽度的 1.5 倍时，直接将剩余宽度分成一片
            val remainingWidth = bitmapSize.width - nextX
            nextTileWidth = if (remainingWidth < tileWidth * 1.5f) {
                remainingWidth
            } else {
                tileWidth
            }
            val left = nextX
            val top = nextY
            val nextWidth = nextTileWidth
            val nextHeight = nextTileHeight
            newTileRow.add(
                Tile(
                    originImageFetcher = {
                        Bitmap.createBitmap(
                            bitmap.asAndroidBitmap(),
                            left,
                            top,
                            nextWidth,
                            nextHeight
                        ).asImageBitmap()
                    },
                    x = nextX,
                    y = nextY,
                    width = nextWidth,
                    height = nextHeight,
                    fullWidth = bitmap.width,
                    fullHeight = bitmap.height,
                )
            )
            nextX += nextTileWidth
            if (nextX >= bitmapSize.width) break
        }
        newTileMatrix.add(newTileRow)

        nextY += nextTileHeight
        if (nextY >= bitmapSize.height) break
    }
    return@withContext newTileMatrix
}

private data class Tile(
    val originImageFetcher: () -> ImageBitmap?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val fullWidth: Int,
    val fullHeight: Int
) {
    private var preparing = false

    var image: ImageBitmap? = null
        private set

    /**
     * @return true 表示取图成功，需要刷新界面
     */
    suspend fun prepareImage(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (image == null && !preparing) {
                preparing = true
                ensureActive()
                image = originImageFetcher()
                ensureActive()
                preparing = false
                return@withContext true
            }
        } catch (e: CancellationException) {
            release()
        }
        return@withContext false
    }

    fun release() {
        image?.asAndroidBitmap()?.recycle()
        image = null
        preparing = false
    }
}
