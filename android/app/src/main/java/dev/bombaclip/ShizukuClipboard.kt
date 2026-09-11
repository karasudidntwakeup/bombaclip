package dev.bombaclip

import android.content.ClipData
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuClipboard {

    private const val TAG = "bombaclip"
    private const val TRANSACTION_GET_PRIMARY_CLIP = 2

    fun read(): String? {
        if (!Shizuku.pingBinder()) return null
        return try {
            val binder = ShizukuBinderWrapper(
                rikka.shizuku.SystemServiceHelper.getSystemService("clipboard")
            )
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.internal.IClipboard")
                data.writeString("dev.bombaclip")
                data.writeString(null)
                data.writeString("dev.bombaclip")
                data.writeInt(0)
                val ok = binder.transact(TRANSACTION_GET_PRIMARY_CLIP, data, reply, 0)
                if (!ok) return null
                reply.readException()
                val clipData = reply.readTypedObject(ClipData.CREATOR)
                clipData?.getItemAt(0)?.coerceToText(null)?.toString()
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
