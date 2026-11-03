package net.plumt.image.compress

/**
 * 압축 중 실패한 모든 경우의 상위 예외. 원인은 [cause] 에 보존한다.
 *
 * 잘못된 설정·인자는 이 예외가 아니라 [IllegalArgumentException] 이다. 코루틴 취소는 [kotlinx.coroutines.CancellationException]
 * 그대로 전파하며 이 예외로 감싸지 않는다. 자세한 발생 조건은 저장소의 ERRORS.md 를 본다.
 */
public sealed class ImageCompressionException(message: String, cause: Throwable?) : Exception(message, cause)

/** 이미지를 읽을 수 없다 — 손상됨, 이 기기가 디코딩하지 못하는 형식, 크기 0, 디코딩 결과 없음. */
public class ImageDecodeException internal constructor(message: String, cause: Throwable? = null) :
    ImageCompressionException(message, cause)

/** 인코딩 단계 실패 — 인코더가 false·빈 결과를 냈거나 예외를 던졌거나, 요청한 메타데이터를 쓰지 못했다. */
public class ImageEncodeException public constructor(message: String, cause: Throwable? = null) :
    ImageCompressionException(message, cause)

/** 입력을 열거나 출력을 쓰는 입출력 실패(파일 없음, URI 열기 실패, 스트림 한도 초과 포함). */
public class ImageIoException internal constructor(message: String, cause: Throwable? = null) :
    ImageCompressionException(message, cause)

/** 디코딩·변환 중 메모리가 부족했다. `maxDimension`·`maxPixels` 를 줄여 다시 시도한다. */
public class ImageOutOfMemoryException internal constructor(message: String, cause: Throwable? = null) :
    ImageCompressionException(message, cause)

/**
 * 용량 목표를 끝내 맞추지 못했다 — 최소 품질·최소 크기까지 내려도 [targetBytes] 를 넘는다.
 * 목표보다 큰 결과를 조용히 돌려주지 않고 이 예외로 알린다.
 *
 * @property targetBytes 요청한 상한(바이트)
 * @property smallestBytes 시도한 것 중 가장 작았던 결과의 크기(바이트)
 * @property width 그 결과의 폭
 * @property height 그 결과의 높이
 */
public class TargetSizeNotReachedException public constructor(
    public val targetBytes: Long,
    public val smallestBytes: Long,
    public val width: Int,
    public val height: Int,
) : ImageCompressionException(
    "용량 목표 ${targetBytes}B 를 맞추지 못했다 — 가장 작은 시도는 ${width}x$height, ${smallestBytes}B",
    null,
)
