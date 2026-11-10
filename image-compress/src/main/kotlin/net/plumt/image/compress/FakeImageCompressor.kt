package net.plumt.image.compress

import java.util.Collections

/**
 * 시험용 [ImageCompressor]. 디코딩·인코딩을 하지 않으므로 앱 단위 테스트가 기기·Robolectric 없이 돈다.
 *
 * - 기본으로 [config] 의 형식·품질을 그대로 적은 1x1 결과를 돌려준다. 결과는 [respondWith] 로 정한다.
 * - [failWith] (또는 `failWith*`) 를 부르면 [compress] 가 그 원인으로 [CompressionResult.Failure] 를 돌려준다. [succeed] 로 되돌린다.
 * - [ImageTarget] 을 함께 넘기면 성공 결과의 바이트를 실제로 쓴다(쓰기 실패는 [ImageIoException] 실패).
 * - 콜백 형태는 부른 스레드에서 **바로** 결과를 주고 이미 끝난 핸들을 돌려준다(시험에 메인 루퍼가 필요 없다).
 * - 받은 입력은 [sources], 부른 횟수는 [calls] 로 확인한다.
 *
 * ```kotlin
 * val compressor = FakeImageCompressor()
 * compressor.failWithIoError("권한 만료")
 * val viewModel = UploadViewModel(compressor)
 * ```
 */
public class FakeImageCompressor(override val config: CompressorConfig) : ImageCompressor {

    /** 기본 설정([CompressorConfig.builder] 의 기본값)의 가짜 압축기. */
    public constructor() : this(CompressorConfig.builder().build())

    private val lock = Any()
    private var data: ByteArray = FAKE_DATA
    private var width: Int = 1
    private var height: Int = 1
    private var failure: ImageCompressionException? = null
    private val received = ArrayList<ImageSource>()

    /** 압축을 부른 횟수(결과·콜백 형태 포함). */
    public val calls: Int get() = synchronized(lock) { received.size }

    /** 받은 입력(부른 순서). */
    public val sources: List<ImageSource> get() = synchronized(lock) { Collections.unmodifiableList(ArrayList(received)) }

    /**
     * 이후 성공 결과를 [data]·[width]x[height] 로 한다. 원본 크기도 같은 값으로 적는다.
     *
     * @throws IllegalArgumentException [data] 가 비었거나 크기가 1 미만일 때
     */
    public fun respondWith(data: ByteArray, width: Int, height: Int) {
        require(data.isNotEmpty()) { "data 가 비었다" }
        require(width > 0 && height > 0) { "크기는 1 이상이어야 한다: ${width}x$height" }
        synchronized(lock) {
            this.data = data.copyOf()
            this.width = width
            this.height = height
        }
    }

    /** 이후 압축이 [error] 로 실패하게 한다. */
    public fun failWith(error: ImageCompressionException) {
        synchronized(lock) { failure = error }
    }

    /** 이후 압축이 [ImageDecodeException] 으로 실패하게 한다. */
    public fun failWithDecodeError(message: String) {
        failWith(ImageDecodeException(message))
    }

    /** 이후 압축이 [ImageIoException] 으로 실패하게 한다. */
    public fun failWithIoError(message: String) {
        failWith(ImageIoException(message))
    }

    /** 이후 압축이 [ImageOutOfMemoryException] 으로 실패하게 한다. */
    public fun failWithOutOfMemory(message: String) {
        failWith(ImageOutOfMemoryException(message))
    }

    /** [failWith] 를 되돌린다. */
    public fun succeed() {
        synchronized(lock) { failure = null }
    }

    override suspend fun compress(source: ImageSource): CompressionResult = respond(source, null)

    override suspend fun compress(source: ImageSource, target: ImageTarget): CompressionResult = respond(source, target)

    override fun compress(source: ImageSource, callback: CompressionCallback): CompressionCallHandle {
        callback.onResult(respond(source, null))
        return CompletedHandle
    }

    override fun compress(source: ImageSource, target: ImageTarget, callback: CompressionCallback): CompressionCallHandle {
        callback.onResult(respond(source, target))
        return CompletedHandle
    }

    override fun toString(): String =
        "FakeImageCompressor(${synchronized(lock) { failure?.let { it::class.java.simpleName } ?: "success" }}, calls=$calls)"

    private fun respond(source: ImageSource, target: ImageTarget?): CompressionResult {
        val image = synchronized(lock) {
            received += source
            failure?.let { return CompressionResult.Failure(it) }
            CompressedImage(
                data = data.copyOf(),
                format = config.format,
                width = width,
                height = height,
                quality = config.quality.takeIf { config.format.supportsQuality },
                sourceWidth = width,
                sourceHeight = height,
                sampleSize = 1,
                attempts = 1,
                preservedMetadata = emptySet(),
                warnings = emptySet(),
            )
        }
        if (target != null) {
            try {
                writeOutput(target, image.data)
            } catch (e: ImageIoException) {
                return CompressionResult.Failure(e)
            }
        }
        return CompressionResult.Success(image)
    }

    private companion object {
        /** 내용 없는 가짜 바이트. 이미지로 디코딩되지 않는다. */
        val FAKE_DATA = byteArrayOf(0x46, 0x41, 0x4B, 0x45)
    }
}
