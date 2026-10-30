package com.sparrowwallet.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: hands a QR frame to the scanner that is on screen, as if the camera
 * had read it, so the air-gapped flow can be tested on an emulator without a camera:
 *
 *   adb shell am broadcast -n com.sparrowwallet.mobile/.DebugUrReceiver \
 *       -a com.sparrowwallet.mobile.DEBUG_UR --es part ur:crypto-psbt/1-3/...
 */
class DebugUrReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val part = intent.getStringExtra("part") ?: return
        // manifest receivers run on the main thread, where the scanner's state lives
        debugFrameSink?.invoke(part)
    }
}
