package net.plumt.image.compress

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 이미지 압축기. 설정은 만들 때 고정되며 상태가 없어 앱에 하나(또는 용도별 하나)만 두고 여러 스레드가 공유해도 된다.
 * 인스턴스는 앱이 들고 DI 로 넘긴다. 단위 테스트는 [FakeImageCompressor] 를 쓴다.
 *
 * 같은 기능을 두 형태로 준다: `suspend` 함수와 콜백(메인 스레드에서 결과를 받고 취소 핸들을 돌려준다). 둘은 같은 내부 로직을 쓴다.
 * 예상되는 실패(디코딩·입출력·메모리·용량 목표)는 [CompressionResult.Failure] 로 돌려주고 던지지 않는다.
 *
 * ```kotlin
 * val compressor = ImageCompressor.create(
 *     CompressorConfig.builder().maxDimension(1920).format(ImageFormat.WEBP).quality(80).targetBytes(500_000).build(),
 * )
 * when (val r = compressor.compress(ImageSource.file(photo), ImageTarget.file(File(cacheDir, "upload.webp")))) {
 *     is CompressionResult.Success -> upload(r.image)
 *     is CompressionResult.Failure -> showError(r.error)
 * }
 * ```
 *
 * 어떤 설정에서도 원본 파일을 그대로 돌려주지 않는다 — 이미 작아도 다시 인코딩해 메타데이터(위치 등)를 제거한다.
 */
public interface ImageCompressor {
    /** 이 압축기의 확정된 설정. */
    public val config: CompressorConfig

    /**
     * [source] 를 압축한다. [CompressorConfig.dispatcher] 에서 실행되며 취소에 응한다(취소는 `CancellationException` 그대로).
     * 메인 스레드에서 불러도 된다.
     *
     * 던지는 것은 프로그래머 오류뿐이다: 직접 만든 [Resizer]·[CompressionStrategy]·[Encoder]·[ImageSource] 가
     * 잘못된 값을 냈거나 `IllegalArgumentException` 을 던진 경우. 플랫폼 내부의 IAE 는 [ImageEncodeException] 실패로 감싼다.
     */
    public suspend fun compress(source: ImageSource): CompressionResult

    /** [source] 를 압축해 [target] 에 쓴다. 쓰기 실패는 [ImageIoException] 실패다. 그 밖은 [compress] (source) 와 같다. */
    public suspend fun compress(source: ImageSource, target: ImageTarget): CompressionResult

    /**
     * [compress] 의 콜백 형태. 압축은 [CompressorConfig.dispatcher] 에서 돌고 결과는 메인 스레드에서 [callback] 으로
     * 한 번 온다. 돌려받은 핸들로 취소하면 콜백을 부르지 않는다. 화면이 사라질 때 취소한다.
     * 프로그래머 오류(IAE)는 메인 스레드의 미처리 예외가 된다.
     */
    public fun compress(source: ImageSource, callback: CompressionCallback): CompressionCallHandle =
        launchCallback({ compress(source) }, callback)

    /** [compress] (source, target) 의 콜백 형태. 결과·취소는 [compress] (source, callback) 과 같다. */
    public fun compress(source: ImageSource, target: ImageTarget, callback: CompressionCallback): CompressionCallHandle =
        launchCallback({ compress(source, target) }, callback)

    /** 압축기를 만든다. */
    public companion object {
        /** 기본 설정([CompressorConfig.builder] 의 기본값)의 압축기. */
        @JvmStatic
        public fun create(): ImageCompressor = create(CompressorConfig.builder().build())

        /** [config] 로 압축기를 만든다. */
        @JvmStatic
        public fun create(config: CompressorConfig): ImageCompressor = DefaultImageCompressor(config)
    }
}

internal class DefaultImageCompressor(override val config: CompressorConfig) : ImageCompressor {
    override suspend fun compress(source: ImageSource): CompressionResult = run(source, null)

    override suspend fun compress(source: ImageSource, target: ImageTarget): CompressionResult = run(source, target)

