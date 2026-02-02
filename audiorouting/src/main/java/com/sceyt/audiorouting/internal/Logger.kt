package com.sceyt.audiorouting.internal

import android.util.Log

/**
 * Internal logger for the audio routing module.
 */
internal class Logger(
    private val tag: String = "AudioRouter",
    var enabled: Boolean = false
) {
    fun d(message: String) {
        if (enabled) {
            Log.d(tag, message)
        }
    }

    fun i(message: String) {
        if (enabled) {
            Log.i(tag, message)
        }
    }

    fun w(message: String) {
        if (enabled) {
            Log.w(tag, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }
}
