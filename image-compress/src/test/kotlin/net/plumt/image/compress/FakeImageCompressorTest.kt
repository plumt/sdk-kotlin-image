package net.plumt.image.compress

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 가짜 압축기는 Android 없이(순수 JVM) 돈다 — Robolectric 러너를 쓰지 않는다. */
class FakeImageCompressorTest {
    private val source = ImageSource.bytes(byteArrayOf(1))

    @Test fun 기본은_설정을_적은_1x1_결과() {
        val fake = FakeImageCompressor()
        val r = runBlocking { fake.compress(source) }.orThrow()
        assertEquals(ImageFormat.JPEG, r.format)
        assertEquals(80, r.quality)
        assertEquals(1, r.width)
        assertEquals(1, r.height)
        assertEquals(1, r.attempts)
        assertTrue(r.preservedMetadata.isEmpty())
        assertTrue(r.warnings.isEmpty())
        assertEquals(1, fake.calls)
        assertSame(source, fake.sources.single())
    }

    @Test fun 설정_블록과_품질없는_형식() {
        val fake = FakeImageCompressor(CompressorConfig.builder().format(ImageFormat.PNG).build())
        assertEquals(ImageFormat.PNG, fake.config.format)
        assertNull(runBlocking { fake.compress(source) }.orThrow().quality)
    }

    @Test fun respondWith_는_결과를_정한다() {
        val fake = FakeImageCompressor()
        val bytes = byteArrayOf(5, 6, 7)
        fake.respondWith(bytes, 30, 20)
        bytes[0] = 0
        val r = runBlocking { fake.compress(source) } as CompressionResult.Success
        assertArrayEquals(byteArrayOf(5, 6, 7), r.image.data)
        assertEquals(30, r.image.width)
        assertEquals(20, r.image.sourceHeight)
    }

    @Test fun respondWith_잘못된_값은_IAE() {
        val fake = FakeImageCompressor()
        for (call in listOf({ fake.respondWith(ByteArray(0), 1, 1) }, { fake.respondWith(byteArrayOf(1), 0, 1) })) {
            try {
                call()
                fail("IAE 를 던져야 한다")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test fun 실패를_정하고_되돌린다() {
        val fake = FakeImageCompressor()
        val cases = listOf<Pair<() -> Unit, Class<*>>>(
            { fake.failWithDecodeError("d") } to ImageDecodeException::class.java,
            { fake.failWithIoError("i") } to ImageIoException::class.java,
            { fake.failWithOutOfMemory("o") } to ImageOutOfMemoryException::class.java,
            { fake.failWith(TargetSizeNotReachedException(1, 2, 3, 4)) } to TargetSizeNotReachedException::class.java,
        )
        for ((arrange, type) in cases) {
            arrange()
            val r = runBlocking { fake.compress(source) } as CompressionResult.Failure
            assertTrue(type.isInstance(r.error))
            val again = runBlocking { fake.compress(source) } as CompressionResult.Failure
            assertSame(r.error, again.error)
        }
        assertTrue(fake.toString().contains("TargetSizeNotReachedException"))
        fake.succeed()
        assertTrue(runBlocking { fake.compress(source) } is CompressionResult.Success)
        assertTrue(fake.toString().contains("success"))
    }

    @Test fun 콜백은_바로_결과를_준다() {
        val fake = FakeImageCompressor()
        val got = ArrayList<CompressionResult>()
        val handle = fake.compress(source) { got += it }
        assertTrue(got.single() is CompressionResult.Success)
        assertFalse(handle.isActive)
        handle.cancel()
        fake.failWithIoError("x")
        fake.compress(source) { got += it }
        assertTrue(got[1] is CompressionResult.Failure)
        assertEquals(2, fake.calls)
    }

    @Test fun 대상이_있으면_실제로_쓴다() {
        val fake = FakeImageCompressor()
        val out = java.io.ByteArrayOutputStream()
        assertTrue(runBlocking { fake.compress(source, ImageTarget.stream(out)) } is CompressionResult.Success)
        assertArrayEquals(byteArrayOf(0x46, 0x41, 0x4B, 0x45), out.toByteArray())
        val failing = object : ImageTarget {
            override fun write(data: ByteArray) = throw java.io.IOException("x")
        }
        assertTrue((runBlocking { fake.compress(source, failing) } as CompressionResult.Failure).error is ImageIoException)
        val got = ArrayList<CompressionResult>()
        fake.compress(source, ImageTarget.stream(out)) { got += it }
        assertTrue(got.single() is CompressionResult.Success)
        fake.failWithDecodeError("d")
        fake.compress(source, ImageTarget.stream(out)) { got += it }
        assertTrue(got[1] is CompressionResult.Failure)
    }
}
