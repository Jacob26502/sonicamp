package dev.subsonic.poweramp.provider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import dev.subsonic.poweramp.stream.StreamSession
import java.io.IOException

/**
 * One seekable descriptor handed to Poweramp, for
 * [android.os.storage.StorageManager.openProxyFileDescriptor]. Reads are served by the track's
 * shared [StreamSession]. Runs on a dedicated handler thread, so blocking is fine.
 */
class RemoteFileCallback(
    private val session: StreamSession,
    private val onClosed: () -> Unit,
) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long = session.size

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        session.read(offset, size, data)
    } catch (e: IOException) {
        Log.w("SubsonicStream", "Read of ${session.trackId} at $offset failed: ${e.javaClass.simpleName}: ${e.message}")
        throw ErrnoException("onRead", OsConstants.EIO)
    }

    override fun onRelease() = onClosed()
}