    private suspend fun run(source: ImageSource, target: ImageTarget?): CompressionResult =
        withContext(config.dispatcher) {
            val job = coroutineContext.job
            try {
                val image = CompressionPipeline(config).run(source) { job.ensureActive() }
                if (target != null) {
                    job.ensureActive()
                    writeOutput(target, image.data)
                }
                CompressionResult.Success(image)
            } catch (e: ImageCompressionException) {
                CompressionResult.Failure(e)
            }
        }

    override fun toString(): String = "ImageCompressor($config)"
}

/**
 * 확정된 압축 설정(불변). [builder] 로 만들고 [toBuilder] 로 고친 사본을 만든다.
 *
 * 기본값: [ImageFormat.JPEG], 품질 80, 긴 변 1920px 안에 맞춤(확대 없음), 픽셀 상한 4096x4096, 한 번 인코딩,
 * [Encoder.Default], `Dispatchers.IO`, 메타데이터 전부 제거, 배경 흰색.
 */
public class CompressorConfig internal constructor(private val source: Builder) {
    /** 출력 형식. */
    public val format: ImageFormat = source.format

    /** 품질 1..100. 용량 목표가 있으면 탐색의 상한이다. */
    public val quality: Int = source.quality

    /** 크기 규칙. */
    public val resizer: Resizer = source.resizer ?: FitResizer(source.maxWidth, source.maxHeight)

    /** 결과 픽셀 수 상한. */
    public val maxPixels: Long = source.maxPixels

    /** 인코딩 전략. */
    public val strategy: CompressionStrategy = source.resolveStrategy()

    /** 인코더. */
    public val encoder: Encoder = source.encoder

    /** 작업 디스패처. */
    public val dispatcher: CoroutineDispatcher = source.dispatcher

    /** 결과에 남길 메타데이터 묶음(빈 집합 = 전부 제거). */
    public val preserveMetadata: Set<MetadataGroup> = source.preserveMetadata

    /** 투명을 못 담는 형식에서 투명 픽셀 아래에 깔 색(ARGB). */
    public val backgroundColor: Int = source.backgroundColor

    /** 이 값에서 시작하는 빌더. */
    public fun toBuilder(): Builder = source.copy()

    override fun toString(): String =
        "CompressorConfig(format=$format, quality=$quality, maxPixels=$maxPixels, resizer=$resizer, " +
            "strategy=$strategy, preserveMetadata=$preserveMetadata)"

