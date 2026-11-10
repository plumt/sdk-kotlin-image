package net.plumt.image.sample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.plumt.image.compress.CompressionCallHandle
import net.plumt.image.compress.CompressionProfiles
import net.plumt.image.compress.CompressionResult
import net.plumt.image.compress.CompressorConfig
import net.plumt.image.compress.ImageCompressionException
import net.plumt.image.compress.ImageCompressor
import net.plumt.image.compress.ImageDecodeException
import net.plumt.image.compress.ImageEncodeException
import net.plumt.image.compress.ImageFormat
import net.plumt.image.compress.ImageIoException
import net.plumt.image.compress.ImageOutOfMemoryException
import net.plumt.image.compress.ImageSource
import net.plumt.image.compress.ImageTarget
import net.plumt.image.compress.TargetSizeNotReachedException

/** 공개 메서드를 버튼 하나씩으로 불러 결과를 화면에 쌓는다. 입력 사진은 코드로 그린다(저장소에 사진을 두지 않는다). */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pending: CompressionCallHandle? = null
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val compressor = ImageCompressor.create(CompressorConfig.builder().maxDimension(1280).build())
        val source = ImageSource.bytes(drawPhoto(3000, 2000))
        log = TextView(this).apply { setText(R.string.ready) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(button(R.string.compress_suspend) { scope.launch { show("suspend", compressor.compress(source)) } })
        column.addView(button(R.string.compress_callback) { pending = compressor.compress(source) { show("콜백", it) } })
        column.addView(button(R.string.default_config) { pending = ImageCompressor.create().compress(source) { show("기본 설정", it) } })
        column.addView(button(R.string.target_size) {
            val webp = ImageCompressor.create(CompressorConfig.builder().format(ImageFormat.WEBP).targetBytes(15_000).build())
            pending = webp.compress(source) { show("용량 목표", it) }
        })
        column.addView(button(R.string.target_unreachable) {
            val tiny = ImageCompressor.create(CompressorConfig.builder().targetBytes(10).minDimension(512).build())
            pending = tiny.compress(source) { show("도달 불가", it) }
        })
        column.addView(button(R.string.broken_input) {
            pending = compressor.compress(ImageSource.bytes(byteArrayOf(1, 2, 3))) { show("깨진 입력", it) }
        })
        column.addView(button(R.string.avatar) {
            val target = File(cacheDir, "avatar.jpg")
            val avatar = ImageCompressor.create(CompressorConfig.builder().profile(CompressionProfiles.Avatar).build())
            pending = avatar.compress(source, ImageTarget.file(target)) { show("avatar 파일 ${target.length()}B", it) }
        })
        column.addView(button(R.string.cancel) { pending?.cancel(); append("cancel: active=${pending?.isActive}") })
        column.addView(log)
        // target 35 이상은 edge-to-edge 가 강제라 시스템 바 높이만큼 비켜 둔다
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onDestroy() {
        pending?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun show(label: String, result: CompressionResult) {
        val text = when (result) {
            is CompressionResult.Success -> result.image.toString()
            is CompressionResult.Failure -> "실패 ${errorName(result.error)}: ${result.error.message}"
        }
        append("$label: $text")
    }

    // R8 이 클래스 이름을 줄이고 is 검사가 없는 예외 클래스끼리는 합치기도 해서 simpleName 대신 타입으로 가른다
    private fun errorName(error: ImageCompressionException): String = when (error) {
        is ImageDecodeException -> "ImageDecodeException"
        is ImageEncodeException -> "ImageEncodeException"
        is ImageIoException -> "ImageIoException"
        is ImageOutOfMemoryException -> "ImageOutOfMemoryException"
        is TargetSizeNotReachedException -> "TargetSizeNotReachedException(smallest=${error.smallestBytes}B)"
    }

    private fun append(line: String) {
        log.append("\n" + line)
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    /** 줄무늬와 원을 그린 JPEG. 잡음이 없어 용량 목표가 쉽게 맞는다. */
    private fun drawPhoto(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(40, 90, 160))
            paint.color = Color.rgb(240, 200, 60)
            drawCircle(width * 0.6f, height * 0.4f, height * 0.3f, paint)
            paint.color = Color.rgb(30, 140, 70)
            for (i in 0 until 20) drawRect(0f, height * (0.7f + i * 0.015f), width.toFloat(), height * (0.705f + i * 0.015f), paint)
        }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }
}
