package net.plumt.image.compress

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 이미지 압축기. 설정은 만들 때 고정되며 상태가 없어 앱에 하나(또는 용도별 하나)만 두고 여러 스레드가 공유해도 된다.
 *
 * ```kotlin
 * val compressor = ImageCompressor { maxDimension = 1920; format = ImageFormat.WEBP; quality = 80; targetBytes = 500_000 }
 * val result = compressor.compress(File("photo.jpg"))   // suspend, 기본 Dispatchers.IO
 * result.writeTo(File(cacheDir, "upload.webp"))
 * ```
 *
 * 어떤 설정에서도 원본 파일을 그대로 돌려주지 않는다 — 이미 작아도 다시 인코딩해 메타데이터(위치 등)를 제거한다.
 */
public interface ImageCompressor {
    /** 이 압축기의 확정된 설정. */
    public val config: CompressorConfig

    /**
     * [source] 를 압축한다. [CompressorConfig.dispatcher] 에서 실행되며 취소에 응한다(취소는 `CancellationException` 그대로).
     *
     * @throws ImageCompressionException 디코딩·인코딩·입출력·메모리·용량 목표 실패(원인은 `cause`)
     * @throws IllegalArgumentException 직접 만든 [Resizer]·[CompressionStrategy]·[Encoder] 가 잘못된 값을 냈거나 IAE 를 던진 경우.
     *   플랫폼 내부의 IAE 는 [ImageCompressionException] 으로 감싼다
     */
    public suspend fun compress(source: ImageSource): CompressedImage
}

/** 바이트 배열 입력. */
public suspend fun ImageCompressor.compress(data: ByteArray): CompressedImage = compress(ImageSource.bytes(data))

/** 파일 입력. */
public suspend fun ImageCompressor.compress(file: File): CompressedImage = compress(ImageSource.file(file))

/** `content://`·`file://` URI 입력. [resolver] 는 `context.contentResolver`. */
public suspend fun ImageCompressor.compress(resolver: ContentResolver, uri: Uri): CompressedImage =
    compress(ImageSource.uri(resolver, uri))

/** 스트림 입력. 전부 읽은 뒤 닫는다([ImageSource.stream] 참고). */
public suspend fun ImageCompressor.compress(input: InputStream): CompressedImage = compress(ImageSource.stream(input))

/** 압축해서 [target] 파일로 쓴다. */
public suspend fun ImageCompressor.compressToFile(source: ImageSource, target: File): CompressedImage =
    compress(source).also { it.writeTo(target, config.dispatcher) }

/** 압축해서 [uri] 로 쓴다. */
public suspend fun ImageCompressor.compressToUri(
    source: ImageSource,
    resolver: ContentResolver,
    uri: Uri,
): CompressedImage = compress(source).also { it.writeTo(resolver, uri, config.dispatcher) }

/** DSL 로 압축기를 만든다. 잘못된 설정은 [IllegalArgumentException]. */
public fun ImageCompressor(block: ImageCompressorBuilder.() -> Unit = {}): ImageCompressor =
    ImageCompressorBuilder().apply(block).buildCompressor()

/** [profile] 을 먼저 적용하고 [block] 으로 덮어써 압축기를 만든다. */
public fun ImageCompressor(profile: CompressionProfile, block: ImageCompressorBuilder.() -> Unit = {}): ImageCompressor =
    ImageCompressorBuilder().apply { profile(profile) }.apply(block).buildCompressor()

internal class DefaultImageCompressor(override val config: CompressorConfig) : ImageCompressor {
    override suspend fun compress(source: ImageSource): CompressedImage =
        withContext(config.dispatcher) {
            val job = coroutineContext.job
            CompressionPipeline(config).run(source) { job.ensureActive() }
        }
}

/** 확정된 압축 설정(읽기 전용). [ImageCompressorBuilder.build] 가 검증한 뒤 만든다. */
public class CompressorConfig internal constructor(
    public val format: ImageFormat,
    public val quality: Int,
    public val resizer: Resizer,
    public val maxPixels: Long,
    public val strategy: CompressionStrategy,
    public val encoder: Encoder,
    public val dispatcher: CoroutineDispatcher,
    public val preserveMetadata: Set<MetadataGroup>,
    public val backgroundColor: Int,
) {
    override fun toString(): String =
        "CompressorConfig(format=$format, quality=$quality, maxPixels=$maxPixels, resizer=$resizer, " +
            "strategy=$strategy, preserveMetadata=$preserveMetadata)"
}

