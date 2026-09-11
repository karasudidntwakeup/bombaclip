package dev.bombaclip

import android.content.ClipData
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuClipboard {

    private const val TAG = "bombaclip"
    private const val TRANSACTION_GET_PRIMARY_CLIP = 4

    fun read(): String? {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "shizuku binder dead, cannot read clipboard")
            return null
        }
        return try {
            val binder = ShizukuBinderWrapper(
                rikka.shizuku.SystemServiceHelper.getSystemService("clipboard")
            )
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.content.IClipboard")
                data.writeString("com.android.shell")
                data.writeString(null)
                data.writeInt(0)
                data.writeInt(0)
                val ok = binder.transact(TRANSACTION_GET_PRIMARY_CLIP, data, reply, 0)
                if (!ok) { Log.w(TAG, "transact failed"); return null }
                        reply.readException()
                        val clipData = reply.readTypedObject(ClipData.CREATOR)
                        if (clipData == null) { Log.w(TAG, "clipData null from transact"); return null }
                        // Only mirror text clips. Image/URI clips come back as
                        // garbage strings via coerceToText and would loop.
                        val item = clipData.getItemAt(0)
                        if (item.uri != null) { Log.i(TAG, "clip is uri/image, skipping"); return null }
                        item.coerceToText(null)?.toString()
                    } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "shizuku clipboard read failed", e)
            null
        }
    }
}
