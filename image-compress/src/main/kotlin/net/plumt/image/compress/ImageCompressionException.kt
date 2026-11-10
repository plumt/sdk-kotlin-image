package net.plumt.image.compress

/**
 * 압축이 실패한 원인. 던지지 않고 [CompressionResult.Failure.error] 에 담겨 온다. 원래 예외는 [cause] 에 보존한다.
 * 닫힌 집합이다 — 하위 타입으로 가린다. 자세한 발생 조건은 저장소의 ERRORS.md 를 본다.
 *
 * 확장 지점([Encoder]·[CompressionStrategy])은 이 예외를 던져 실패를 알릴 수 있고, SDK 가 받아 결과로 바꾼다.
 */
public sealed class ImageCompressionException(message: String, cause: Throwable?) : Exception(message, cause)

/** 이미지를 읽을 수 없다 — 손상됨, 이 기기가 디코딩하지 못하는 형식, 크기 0, 디코딩 결과 없음. */
public class ImageDecodeException internal constructor(message: String, cause: Throwable?) :
    ImageCompressionException(message, cause) {
    internal constructor(message: String) : this(message, null)
}

/** 인코딩 단계 실패 — 인코더가 false·빈 결과를 냈거나 예외를 던졌거나, 요청한 메타데이터를 쓰지 못했다. */
public class ImageEncodeException(message: String, cause: Throwable?) : ImageCompressionException(message, cause) {
    /** 원인 예외가 없는 실패. 직접 만든 [Encoder]·[CompressionStrategy] 가 실패를 알릴 때 쓴다. */
    public constructor(message: String) : this(message, null)
}

/** 입력을 열거나 출력을 쓰는 입출력 실패(파일 없음, URI 열기 실패, 권한 없음·만료, 스트림 한도 초과 포함). */
public class ImageIoException internal constructor(message: String, cause: Throwable?) :
    ImageCompressionException(message, cause) {
    internal constructor(message: String) : this(message, null)
}

/** 디코딩·변환 중 메모리가 부족했다. `maxDimension`·`maxPixels` 를 줄여 다시 시도한다. */
public class ImageOutOfMemoryException internal constructor(message: String, cause: Throwable?) :
    ImageCompressionException(message, cause) {
    internal constructor(message: String) : this(message, null)
}

/**
 * 용량 목표를 끝내 맞추지 못했다 — 최소 품질·최소 크기까지 내려도 [targetBytes] 를 넘는다.
 * 목표보다 큰 결과를 조용히 돌려주지 않고 이 실패로 알린다.
 *
 * @property targetBytes 요청한 상한(바이트)
 * @property smallestBytes 시도한 것 중 가장 작았던 결과의 크기(바이트)
 * @property width 그 결과의 폭
 * @property height 그 결과의 높이
 */
public class TargetSizeNotReachedException(
    public val targetBytes: Long,
    public val smallestBytes: Long,
    public val width: Int,
    public val height: Int,
) : ImageCompressionException(
    "용량 목표 ${targetBytes}B 를 맞추지 못했다 — 가장 작은 시도는 ${width}x$height, ${smallestBytes}B",
    null,
)
