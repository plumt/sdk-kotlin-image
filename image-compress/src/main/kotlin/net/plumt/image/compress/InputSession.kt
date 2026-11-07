package net.plumt.image.compress

import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel

/** 압축 한 번 동안 입력을 처음부터 여러 번 읽는 창구. 끝나면 닫는다. */
internal interface InputSession : Closeable {
    /** 처음부터 읽는 스트림. 호출자가 닫는다(닫아도 세션은 그대로다). */
    fun stream(): InputStream
}

/** 한 번 연 입력을 되감아 쓰는 세션을 직접 만들 수 있는 기본 제공 소스. */
internal interface SessionSource : ImageSource {
    fun openSession(): InputSession
}

internal fun ImageSource.openSession(): InputSession =
    if (this is SessionSource) openSession() else ReopeningSession(this)

/** 읽을 때마다 [ImageSource.open] 을 다시 부른다(사용자 구현 소스·메모리 소스). */
internal class ReopeningSession(private val source: ImageSource) : InputSession {
    override fun stream(): InputStream = source.open()

    override fun close() {}
}

/**
 * 탐색 가능한 파일 기술자 하나를 매번 0 으로 되감아 읽는다. 스트림은 `FileInputStream` 이라
 * ExifInterface 가 기술자를 직접 써서 HEIF 의 회전·EXIF 도 읽는다. [owner] 를 닫으면 기술자가 닫힌다.
 */
internal class SeekableFdSession private constructor(
    private val fd: FileDescriptor,
    private val channel: FileChannel,
    private val owner: Closeable,
) : InputSession {
    override fun stream(): InputStream {
        channel.position(0)
        return SharedFdInputStream(fd)
    }

    override fun close() = owner.close()

    companion object {
        /** [fd] 를 되감을 수 있으면 세션, 파이프처럼 탐색할 수 없으면 null. [owner] 는 닫지 않는다. */
        fun create(fd: FileDescriptor, owner: Closeable): SeekableFdSession? {
            val channel = SharedFdInputStream(fd).channel
            val seekable = try {
                channel.position(0)
                channel.position() == 0L
            } catch (e: Exception) {
                false
            }
            return if (seekable) SeekableFdSession(fd, channel, owner) else null
        }
    }
}

/** 기술자를 공유하는 스트림. 닫아도 기술자를 닫지 않는다 — 세션이 닫는다. */
private class SharedFdInputStream(fd: FileDescriptor) : FileInputStream(fd) {
    override fun close() {}
}
