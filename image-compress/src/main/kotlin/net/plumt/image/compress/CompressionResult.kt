package net.plumt.image.compress

/**
 * [ImageCompressor.compress] 의 결과. 닫힌 집합이다 — `when` 에 `else` 없이 두 경우를 다룬다.
 *
 * 예상되는 실패(디코딩·입출력·메모리·용량 목표)는 던지지 않고 [Failure] 로 돌려준다. 잘못된 설정·인자와
 * 직접 만든 확장이 던진 `IllegalArgumentException` 은 프로그래머 오류라 그대로 던진다. 취소는 `CancellationException` 그대로다.
 */
public sealed interface CompressionResult {

    /**
     * 압축했다.
     *
     * @property image 압축 결과
     */
    public class Success(public val image: CompressedImage) : CompressionResult {
        override fun equals(other: Any?): Boolean = other is Success && other.image == image

        override fun hashCode(): Int = image.hashCode()

        override fun toString(): String = "Success($image)"
    }

    /**
     * 압축하지 못했다. 원인의 종류는 [error] 의 하위 타입으로 가린다(저장소의 ERRORS.md).
     *
     * @property error 실패 원인. 원래 예외는 `error.cause`
     */
    public class Failure(public val error: ImageCompressionException) : CompressionResult {
        override fun equals(other: Any?): Boolean = other is Failure && other.error == error

        override fun hashCode(): Int = error.hashCode()

        override fun toString(): String = "Failure(${error::class.java.simpleName}: ${error.message})"
    }
}

/** 콜백 형태 [ImageCompressor.compress] 의 결과 받기. 메인 스레드에서 한 번 불린다. */
public fun interface CompressionCallback {
    /** 결과를 받는다. 메인 스레드에서 불린다. */
    public fun onResult(result: CompressionResult)
}

/** 콜백 형태 호출의 취소 핸들. 취소하면 콜백을 부르지 않는다. */
public interface CompressionCallHandle {
    /** 아직 결과를 주지 않았고 취소되지도 않았으면 true. */
    public val isActive: Boolean

    /** 진행 중인 압축을 취소한다. 이미 끝났으면 아무것도 하지 않는다. */
    public fun cancel()
}
