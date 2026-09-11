package bombaclip.karasu

import android.content.ClipData
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object ShizukuClipboard {

    private const val GET_PRIMARY_CLIP = 4

    /** Reads the phone clipboard through Shizuku. The server must run as the
     *  shell user (uid 2000): Android 15+ denies background reads for the app
     *  itself, and only com.android.shell is allowed to read in the background. */
    fun read(): String? {
        if (!Shizuku.pingBinder()) return null
        return try {
            val binder = ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("clipboard")
            )
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.content.IClipboard")
                data.writeString("com.android.shell")
                data.writeString(null)
                data.writeInt(0)
                data.writeInt(0)
                if (!binder.transact(GET_PRIMARY_CLIP, data, reply, 0)) return null
                reply.readException()
                val clip = reply.readTypedObject(ClipData.CREATOR) ?: return null
                // URI/image clips turn into garbage via coerceToText and would
                // cause a copy loop, so mirror text clips only.
                val item = clip.getItemAt(0)
                if (item.uri != null) return null
                item.coerceToText(null)?.toString()
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }
}