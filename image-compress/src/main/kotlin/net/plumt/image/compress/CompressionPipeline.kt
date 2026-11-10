package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 한 번의 압축 실행. 크기 확인 → EXIF(방향·보존 태그) → 계획 → 축소 디코딩 → 방향·자르기·축소를 한 번에 → 알파 합성 → 전략 인코딩.
 * 입력은 [InputSession] 으로 한 번 열어 되감아 쓴다(기본 제공 파일·URI).
 */
internal class CompressionPipeline(
    private val config: CompressorConfig,
    private val platform: ImagePlatform = ImagePlatform.Default,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    /** 마지막 실행에서 동시에 살아 있던 SDK 소유 Bitmap 바이트의 최댓값(시험용 관측값). */
    @Volatile var peakBitmapBytes: Long = 0
        private set

    fun run(source: ImageSource, ensureActive: () -> Unit): CompressedImage {
        val tracker = BitmapTracker()
        try {
            return source.openSession().use { execute(it, ensureActive, tracker) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageCompressionException) {
            throw e
        } catch (e: CallerArgumentException) {
            throw e.original
        } catch (e: IOException) {
            throw ImageIoException("이미지를 읽거나 쓰지 못했다", e)
        } catch (e: SecurityException) {
            throw ImageIoException("입력을 읽을 권한이 없다(만료된 URI 권한 등)", e)
        } catch (e: OutOfMemoryError) {
            throw ImageOutOfMemoryException("메모리가 부족하다 — maxDimension·maxPixels 를 줄인다", e)
        } catch (e: Exception) {
            throw ImageEncodeException("압축 중 예기치 않은 실패", e)
        } finally {
            peakBitmapBytes = tracker.peakBytes
            tracker.recycleAll()
        }
    }

    private fun execute(session: InputSession, ensureActive: () -> Unit, tracker: BitmapTracker): CompressedImage {
        // 1. 크기만 읽는다 — 픽셀은 메모리에 올리지 않는다
        val bounds = session.stream().use { platform.decodeBounds(it) }
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw ImageDecodeException("이미지 크기를 읽을 수 없다(손상됐거나 이 기기가 지원하지 않는 형식, mime=${bounds.mimeType})")
        }
        ensureActive()

        // 2. EXIF 한 번으로 방향과 보존할 태그를 함께 읽는다. 형식(HEIF·AVIF 포함)과 관계없이 SDK 가 방향을 적용한다
        val warnings = LinkedHashSet<CompressionWarning>()
        val exif = readExif(session, warnings)
        val orientation = exif.orientation.takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
        val snapshot = MetadataSnapshot(exif.attributes)
        val swap = Orientation.swapsDimensions(orientation)
        val orientedW = if (swap) bounds.height else bounds.width
        val orientedH = if (swap) bounds.width else bounds.height
        ensureActive()

        // 3. 계획
        val plan = limitPixels(callerCode { checkedPlan(config.resizer.plan(orientedW, orientedH), orientedW, orientedH) })
        val sampleSize = SampleSize.compute(plan.cropWidth, plan.cropHeight, plan.width, plan.height)

        // 4. 축소 디코딩
        val sampled = session.stream().use { platform.decode(it, sampleSize) }
            ?: throw ImageDecodeException("이미지 디코딩 결과가 없다(mime=${bounds.mimeType})")
        tracker.track(sampled)
        ensureActive()

        // 5. 방향·자르기·축소·8비트 변환을 한 번에 — 회전한 전체 사본을 따로 만들지 않는다
        var bitmap = tracker.replace(sampled, transform(sampled, orientation, plan, orientedW, orientedH))
        // 6. 알파 합성
        if (!config.format.supportsAlpha && bitmap.hasAlpha()) {
            bitmap = tracker.replace(bitmap, flatten(bitmap))
        }
        dropGainmap(bitmap, sdkInt)
        ensureActive()

        // 7. 인코딩
        val context = Context(bitmap, snapshot, tracker, ensureActive)
        val encoded = callerCode { config.strategy.compress(context) }
        return CompressedImage(
            data = encoded.data,
            format = config.format,
            width = encoded.width,
            height = encoded.height,
            quality = encoded.quality,
            sourceWidth = orientedW,
            sourceHeight = orientedH,
            sampleSize = sampleSize,
            attempts = encoded.attempts,
            preservedMetadata = if (snapshot.isEmpty) emptySet() else config.preserveMetadata,
            warnings = warnings,
        )
    }

    /**
     * 손상·특이 EXIF 는 정상 이미지를 실패시키지 않는다 — 방향은 정상(1)으로, 보존 태그는 비워 두고 경고를 남긴다.
     * 손상된 길이 필드 때문에 ExifInterface 가 거대한 배열을 요구해 OutOfMemoryError 를 내는 경우도 여기에 든다.
     * 입력 자체를 열지 못하는 것(권한·입출력)은 대체하지 않고 그대로 올린다.
     */
    private fun readExif(session: InputSession, warnings: MutableSet<CompressionWarning>): ExifData {
        val tags = MetadataCopier.tagsFor(config.preserveMetadata)
        return session.stream().use { stream ->
            try {
                platform.readExif(stream, tags)
            } catch (e: Exception) {
                warnings += CompressionWarning.EXIF_UNREADABLE
                UNREADABLE_EXIF
            } catch (e: OutOfMemoryError) {
                warnings += CompressionWarning.EXIF_UNREADABLE
                UNREADABLE_EXIF
            }
        }
    }

    private fun checkedPlan(plan: ResizePlan, w: Int, h: Int): ResizePlan {
        require(plan.cropLeft + plan.cropWidth <= w && plan.cropTop + plan.cropHeight <= h) {
            "Resizer 가 원본(${w}x$h) 밖의 영역을 지정했다: $plan"
        }
        return plan
    }

    /** 출력 픽셀 수가 [CompressorConfig.maxPixels] 를 넘으면 종횡비를 유지한 채 줄인다. */
    private fun limitPixels(plan: ResizePlan): ResizePlan {
        val pixels = plan.width.toLong() * plan.height
        if (pixels <= config.maxPixels) return plan
        val f = sqrt(config.maxPixels.toDouble() / pixels)
        val w = floor(plan.width * f).toInt().coerceAtLeast(1)
        val h = floor(plan.height * f).toInt().coerceAtLeast(1)
        return ResizePlan(plan.cropLeft, plan.cropTop, plan.cropWidth, plan.cropHeight, w, h)
    }

    /**
     * 방향 적용 전의 축소 디코딩본 [raw] 에서, 방향 적용 후 기준 [plan] 영역을 잘라 방향을 반영하고 목표 크기로 만든다.
     * 결과 Bitmap 하나에 행렬로 한 번 그리므로 최대 메모리는 [raw] 와 결과뿐이다(회전한 전체 사본을 만들지 않는다).
     * 결과는 언제나 sRGB `ARGB_8888` 이다 — 16비트 PNG·10비트 HEIF 는 플랫폼이 `RGBA_F16`·`RGBA_1010102` 로 디코딩한다.
     * 바꿀 것이 없으면 [raw] 그대로.
     */
    private fun transform(raw: Bitmap, orientation: Int, plan: ResizePlan, fullW: Int, fullH: Int): Bitmap {
        val swap = Orientation.swapsDimensions(orientation)
        val ow = if (swap) raw.height else raw.width
        val oh = if (swap) raw.width else raw.height
        val sx = ow.toDouble() / fullW
        val sy = oh.toDouble() / fullH
        val left = (plan.cropLeft * sx).roundToInt().coerceIn(0, ow - 1)
        val top = (plan.cropTop * sy).roundToInt().coerceIn(0, oh - 1)
        val w = (plan.cropWidth * sx).roundToInt().coerceIn(1, ow - left)
        val h = (plan.cropHeight * sy).roundToInt().coerceIn(1, oh - top)
        val scaling = w != plan.width || h != plan.height
        val orient = Orientation.matrix(orientation)
        val whole = left == 0 && top == 0 && w == ow && h == oh
        if (orient == null && !scaling && whole && raw.config == Bitmap.Config.ARGB_8888) return raw

        val (rx, ry, rw, rh) = Orientation.rawRect(orientation, raw.width, raw.height, left, top, w, h)
        val matrix = orient ?: Matrix()
        if (scaling) matrix.postScale(plan.width.toFloat() / w, plan.height.toFloat() / h)
        val mapped = RectF(0f, 0f, rw.toFloat(), rh.toFloat()).also { matrix.mapRect(it) }
        val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            translate(-mapped.left, -mapped.top)
            concat(matrix)
            drawBitmap(raw, Rect(rx, ry, rx + rw, ry + rh), RectF(0f, 0f, rw.toFloat(), rh.toFloat()), Paint().apply { isFilterBitmap = scaling })
        }
        out.setHasAlpha(raw.hasAlpha())
        return out
    }

    /** 투명 픽셀을 배경색 위에 합성한다. 배경색이 반투명이어도 결과가 검게 나오지 않도록 흰색을 먼저 깐다. */
    private fun flatten(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawColor(config.backgroundColor)
            drawBitmap(source, 0f, 0f, null)
        }
        out.setHasAlpha(false)
        return out
    }

    private inner class Context(
        override val bitmap: Bitmap,
        private val snapshot: MetadataSnapshot,
        private val tracker: BitmapTracker,
        private val active: () -> Unit,
    ) : EncodeContext {
        override val format: ImageFormat get() = config.format
        override val quality: Int get() = config.quality

        override fun encode(bitmap: Bitmap, quality: Int): ByteArray {
            val bytes = try {
                config.encoder.encode(bitmap, config.format, quality)
            } catch (e: ImageCompressionException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IOException) {
                throw ImageEncodeException("${config.format.name} 인코딩 중 입출력 실패", e)
            } catch (e: Exception) {
                throw ImageEncodeException("${config.format.name} 인코딩 실패", e)
            }
            if (bytes.isEmpty()) throw ImageEncodeException("${config.format.name} 인코더가 빈 결과를 냈다")
            return MetadataCopier.apply(bytes, snapshot, config.format)
        }

        override fun scaledTo(width: Int, height: Int): Bitmap {
            require(width > 0 && height > 0) { "크기는 1 이상이어야 한다: ${width}x$height" }
            return tracker.track(Bitmap.createScaledBitmap(bitmap, width, height, true))
        }

        override fun release(bitmap: Bitmap) {
            if (bitmap !== this.bitmap) tracker.release(bitmap)
        }

        override fun ensureActive() = active()
    }

    private companion object {
        val UNREADABLE_EXIF = ExifData(ExifInterface.ORIENTATION_NORMAL, emptyMap())
    }
}

