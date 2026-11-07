package net.plumt.image.compress

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.io.ByteArrayOutputStream

/**
 * 시험용 파일 provider. 모든 열기를 [modes] 에 적고, [failure] 가 던지면 그대로 호출부로 간다.
 * Robolectric 에 authority [AUTHORITY] 로 등록해 쓴다. 상태는 시험마다 [reset] 한다.
 */
class FileBackedProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        modes += mode
        failure(mode)
        return ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.parseMode(mode))
    }

    /** [partialAsset] 이면 "파일 일부" 기술자를 돌려준다 — openFileDescriptor 는 실패하고 스트림 열기만 된다. */
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode)
        return if (partialAsset) AssetFileDescriptor(pfd, 0, file!!.length()) else AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY: String = "net.plumt.image.compress.test.files"
        val uri: Uri = Uri.parse("content://$AUTHORITY/image")

        @Volatile var file: File? = null
        val modes: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var failure: (String) -> Unit = {}
        @Volatile var partialAsset: Boolean = false

        fun reset(target: File) {
            file = target
            modes.clear()
            failure = {}
            partialAsset = false
        }
    }
}

/** 성분당 16비트 RGBA PNG 를 직접 만든다(플랫폼 인코더는 8비트만 쓴다). */
internal object Png16 {
    fun rgba16(width: Int, height: Int, pixel: (x: Int, y: Int) -> Long): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0) // 필터 없음
            for (x in 0 until width) {
                val p = pixel(x, y) // 0xRRRRGGGGBBBBAAAA
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) raw.write(((p ushr shift) and 0xFF).toInt())
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val idat = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(width); writeInt(height)
            write(16); write(6); write(0); write(0); write(0)
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", idat.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** PNG 의 IHDR 비트 깊이. */
    fun bitDepth(png: ByteArray): Int = png[24].toInt() and 0xFF

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24 and 0xFF); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF)
    }
}
