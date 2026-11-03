package net.plumt.image.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 한 번의 압축 실행. 크기 확인 → 방향 → 계획 → 축소 디코딩 → 방향 적용 → 자르기·축소 → 알파 합성 → 전략 인코딩. */
internal class CompressionPipeline(private val config: CompressorConfig) {

    fun run(source: ImageSource, ensureActive: () -> Unit): CompressedImage {
        val tracker = BitmapTracker()
        try {
            return execute(source, ensureActive, tracker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageCompressionException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IOException) {
            throw ImageIoException("이미지를 읽거나 쓰지 못했다", e)
        } catch (e: OutOfMemoryError) {
            throw ImageOutOfMemoryException("메모리가 부족하다 — maxDimension·maxPixels 를 줄인다", e)
        } catch (e: Exception) {
            throw ImageEncodeException("압축 중 예기치 않은 실패", e)
        } finally {
            tracker.recycleAll()
        }
    }

    private fun execute(source: ImageSource, ensureActive: () -> Unit, tracker: BitmapTracker): CompressedImage {
        // 1. 크기만 읽는다 — 픽셀은 메모리에 올리지 않는다
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeException("이미지 크기를 읽을 수 없다(손상됐거나 이 기기가 지원하지 않는 형식, mime=${bounds.outMimeType})")
        }
        ensureActive()

        // 2. 방향. HEIC·AVIF 는 플랫폼 디코더가 컨테이너의 회전을 이미 적용하므로 EXIF 로 다시 돌리지 않는다
        val orientation = if (bounds.outMimeType in CONTAINER_ROTATED) {
            ExifInterface.ORIENTATION_NORMAL
        } else {
            source.open().use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }
        val swap = Orientation.swapsDimensions(orientation)
        val orientedW = if (swap) bounds.outHeight else bounds.outWidth
        val orientedH = if (swap) bounds.outWidth else bounds.outHeight

        // 3. 계획
        val plan = limitPixels(checkedPlan(config.resizer.plan(orientedW, orientedH), orientedW, orientedH))
        val sampleSize = SampleSize.compute(plan.cropWidth, plan.cropHeight, plan.width, plan.height)
        val snapshot = MetadataCopier.read(config.preserveMetadata) { source.open() }
        ensureActive()

        // 4. 축소 디코딩
        val sampled = source.open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw ImageDecodeException("이미지 디코딩 결과가 없다(mime=${bounds.outMimeType})")
        tracker.track(sampled)
        ensureActive()

        // 5. 방향 적용 → 6. 자르기·축소 → 7. 알파 합성
        var bitmap = tracker.replace(sampled, Orientation.apply(sampled, orientation))
        ensureActive()
        bitmap = tracker.replace(bitmap, cropAndScale(bitmap, plan, orientedW, orientedH))
        if (!config.format.supportsAlpha() && bitmap.hasAlpha()) {
            bitmap = tracker.replace(bitmap, flatten(bitmap))
        }
        ensureActive()

        // 8. 인코딩
        val context = Context(bitmap, snapshot, tracker, ensureActive)
        val encoded = config.strategy.compress(context)
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
        )
    }

    private fun ImageFormat.supportsAlpha(): Boolean = this != ImageFormat.JPEG

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

    /** 방향이 적용된 [oriented](축소 디코딩돼 원본보다 작을 수 있음)에서 [plan] 영역을 잘라 목표 크기로 만든다. */
    private fun cropAndScale(oriented: Bitmap, plan: ResizePlan, fullW: Int, fullH: Int): Bitmap {
        val sx = oriented.width.toDouble() / fullW
        val sy = oriented.height.toDouble() / fullH
        val left = (plan.cropLeft * sx).roundToInt().coerceIn(0, oriented.width - 1)
        val top = (plan.cropTop * sy).roundToInt().coerceIn(0, oriented.height - 1)
        val w = (plan.cropWidth * sx).roundToInt().coerceIn(1, oriented.width - left)
        val h = (plan.cropHeight * sy).roundToInt().coerceIn(1, oriented.height - top)
        var result = oriented
        if (left != 0 || top != 0 || w != oriented.width || h != oriented.height) {
            result = Bitmap.createBitmap(oriented, left, top, w, h)
        }
        if (result.width != plan.width || result.height != plan.height) {
            val scaled = Bitmap.createScaledBitmap(result, plan.width, plan.height, true)
            if (result !== oriented && result !== scaled) result.recycle()
            result = scaled
        }
        return result
    }

    private fun flatten(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
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
        val CONTAINER_ROTATED = setOf("image/heic", "image/heif", "image/avif")
    }
}

/** 만든 Bitmap 을 추적해 어떤 경로(예외·취소 포함)로 끝나도 전부 회수한다. */
internal class BitmapTracker {
    private val bitmaps = ArrayList<Bitmap>()

    fun track(bitmap: Bitmap): Bitmap {
        if (bitmaps.none { it === bitmap }) bitmaps.add(bitmap)
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