/**
 * 사용자 코드([Resizer]·[CompressionStrategy]·[Encoder])가 던진 [IllegalArgumentException] 을 표시해 그대로 내보낸다.
 * 표시가 없는 IAE(플랫폼 내부)는 SDK 예외로 감싼다.
 */
private class CallerArgumentException(val original: IllegalArgumentException) : RuntimeException(original)

private inline fun <T> callerCode(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw CallerArgumentException(e)
    }

/** 만든 Bitmap 을 추적해 어떤 경로(예외·취소 포함)로 끝나도 전부 회수한다. */
internal class BitmapTracker {
    private val bitmaps = ArrayList<Bitmap>()

    /** 동시에 추적 중이던 Bitmap 바이트 합의 최댓값. */
    var peakBytes: Long = 0
        private set

    fun track(bitmap: Bitmap): Bitmap {
        if (bitmaps.none { it === bitmap }) {
            bitmaps.add(bitmap)
            val live = bitmaps.sumOf { if (it.isRecycled) 0L else it.allocationByteCount.toLong() }
            if (live > peakBytes) peakBytes = live
        }
        return bitmap
    }

    /** [old] 를 [new] 로 바꾼다. 서로 다르면 [old] 는 즉시 회수한다. */
    fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) {
            track(new)
            release(old)
        }
        return new
    }

    fun release(bitmap: Bitmap) {
        bitmaps.removeAll { it === bitmap }
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    fun recycleAll() {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
    }
}

internal object SampleSize {
    /**
     * [cropWidth]x[cropHeight] 영역을 [outWidth]x[outHeight] 로 만들 때, 디코딩 결과가 목표보다 작아지지 않는
     * 가장 큰 2의 거듭제곱 `inSampleSize`.
     */
    fun compute(cropWidth: Int, cropHeight: Int, outWidth: Int, outHeight: Int): Int {
        var size = 1
        while (size < MAX_SAMPLE && cropWidth / (size * 2) >= outWidth && cropHeight / (size * 2) >= outHeight) {
            size *= 2
        }
        return size
    }

    private const val MAX_SAMPLE = 1 shl 20
}
