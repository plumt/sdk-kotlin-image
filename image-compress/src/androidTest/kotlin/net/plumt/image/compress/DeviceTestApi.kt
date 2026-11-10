package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 실기기 시험 전용 짧은 표기(단위 시험의 TestApi.kt 와 같은 내용). 0.0.x 시험의 기대값(크기·품질)을 그대로 두려고 대입식 설정과 "실패는 던진다" 형태를 시험 쪽에만 둔다.
 * 공개 API 가 아니다 — 앱은 [CompressorConfig.builder] 와 [CompressionResult] 를 쓴다.
 */
internal class ImageCompressorBuilder {
    var format: ImageFormat = ImageFormat.JPEG
    var quality: Int = 80
    var maxWidth: Int = 1920
    var maxHeight: Int = 1920
    var maxDimension: Int
        get() = maxOf(maxWidth, maxHeight)
        set(value) {
            maxWidth = value
            maxHeight = value
        }
    var resizer: Resizer? = null
    var maxPixels: Long = 4096L * 4096L
    var targetBytes: Long? = null
        set(value) {
            field = value
            if (value != null) strategy = null
        }
    var minQuality: Int = TargetSizeStrategy.DEFAULT_MIN_QUALITY
    var dimensionStep: Float = TargetSizeStrategy.DEFAULT_DIMENSION_STEP
    var minDimension: Int = TargetSizeStrategy.DEFAULT_MIN_DIMENSION
    var strategy: CompressionStrategy? = null
        set(value) {
            field = value
            if (value != null) targetBytes = null
        }
    var encoder: Encoder = Encoder.Default
    var dispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    var preserveMetadata: Set<MetadataGroup> = emptySet()
    var backgroundColor: Int = 0xFFFFFFFF.toInt()

    fun centerSquare(size: Int, allowUpscale: Boolean = false) {
        resizer = CenterSquareResizer(size, allowUpscale)
    }

    /** 실제 빌더에 프로필을 적용한 값을 옮겨 온다. */
    fun profile(profile: CompressionProfile) {
        val b = CompressorConfig.builder().also { load(it) }.profile(profile)
        format = b.format
        quality = b.quality
        maxWidth = b.maxWidth
        maxHeight = b.maxHeight
        resizer = b.resizer
        maxPixels = b.maxPixels
        minQuality = b.minQuality
        dimensionStep = b.dimensionStep
        minDimension = b.minDimension
        encoder = b.encoder
        dispatcher = b.dispatcher
        preserveMetadata = b.preserveMetadata
        backgroundColor = b.backgroundColor
        val s = b.strategy
        val t = b.targetBytes
        if (s != null) strategy = s else targetBytes = t
        if (s == null && t == null) strategy = null
    }

    private fun load(b: CompressorConfig.Builder) {
        b.format(format).quality(quality).maxWidth(maxWidth).maxHeight(maxHeight).resizer(resizer).maxPixels(maxPixels)
            .minQuality(minQuality).dimensionStep(dimensionStep).minDimension(minDimension).encoder(encoder)
            .dispatcher(dispatcher).preserveMetadata(preserveMetadata).backgroundColor(backgroundColor)
        val s = strategy
        if (s != null) b.strategy(s) else b.targetBytes(targetBytes)
    }

    fun build(): CompressorConfig = CompressorConfig.builder().also { load(it) }.build()
}

internal fun ImageCompressor(block: ImageCompressorBuilder.() -> Unit = {}): ImageCompressor =
    ImageCompressor.create(ImageCompressorBuilder().apply(block).build())

internal fun ImageCompressor(profile: CompressionProfile, block: ImageCompressorBuilder.() -> Unit = {}): ImageCompressor =
    ImageCompressor.create(ImageCompressorBuilder().apply { profile(profile) }.apply(block).build())

/** 결과를 풀어 성공이면 이미지, 실패면 원인 예외를 그대로 던진다. */
internal fun CompressionResult.orThrow(): CompressedImage = when (this) {
    is CompressionResult.Success -> image
    is CompressionResult.Failure -> throw error
}

internal suspend fun ImageCompressor.compressOrThrow(source: ImageSource): CompressedImage = compress(source).orThrow()
internal suspend fun ImageCompressor.compressOrThrow(data: ByteArray): CompressedImage = compressOrThrow(ImageSource.bytes(data))
internal suspend fun ImageCompressor.compressOrThrow(file: File): CompressedImage = compressOrThrow(ImageSource.file(file))
internal suspend fun ImageCompressor.compressOrThrow(input: InputStream): CompressedImage = compressOrThrow(ImageSource.stream(input))
internal suspend fun ImageCompressor.compressOrThrow(resolver: ContentResolver, uri: Uri): CompressedImage =
    compressOrThrow(ImageSource.uri(resolver, uri))

internal suspend fun ImageCompressor.compressToFile(source: ImageSource, target: File): CompressedImage =
    compress(source, ImageTarget.file(target)).orThrow()

internal suspend fun ImageCompressor.compressToUri(source: ImageSource, resolver: ContentResolver, uri: Uri): CompressedImage =
    compress(source, ImageTarget.uri(resolver, uri)).orThrow()

/** 0.0.x 의 `writeTo` 와 같은 결과를 내는 시험용 쓰기. 실패는 [ImageIoException] 을 던진다. */
internal fun CompressedImage.writeTo(file: File) = writeOutput(ImageTarget.file(file), data)
internal fun CompressedImage.writeTo(resolver: ContentResolver, uri: Uri) = writeOutput(ImageTarget.uri(resolver, uri), data)
internal fun CompressedImage.writeTo(out: OutputStream) = writeOutput(ImageTarget.stream(out), data)

/** 이름 붙인 인자로 일부만 정하던 시험용 전략 생성. */
internal fun targetSize(
    targetBytes: Long,
    minQuality: Int = TargetSizeStrategy.DEFAULT_MIN_QUALITY,
    dimensionStep: Float = TargetSizeStrategy.DEFAULT_DIMENSION_STEP,
    minDimension: Int = TargetSizeStrategy.DEFAULT_MIN_DIMENSION,
): TargetSizeStrategy = TargetSizeStrategy(targetBytes, minQuality, dimensionStep, minDimension)
