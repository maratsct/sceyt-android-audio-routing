package com.sceyt.audiorouting.internal.wired

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.sceyt.audiorouting.internal.Logger

/**
 * Handles wired headset (3.5mm jack, USB-C audio) connection detection.
 */
internal class WiredHeadsetHandler(
    private val context: Context,
    private val logger: Logger,
    private val onWiredHeadsetConnected: () -> Unit,
    private val onWiredHeadsetDisconnected: () -> Unit
) {
    companion object {
        private const val STATE_UNPLUGGED = 0
        private const val STATE_PLUGGED = 1
        private const val EXTRA_STATE = "state"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MICROPHONE = "microphone"
    }

    private var isReceiverRegistered = false
    private var _isConnected = false

    /**
     * Whether a wired headset is currently connected.
     */
    val isConnected: Boolean
        get() = _isConnected

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                handleHeadsetPlugEvent(intent)
            }
        }
    }

    /**
     * Starts listening for wired headset events.
     */
    fun start() {
        if (isReceiverRegistered) {
            logger.d("WiredHeadsetHandler already started")
            return
        }

        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(headsetReceiver, filter)
        }

        isReceiverRegistered = true
        logger.d("WiredHeadsetHandler started")
    }

    /**
     * Stops listening for wired headset events.
     */
    fun stop() {
        if (!isReceiverRegistered) return

        try {
            context.unregisterReceiver(headsetReceiver)
        } catch (e: IllegalArgumentException) {
            logger.w("Receiver not registered: ${e.message}")
        }

        isReceiverRegistered = false
        _isConnected = false
        logger.d("WiredHeadsetHandler stopped")
    }

    private fun handleHeadsetPlugEvent(intent: Intent) {
        val state = intent.getIntExtra(EXTRA_STATE, STATE_UNPLUGGED)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Wired Headset"
        val hasMicrophone = intent.getIntExtra(EXTRA_MICROPHONE, 0) == 1

        logger.d("Wired headset event: state=$state, name=$name, hasMic=$hasMicrophone")

        when (state) {
            STATE_PLUGGED -> {
                if (!_isConnected) {
                    _isConnected = true
                    logger.d("Wired headset connected: $name")
                    onWiredHeadsetConnected()
                }
            }
            STATE_UNPLUGGED -> {
                if (_isConnected) {
                    _isConnected = false
                    logger.d("Wired headset disconnected: $name")
                    onWiredHeadsetDisconnected()
                }
            }
        }
    }
}
