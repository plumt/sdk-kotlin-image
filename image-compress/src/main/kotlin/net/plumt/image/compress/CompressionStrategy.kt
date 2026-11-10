package net.plumt.image.compress

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 준비된 Bitmap(디코딩·방향·자르기·크기 맞춤이 끝난 것)을 어떤 순서로 몇 번 인코딩해 최종 바이트를 정할지 정하는 지점.
 * 기본 제공: [SinglePassStrategy], [TargetSizeStrategy]. 직접 구현해 예컨대 "SSIM 목표" 같은 정책을 끼울 수 있다.
 *
 * 블로킹 함수다 — [ImageCompressor] 가 자기 디스패처에서 부른다. 오래 도는 구현은 반복마다
 * [EncodeContext.ensureActive] 를 불러 취소에 응해야 한다. 실패는 [ImageCompressionException] 을 던져 알리면
 * SDK 가 [CompressionResult.Failure] 로 바꾼다. `IllegalArgumentException` 은 프로그래머 오류로 보고 그대로 던진다.
 */
public fun interface CompressionStrategy {
    /** [context] 의 도구로 인코딩해 최종 결과를 정한다. */
    public fun compress(context: EncodeContext): EncodedImage
}

/** [CompressionStrategy] 가 쓰는 도구 상자. 만든 Bitmap 의 회수는 SDK 가 책임진다(직접 recycle 하지 않는다). */
public interface EncodeContext {
    /** 준비가 끝난 기준 Bitmap. 읽기 전용이며 recycle 하면 안 된다. */
    public val bitmap: Bitmap

    /** 출력 형식. */
    public val format: ImageFormat

    /** 설정한 품질(1..100) — 품질을 탐색하는 전략에서는 상한으로 쓴다. */
    public val quality: Int

    /**
     * [bitmap] 을 [quality] 로 인코딩한다. 보존하기로 한 메타데이터가 있으면 **이미 붙은** 바이트를 돌려주므로
     * 반환 크기가 곧 최종 파일 크기다.
     */
    public fun encode(bitmap: Bitmap, quality: Int): ByteArray

    /** 기준 [bitmap] 을 [width]x[height] 로 줄인 사본. 다 쓰면 [release] 로 돌려준다. 안 돌려줘도 마지막에 SDK 가 회수한다. */
    public fun scaledTo(width: Int, height: Int): Bitmap

    /** [scaledTo] 가 만든 Bitmap 을 즉시 회수한다. 기준 [bitmap] 은 무시한다. */
    public fun release(bitmap: Bitmap)

    /** 코루틴이 취소됐으면 `CancellationException` 을 던진다. */
    public fun ensureActive()
}

/**
 * [CompressionStrategy] 의 결과.
 *
 * @property quality 사용한 품질. 품질이 없는 형식([ImageFormat.supportsQuality] false)이면 null
 * @property attempts 인코딩한 횟수
 */
public class EncodedImage(
    public val data: ByteArray,
    public val width: Int,
    public val height: Int,
    public val quality: Int?,
    public val attempts: Int,
) {
    init {
        require(data.isNotEmpty()) { "인코딩 결과가 비어 있다" }
        require(width > 0 && height > 0) { "크기는 1 이상이어야 한다: ${width}x$height" }
        require(quality == null || quality in 1..100) { "quality 는 1..100 또는 null 이어야 한다: $quality" }
        require(attempts >= 1) { "attempts 는 1 이상이어야 한다: $attempts" }
    }
}

/** 설정한 품질로 한 번만 인코딩한다. 용량은 보장하지 않는다. */
public object SinglePassStrategy : CompressionStrategy {
    override fun compress(context: EncodeContext): EncodedImage {
        context.ensureActive()
        val data = context.encode(context.bitmap, context.quality)
        return EncodedImage(
            data = data,
            width = context.bitmap.width,
            height = context.bitmap.height,
            quality = context.quality.takeIf { context.format.supportsQuality },
            attempts = 1,
        )
    }
}

/**
 * 결과가 [targetBytes] 이하가 되도록 맞춘다. 결과는 같은 입력이면 항상 같다.
 *
 * 1. 설정 품질로 인코딩 — 들어가면 끝. 품질이 곧 상한이다.
 * 2. [minQuality] 로 인코딩 — 들어가면 그 사이를 **이분 탐색**해 목표 안에서 가장 높은 품질을 찾는다(약 `log2(범위)` 회).
 * 3. [minQuality] 로도 넘으면 긴 변을 줄인다. 한 단계에 **적어도** [dimensionStep] 배로 줄이고, 용량이 픽셀 수에 비례한다고
 *    볼 때 목표에 들어갈 크기가 그보다 작으면 그 크기로 바로 건너뛴다. 줄인 크기에서는 [minQuality] 부터 인코딩해
 *    들어가면 1~2 처럼 품질을 다시 최대화한다. 긴 변이 [minDimension] 에 닿은 시도까지 넘으면 [TargetSizeNotReachedException] 이다.
 *
 * 품질에 따라 용량이 (대체로) 단조 증가한다고 가정한다. 드물게 어긋나는 이미지에서도 반환값은 언제나 목표 이하이며
 * 다만 가장 높은 품질이 아닐 수 있다. PNG·무손실 WebP 는 품질이 없어 3 만 쓴다.
 *
 * @throws IllegalArgumentException 인자가 허용 범위를 벗어났을 때
 */