/** [ImageCompressor] DSL. 기본값은 각 프로퍼티의 설명을 본다. */
public class ImageCompressorBuilder internal constructor() {
    /** 출력 형식. 기본 [ImageFormat.JPEG]. */
    public var format: ImageFormat = ImageFormat.JPEG

    /** 품질 1..100(기본 80). 용량 목표가 있으면 탐색의 상한이다. 품질이 없는 형식에서는 무시된다. */
    public var quality: Int = 80

    /** 결과 폭의 상한(px, 기본 1920). 종횡비를 유지하고 확대하지 않는다. [resizer] 를 지정하면 쓰이지 않는다. */
    public var maxWidth: Int = DEFAULT_MAX_DIMENSION

    /** 결과 높이의 상한(px, 기본 1920). */
    public var maxHeight: Int = DEFAULT_MAX_DIMENSION

    /** [maxWidth]·[maxHeight] 를 함께 설정하는 편의 프로퍼티. 읽으면 둘 중 큰 값. */
    public var maxDimension: Int
        get() = maxOf(maxWidth, maxHeight)
        set(value) {
            maxWidth = value
            maxHeight = value
        }

    /** 직접 만든 크기 규칙. 지정하면 [maxWidth]·[maxHeight] 대신 쓴다. */
    public var resizer: Resizer? = null

    /** 결과 픽셀 수 상한(기본 16,777,216 = 4096x4096). [resizer] 가 이보다 크게 계획해도 비율을 유지해 줄인다. */
    public var maxPixels: Long = DEFAULT_MAX_PIXELS

    /**
     * 용량 목표(바이트). 지정하면 [TargetSizeStrategy] 로 맞추고, 못 맞추면 [TargetSizeNotReachedException].
     * [strategy] 와는 둘 중 **나중에 대입한 것**이 쓰인다 — null 이 아닌 값을 넣으면 [strategy] 를 비운다.
     */
    public var targetBytes: Long? = null
        set(value) {
            field = value
            if (value != null) strategy = null
        }

    /** 용량 목표 탐색의 최소 품질(기본 30). [targetBytes] 가 있을 때만 쓰인다. */
    public var minQuality: Int = TargetSizeStrategy.DEFAULT_MIN_QUALITY

    /** 최소 품질로도 넘을 때 긴 변을 줄이는 배율(0 초과 1 미만, 기본 0.85). */
    public var dimensionStep: Float = TargetSizeStrategy.DEFAULT_DIMENSION_STEP

    /** 용량 목표를 위해 줄일 수 있는 긴 변의 하한(px, 기본 64). */
    public var minDimension: Int = TargetSizeStrategy.DEFAULT_MIN_DIMENSION

    /**
     * 직접 만든 인코딩 전략. 기본은 [targetBytes] 유무에 따라 한 번 인코딩 또는 용량 맞춤.
     * null 이 아닌 값을 넣으면 [targetBytes] 를 비운다 — 용량 목표가 있는 프로필 위에도 그대로 덮어쓸 수 있다.
     */
    public var strategy: CompressionStrategy? = null
        set(value) {
            field = value
            if (value != null) targetBytes = null
        }

    /** 직접 만든 인코더. 기본 [Encoder.Default]. */
    public var encoder: Encoder = Encoder.Default

    /**
     * 작업 디스패처. 기본 [Dispatchers.IO]. 한 장마다 디코딩한 Bitmap(수십 MB 일 수 있다)을 잡으므로 여러 장을 동시에 압축하면
     * 메모리가 장 수만큼 쌓인다. `Dispatchers.IO.limitedParallelism(2)` 처럼 동시 실행 수를 제한한 디스패처를 권한다.
     */
    public var dispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * 결과에 남길 메타데이터. **기본은 빈 집합 = 전부 제거**다.
     * [MetadataGroup.LOCATION] 을 넣는 것은 위치 노출을 감수하겠다는 명시적 선택이다.
     * 용량 목표와 함께 쓰면 붙인 뒤의 크기로 맞춘다.
     */
    public var preserveMetadata: Set<MetadataGroup> = emptySet()

