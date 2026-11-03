package net.plumt.image.compress

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 원본(방향 적용 후) 크기에서 "어느 영역을 잘라 어떤 크기로 만들지" 를 정하는 지점.
 * 순수 함수라 Bitmap 없이 시험할 수 있다. 직접 구현해 종횡비 맞춤 같은 규칙을 끼울 수 있다.
 */
public fun interface Resizer {
    /**
     * @param sourceWidth 방향을 적용한 원본 폭(1 이상)
     * @param sourceHeight 방향을 적용한 원본 높이(1 이상)
     */
    public fun plan(sourceWidth: Int, sourceHeight: Int): ResizePlan
}

/**
 * [Resizer] 의 결과 — 원본의 [cropWidth]x[cropHeight] 영역([cropLeft], [cropTop] 에서 시작)을 [width]x[height] 로 만든다.
 * 영역이 원본을 벗어나는지는 압축 시점에 원본 크기와 대조해 [IllegalArgumentException] 으로 알린다.
 */
public class ResizePlan(
    public val cropLeft: Int,
    public val cropTop: Int,
    public val cropWidth: Int,
    public val cropHeight: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        require(cropLeft >= 0 && cropTop >= 0) { "자르기 시작점은 0 이상이어야 한다: ($cropLeft, $cropTop)" }
        require(cropWidth > 0 && cropHeight > 0) { "자르기 크기는 1 이상이어야 한다: ${cropWidth}x$cropHeight" }
        require(width > 0 && height > 0) { "출력 크기는 1 이상이어야 한다: ${width}x$height" }
    }

    override fun toString(): String =
        "ResizePlan(crop=($cropLeft,$cropTop ${cropWidth}x$cropHeight) -> ${width}x$height)"

    public companion object {
        /** 자르지 않고 전체를 [width]x[height] 로 만드는 계획. */
        public fun full(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): ResizePlan =
            ResizePlan(0, 0, sourceWidth, sourceHeight, width, height)
    }
}

/**
 * 종횡비를 유지하며 [maxWidth]x[maxHeight] 안에 들어오게 줄인다. 이미 안에 있으면 그대로 두고(확대하지 않는다),
 * [allowUpscale] 이 true 면 더 작은 원본도 한도까지 키운다.
 */
public class FitResizer(
    public val maxWidth: Int,
    public val maxHeight: Int,
    public val allowUpscale: Boolean = false,
) : Resizer {
    init {
        require(maxWidth > 0 && maxHeight > 0) { "최대 크기는 1 이상이어야 한다: ${maxWidth}x$maxHeight" }
    }

    override fun plan(sourceWidth: Int, sourceHeight: Int): ResizePlan {
        val ratio = min(maxWidth.toDouble() / sourceWidth, maxHeight.toDouble() / sourceHeight)
        if (ratio >= 1.0 && !allowUpscale) return ResizePlan.full(sourceWidth, sourceHeight, sourceWidth, sourceHeight)
        val w = (sourceWidth * ratio).roundToInt().coerceIn(1, maxWidth)
        val h = (sourceHeight * ratio).roundToInt().coerceIn(1, maxHeight)
        return ResizePlan.full(sourceWidth, sourceHeight, w, h)
    }
}

/**
 * 짧은 변 기준 가운데 정사각형을 잘라 [size]x[size] 로 맞춘다. 원본의 짧은 변이 [size] 보다 작으면
 * [allowUpscale] 이 false(기본)인 한 그 변 길이의 정사각형이 되고, true 면 [size] 로 키운다.
 */
public class CenterSquareResizer(
    public val size: Int,
    public val allowUpscale: Boolean = false,
) : Resizer {
    init {
        require(size > 0) { "정사각 크기는 1 이상이어야 한다: $size" }
    }

    override fun plan(sourceWidth: Int, sourceHeight: Int): ResizePlan {
        val edge = min(sourceWidth, sourceHeight)
        val out = if (allowUpscale) size else min(size, edge)
        return ResizePlan((sourceWidth - edge) / 2, (sourceHeight - edge) / 2, edge, edge, out, out)
    }
}