public class TargetSizeStrategy(
    public val targetBytes: Long,
    public val minQuality: Int,
    public val dimensionStep: Float,
    public val minDimension: Int,
) : CompressionStrategy {
    /** 나머지를 기본값([DEFAULT_MIN_QUALITY]·[DEFAULT_DIMENSION_STEP]·[DEFAULT_MIN_DIMENSION])으로 둔 전략. */
    public constructor(targetBytes: Long) : this(targetBytes, DEFAULT_MIN_QUALITY, DEFAULT_DIMENSION_STEP, DEFAULT_MIN_DIMENSION)

    init {
        require(targetBytes > 0) { "targetBytes 는 1 이상이어야 한다: $targetBytes" }
        require(minQuality in 1..100) { "minQuality 는 1..100 이어야 한다: $minQuality" }
        require(dimensionStep > 0f && dimensionStep < 1f) { "dimensionStep 은 0 초과 1 미만이어야 한다: $dimensionStep" }
        require(minDimension >= 1) { "minDimension 은 1 이상이어야 한다: $minDimension" }
    }

    override fun compress(context: EncodeContext): EncodedImage {
        val base = context.bitmap
        val searchable = context.format.supportsQuality
        val maxQuality = context.quality
        val floorQuality = minOf(minQuality, maxQuality)
        var attempts = 0
        var smallest = Long.MAX_VALUE
        var smallestW = base.width
        var smallestH = base.height

        fun encode(bitmap: Bitmap, quality: Int): ByteArray {
            context.ensureActive()
            attempts++
            val bytes = context.encode(bitmap, quality)
            if (bytes.size < smallest) {
                smallest = bytes.size.toLong()
                smallestW = bitmap.width
                smallestH = bitmap.height
            }
            return bytes
        }

        fun result(bytes: ByteArray, bitmap: Bitmap, quality: Int) = EncodedImage(
            data = bytes,
            width = bitmap.width,
            height = bitmap.height,
            quality = quality.takeIf { searchable },
            attempts = attempts,
        )

        /** [lo](목표 이하)와 [hi](목표 초과) 사이에서 목표 안의 가장 높은 품질을 찾는다. */
        fun search(bitmap: Bitmap, lo0: Int, loBytes0: ByteArray, hi0: Int): EncodedImage {
            var lo = lo0
            var loBytes = loBytes0
            var hi = hi0
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                val bytes = encode(bitmap, mid)
                if (bytes.size <= targetBytes) {
                    lo = mid
                    loBytes = bytes
                } else {
                    hi = mid
                }
            }
            return result(loBytes, bitmap, lo)
        }

        var step = 0
        var current = base
        try {
            while (true) {
                // 이 크기에서 가장 작게 나온 크기(바이트) — 다음 크기를 추정하는 데 쓴다
                val floorBytes: Int
                if (!searchable) {
                    val bytes = encode(current, 100)
                    if (bytes.size <= targetBytes) return result(bytes, current, 100)
                    floorBytes = bytes.size
                } else if (step == 0) {
                    val top = encode(current, maxQuality)
                    if (top.size <= targetBytes) return result(top, current, maxQuality)
                    if (floorQuality < maxQuality) {
                        val bottom = encode(current, floorQuality)
                        if (bottom.size <= targetBytes) return search(current, floorQuality, bottom, maxQuality)
                        floorBytes = bottom.size
                    } else {
                        floorBytes = top.size
                    }
                } else {
                    // 바로 전 크기에서 최소 품질로도 넘었다 — 이번에도 최소 품질부터 보면 실패한 크기마다 인코딩이 한 번으로 준다
                    val bottom = encode(current, floorQuality)
                    if (bottom.size <= targetBytes) {
                        if (floorQuality == maxQuality) return result(bottom, current, floorQuality)
                        val top = encode(current, maxQuality)
                        if (top.size <= targetBytes) return result(top, current, maxQuality)
                        return search(current, floorQuality, bottom, maxQuality)
                    }
                    floorBytes = bottom.size
                }

                val currentLong = max(current.width, current.height)
                if (currentLong <= minDimension) {
                    throw TargetSizeNotReachedException(targetBytes, smallest, smallestW, smallestH)
                }
                step++
                // 용량이 대략 픽셀 수에 비례한다고 보고 들어갈 긴 변을 추정한다. dimensionStep 보다 덜 줄이지는 않는다
                val byStep = currentLong * dimensionStep.toDouble()
                val byEstimate = currentLong * sqrt(targetBytes.toDouble() / floorBytes)
                val nextLong = max(minDimension, floor(min(byStep, byEstimate)).toInt()).coerceAtMost(currentLong - 1)
                val ratio = nextLong.toDouble() / max(base.width, base.height)
                val w = max(1, (base.width * ratio).roundToInt())
                val h = max(1, (base.height * ratio).roundToInt())
                if (current !== base) context.release(current)
                current = context.scaledTo(w, h)
            }
        } finally {
            if (current !== base) context.release(current)
        }
    }

    public companion object {
        /** 기본 최소 품질. */
        public const val DEFAULT_MIN_QUALITY: Int = 30

        /** 기본 축소 배율(긴 변을 한 단계에 85% 로). */
        public const val DEFAULT_DIMENSION_STEP: Float = 0.85f

        /** 기본 최소 긴 변(px). */
        public const val DEFAULT_MIN_DIMENSION: Int = 64
    }
}
