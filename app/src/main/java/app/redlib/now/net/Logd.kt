package app.redlib.now.net

import android.util.Log

/** Thin wrapper so all network diagnostics funnel into one logcat tag. */
object Logd {
    const val TAG = "NowRedlib"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
