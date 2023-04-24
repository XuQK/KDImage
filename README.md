# KDImage

[![](https://jitpack.io/v/XuQK/KDImage.svg)](https://jitpack.io/#XuQK/KDImage)

可以当成是 Compose 版的 [Subsampling Scale Image View](https://github.com/davemorrissey/subsampling-scale-image-view)。

## 特点

- 完全 Compose 实现

- 支持超大图分片解析，查看 JPG、WebP、PNG、HEIF 图片时，分辨率超出一定大小时会自动分片解析
- 支持基本的双击放大手势；双击后按住不动，上滑缩小，下滑放大；点击；长按
- 支持 Android 原生支持的所有图片格式，具体请看，具体请看 [Image support](https://developer.android.com/guide/topics/media/media-formats#image-formats)

## 使用方式：

### 将JitPack存储库添加到构建文件(项目根目录下build.gradle文件)

```groovy
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

### 添加依赖项

```groovy
// 版本号参看Release
implementation 'com.github.XuQK:KDImage:versionCode'

// 若想要展示动图或 SVG 图，请自行导入 Coil 或 Glide
```

***注意：*** 动图和 SVG 图从 Stream 转 Drawable 需要借助另外的图片加载库，本库只实现了 Coil 和 Glide 的相关方法，若想自己实现，参考 [Converter.kt](https://github.com/XuQK/KDImage/blob/master/KDImage/src/main/java/github/xuqk/kdimage/Converter.kt) 的写法即可

### 使用说明

动图和 svg 加载，以 coil 为例：

```kotlin
KdImage(
    data = "",// 图片本地 url 与 imageInputStream 二选一
    imageInputStream = {
        // 获取图片 InputStream，与 data 二选一
    },
    streamToAnimatedDrawableConverter = // 生成 Stream 转 AnimatedDrawable 的方法，若不需要动图，可不传
        generateStreamToAnimatedDrawableConverterForCoil(
            context = context,
            imageLoader = context.imageLoader
        ),
    streamToSvgDrawableConverter = // 生成 Stream 转 SvgDrawable 的方法，若不需要 svg，可不传
        generateStreamToSvgDrawableConverterForCoil(
            context = context,
            imageLoader = context.imageLoader
        ),
    onStateChange = {
        // 加载状态变化回调
    },
    onClick = {  },
    onLongPress = {  }
)
```
