package com.wowsir.wowcast

import android.util.Log

/** Simple shared, on-screen log so Activity and Service can both report status. */
object AppLog {
    private val sb = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        sb.append(msg).append('\n')
        Log.i("WOWCast", msg)
        listener?.invoke(sb.toString())
    }

    @Synchronized
    fun dump(): String = sb.toString()
}