    /**
     * JPEG 처럼 투명을 못 담는 형식에서 투명 픽셀 아래에 깔 색(기본 흰색 `0xFFFFFFFF`).
     * 반투명 색이면 흰색 위에 먼저 합성한다(투명 영역이 검게 나오지 않는다).
     */
    public var backgroundColor: Int = 0xFFFFFFFF.toInt()

    /**
     * 짧은 변 기준 가운데 정사각형으로 잘라 [size]x[size] 로 만든다([resizer] 를 대체한다).
     * 짧은 변이 [size] 보다 작으면 기본으로는 키우지 않아 결과가 더 작다. 언제나 [size] 가 필요하면 [allowUpscale] 을 true 로 한다.
     */
    public fun centerSquare(size: Int, allowUpscale: Boolean = false) {
        resizer = CenterSquareResizer(size, allowUpscale)
    }

    /** [profile] 의 설정을 이 빌더에 적용한다. 이후 대입한 값이 우선한다. */
    public fun profile(profile: CompressionProfile) {
        profile.applyTo(this)
    }

    internal fun build(): CompressorConfig {
        require(quality in 1..100) { "quality 는 1..100 이어야 한다: $quality" }
        require(maxWidth > 0 && maxHeight > 0) { "maxWidth/maxHeight 는 1 이상이어야 한다: ${maxWidth}x$maxHeight" }
        require(maxPixels > 0) { "maxPixels 는 1 이상이어야 한다: $maxPixels" }
        val target = targetBytes
        val custom = strategy
        val resolvedStrategy = when {
            custom != null -> custom
            target != null -> {
                require(minQuality <= quality) { "minQuality($minQuality) 는 quality($quality) 이하여야 한다" }
                TargetSizeStrategy(target, minQuality, dimensionStep, minDimension)
            }
            else -> SinglePassStrategy
        }
        return CompressorConfig(
            format = format,
            quality = quality,
            resizer = resizer ?: FitResizer(maxWidth, maxHeight),
            maxPixels = maxPixels,
            strategy = resolvedStrategy,
            encoder = encoder,
            dispatcher = dispatcher,
            preserveMetadata = preserveMetadata.toSet(),
            backgroundColor = backgroundColor,
        )
    }

    internal fun buildCompressor(): ImageCompressor = DefaultImageCompressor(build())

    private companion object {
        const val DEFAULT_MAX_DIMENSION = 1920
        const val DEFAULT_MAX_PIXELS = 4096L * 4096L
    }
}

/**
 * 이름 붙인 설정 묶음(프리셋). 직접 정의해 `ImageCompressor(profile) { ... }` 로 쓴다.
 *
 * ```kotlin
 * val Banner = CompressionProfile("banner") { maxWidth = 1200; maxHeight = 400; format = ImageFormat.WEBP }
 * ```
 */
public class CompressionProfile(public val name: String, private val configure: ImageCompressorBuilder.() -> Unit) {
    init {
        require(name.isNotBlank()) { "프로필 이름이 비어 있다" }
    }

    internal fun applyTo(builder: ImageCompressorBuilder) = builder.configure()

    override fun toString(): String = "CompressionProfile($name)"
}

/** 기본 제공 프로필. */
public object CompressionProfiles {
    /** 목록·미리보기용 작은 이미지: 512px, JPEG 60, 100KiB 이하. */
    public val Thumbnail: CompressionProfile = CompressionProfile("thumbnail") {
        maxDimension = 512
        format = ImageFormat.JPEG
        quality = 60
        targetBytes = 100L * 1024
    }

    /** 서버 업로드용: 1920px, JPEG 80, 1.5MiB 이하. */
    public val Upload: CompressionProfile = CompressionProfile("upload") {
        maxDimension = 1920
        format = ImageFormat.JPEG
        quality = 80
        targetBytes = 1536L * 1024
    }

    /** 프로필 사진용: 가운데 정사각 256px, JPEG 85, 100KiB 이하. */
    public val Avatar: CompressionProfile = CompressionProfile("avatar") {
        centerSquare(256)
        format = ImageFormat.JPEG
        quality = 85
        targetBytes = 100L * 1024
    }
}