    /** 빌더를 만든다. */
    public companion object {
        /** 기본값에서 시작하는 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        internal fun from(builder: Builder): CompressorConfig = CompressorConfig(builder.copy())
    }

    /**
     * [CompressorConfig] 빌더. 값은 [build] 에서 검사하며, 틀리면 `IllegalArgumentException`(받은 값과 허용 범위 포함)이다.
     * 같은 항목을 두 번 정하면 나중 값이 쓰인다.
     */
    public class Builder internal constructor() {
        internal var format: ImageFormat = ImageFormat.JPEG
        internal var quality: Int = DEFAULT_QUALITY
        internal var maxWidth: Int = DEFAULT_MAX_DIMENSION
        internal var maxHeight: Int = DEFAULT_MAX_DIMENSION
        internal var resizer: Resizer? = null
        internal var maxPixels: Long = DEFAULT_MAX_PIXELS
        internal var targetBytes: Long? = null
        internal var minQuality: Int = TargetSizeStrategy.DEFAULT_MIN_QUALITY
        internal var dimensionStep: Float = TargetSizeStrategy.DEFAULT_DIMENSION_STEP
        internal var minDimension: Int = TargetSizeStrategy.DEFAULT_MIN_DIMENSION
        internal var strategy: CompressionStrategy? = null
        internal var encoder: Encoder = Encoder.Default
        internal var dispatcher: CoroutineDispatcher = Dispatchers.IO
        internal var preserveMetadata: Set<MetadataGroup> = emptySet()
        internal var backgroundColor: Int = DEFAULT_BACKGROUND

        /** 출력 형식. 기본 [ImageFormat.JPEG]. */
        public fun format(format: ImageFormat): Builder = apply { this.format = format }

        /** 품질 1..100(기본 80). 용량 목표가 있으면 탐색의 상한이다. 품질이 없는 형식에서는 무시된다. */
        public fun quality(quality: Int): Builder = apply { this.quality = quality }

        /** 결과 폭의 상한(px, 기본 1920). 종횡비를 유지하고 확대하지 않는다. [resizer] 를 정하면 쓰이지 않는다. */
        public fun maxWidth(px: Int): Builder = apply { maxWidth = px }

        /** 결과 높이의 상한(px, 기본 1920). */
        public fun maxHeight(px: Int): Builder = apply { maxHeight = px }

        /** [maxWidth]·[maxHeight] 를 함께 정한다. */
        public fun maxDimension(px: Int): Builder = apply {
            maxWidth = px
            maxHeight = px
        }

        /** 직접 만든 크기 규칙. 정하면 [maxWidth]·[maxHeight] 대신 쓴다. `null` 이면 그 둘로 돌아간다. */
        public fun resizer(resizer: Resizer?): Builder = apply { this.resizer = resizer }

        /** 짧은 변 기준 가운데 정사각형으로 잘라 [size]x[size] 로 만든다. 짧은 변이 [size] 보다 작으면 키우지 않는다. */
        public fun centerSquare(size: Int): Builder = centerSquare(size, false)

        /** [centerSquare] 와 같되 [allowUpscale] 이 true 면 작은 원본도 [size] 로 키운다. */
        public fun centerSquare(size: Int, allowUpscale: Boolean): Builder = resizer(CenterSquareResizer(size, allowUpscale))

        /** 결과 픽셀 수 상한(기본 16,777,216 = 4096x4096). [resizer] 가 이보다 크게 계획해도 비율을 유지해 줄인다. */
        public fun maxPixels(pixels: Long): Builder = apply { maxPixels = pixels }

        /**
         * 용량 목표(바이트). 정하면 [TargetSizeStrategy] 로 맞추고, 못 맞추면 [TargetSizeNotReachedException] 실패다.
         * [strategy] 와는 둘 중 **나중에 정한 것**이 쓰인다 — null 이 아닌 값을 넣으면 [strategy] 를 비운다. `null` 은 목표를 지운다.
         */
        public fun targetBytes(bytes: Long?): Builder = apply {
            targetBytes = bytes
            if (bytes != null) strategy = null
        }

        /** 용량 목표 탐색의 최소 품질(기본 30). [targetBytes] 가 있을 때만 쓰인다. */
        public fun minQuality(quality: Int): Builder = apply { minQuality = quality }

        /** 최소 품질로도 넘을 때 긴 변을 줄이는 배율(0 초과 1 미만, 기본 0.85). */
        public fun dimensionStep(step: Float): Builder = apply { dimensionStep = step }

        /** 용량 목표를 위해 줄일 수 있는 긴 변의 하한(px, 기본 64). */
        public fun minDimension(px: Int): Builder = apply { minDimension = px }

        /**
         * 직접 만든 인코딩 전략. 기본은 [targetBytes] 유무에 따라 한 번 인코딩 또는 용량 맞춤.
         * null 이 아닌 값을 넣으면 [targetBytes] 를 비운다 — 용량 목표가 있는 프로필 위에도 그대로 덮어쓸 수 있다.
         */
        public fun strategy(strategy: CompressionStrategy?): Builder = apply {
            this.strategy = strategy
            if (strategy != null) targetBytes = null
        }

        /** 직접 만든 인코더. 기본 [Encoder.Default]. */
        public fun encoder(encoder: Encoder): Builder = apply { this.encoder = encoder }

        /**
         * 작업 디스패처. 기본 `Dispatchers.IO`. 한 장마다 디코딩한 Bitmap(수십 MB 일 수 있다)을 잡으므로 여러 장을 동시에 압축하면
         * 메모리가 장 수만큼 쌓인다. `Dispatchers.IO.limitedParallelism(2)` 처럼 동시 실행 수를 제한한 디스패처를 권한다.
         */
        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        /**
         * 결과에 남길 메타데이터. **기본은 빈 집합 = 전부 제거**다.
         * [MetadataGroup.LOCATION] 을 넣는 것은 위치 노출을 감수하겠다는 명시적 선택이다.
         * 용량 목표와 함께 쓰면 붙인 뒤의 크기로 맞춘다.
         */
        public fun preserveMetadata(groups: Set<MetadataGroup>): Builder = apply { preserveMetadata = LinkedHashSet(groups) }

        /**
         * JPEG 처럼 투명을 못 담는 형식에서 투명 픽셀 아래에 깔 색(ARGB, 기본 흰색 `0xFFFFFFFF`).
         * 반투명 색이면 흰색 위에 먼저 합성한다(투명 영역이 검게 나오지 않는다).
         */
        public fun backgroundColor(argb: Int): Builder = apply { backgroundColor = argb }

        /** [profile] 의 설정을 이 빌더에 적용한다. 이후 정한 값이 우선한다. */
        public fun profile(profile: CompressionProfile): Builder = apply { profile.applyTo(this) }

        /** 검사하고 불변 설정을 만든다. */
        public fun build(): CompressorConfig {
            require(quality in 1..100) { "quality 는 1..100 이어야 한다: $quality" }
            require(maxWidth > 0 && maxHeight > 0) { "maxWidth/maxHeight 는 1 이상이어야 한다: ${maxWidth}x$maxHeight" }
            require(maxPixels > 0) { "maxPixels 는 1 이상이어야 한다: $maxPixels" }
            return from(this)
        }

        internal fun resolveStrategy(): CompressionStrategy {
            val target = targetBytes
            val custom = strategy
            return when {
                custom != null -> custom
                target != null -> {
                    require(minQuality <= quality) { "minQuality($minQuality) 는 quality($quality) 이하여야 한다" }
                    TargetSizeStrategy(target, minQuality, dimensionStep, minDimension)
                }
                else -> SinglePassStrategy
            }
        }

        internal fun copy(): Builder = Builder().also { b ->
            b.format = format
            b.quality = quality
            b.maxWidth = maxWidth
            b.maxHeight = maxHeight
            b.resizer = resizer
            b.maxPixels = maxPixels
            b.targetBytes = targetBytes
            b.minQuality = minQuality
            b.dimensionStep = dimensionStep
            b.minDimension = minDimension
            b.strategy = strategy
            b.encoder = encoder
            b.dispatcher = dispatcher
            b.preserveMetadata = preserveMetadata
            b.backgroundColor = backgroundColor
        }

        private companion object {
            const val DEFAULT_QUALITY = 80
            const val DEFAULT_MAX_DIMENSION = 1920
            const val DEFAULT_MAX_PIXELS = 4096L * 4096L
            const val DEFAULT_BACKGROUND = 0xFFFFFFFF.toInt()
        }
    }
}

/**
 * 이름 붙인 설정 묶음(프리셋). 직접 정의해 `CompressorConfig.builder().profile(profile)` 로 쓴다.
 *
 * ```kotlin
 * val Banner = CompressionProfile("banner") { maxWidth(1200).maxHeight(400).format(ImageFormat.WEBP) }
 * ```
 *
 * @throws IllegalArgumentException [name] 이 비었을 때
 */
public class CompressionProfile(
    /** 프로필 이름. */
    public val name: String,
    private val configure: CompressorConfig.Builder.() -> Unit,
) {
    init {
        require(name.isNotBlank()) { "프로필 이름이 비어 있다" }
    }

    internal fun applyTo(builder: CompressorConfig.Builder) = builder.configure()

    override fun toString(): String = "CompressionProfile($name)"
}

/** 기본 제공 프로필. */
public object CompressionProfiles {
    /** 목록·미리보기용 작은 이미지: 512px, JPEG 60, 100KiB 이하. */
    @JvmField
    public val Thumbnail: CompressionProfile = CompressionProfile("thumbnail") {
        maxDimension(512).format(ImageFormat.JPEG).quality(60).targetBytes(100L * 1024)
    }

    /** 서버 업로드용: 1920px, JPEG 80, 1.5MiB 이하. */
    @JvmField
    public val Upload: CompressionProfile = CompressionProfile("upload") {
        maxDimension(1920).format(ImageFormat.JPEG).quality(80).targetBytes(1536L * 1024)
    }

    /** 프로필 사진용: 가운데 정사각 256px, JPEG 85, 100KiB 이하. */
    @JvmField
    public val Avatar: CompressionProfile = CompressionProfile("avatar") {
        centerSquare(256).format(ImageFormat.JPEG).quality(85).targetBytes(100L * 1024)
    }
}
