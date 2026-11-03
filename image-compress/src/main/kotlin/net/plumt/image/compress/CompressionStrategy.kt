package net.plumt.image.compress

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 준비된 Bitmap(디코딩·방향·자르기·크기 맞춤이 끝난 것)을 어떤 순서로 몇 번 인코딩해 최종 바이트를 정할지 정하는 지점.
 * 기본 제공: [SinglePassStrategy], [TargetSizeStrategy]. 직접 구현해 예컨대 "SSIM 목표" 같은 정책을 끼울 수 있다.
 *
 * 블로킹 함수다 — [ImageCompressor] 가 자기 디스패처에서 부른다. 오래 도는 구현은 반복마다
 * [EncodeContext.ensureActive] 를 불러 취소에 응해야 한다.
 */
public fun interface CompressionStrategy {
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
 * 3. [minQuality] 로도 넘으면 긴 변을 [dimensionStep] 배씩 줄여 1~2 를 반복한다. 긴 변이 [minDimension] 에 닿은 시도까지 넘으면
 *    [TargetSizeNotReachedException] 이다.
 *
 * 품질에 따라 용량이 (대체로) 단조 증가한다고 가정한다. 드물게 어긋나는 이미지에서도 반환값은 언제나 목표 이하이며
 * 다만 가장 높은 품질이 아닐 수 있다. PNG·무손실 WebP 는 품질이 없어 3 만 쓴다.
 *
 * @throws IllegalArgumentException 인자가 허용 범위를 벗어났을 때
 */
public class TargetSizeStrategy(
    public val targetBytes: Long,
    public val minQuality: Int = DEFAULT_MIN_QUALITY,
    public val dimensionStep: Float = DEFAULT_DIMENSION_STEP,
    public val minDimension: Int = DEFAULT_MIN_DIMENSION,
) : CompressionStrategy {
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

        var step = 0
        var current = base
        try {
            while (true) {
                if (!searchable) {
                    val bytes = encode(current, 100)
                    if (bytes.size <= targetBytes) return result(bytes, current, 100)
                } else {
                    val top = encode(current, maxQuality)
                    if (top.size <= targetBytes) return result(top, current, maxQuality)
                    if (floorQuality < maxQuality) {
                        val bottom = encode(current, floorQuality)
                        if (bottom.size <= targetBytes) {
                            var lo = floorQuality
                            var loBytes = bottom
                            var hi = maxQuality // hi 는 항상 목표 초과
                            while (hi - lo > 1) {
                                val mid = (lo + hi) ushr 1
                                val bytes = encode(current, mid)
                                if (bytes.size <= targetBytes) {
                                    lo = mid
                                    loBytes = bytes
                                } else {
                                    hi = mid
                                }
                            }
                            return result(loBytes, current, lo)
                        }
                    }
                }

                val longEdge = max(base.width, base.height)
                val currentLong = max(current.width, current.height)
                if (currentLong <= minDimension) {
                    throw TargetSizeNotReachedException(targetBytes, smallest, smallestW, smallestH)
                }
                step++
                val nextLong = max(minDimension, (longEdge * Math.pow(dimensionStep.toDouble(), step.toDouble())).roundToInt())
                    .coerceAtMost(currentLong - 1)
                val ratio = nextLong.toDouble() / longEdge
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
