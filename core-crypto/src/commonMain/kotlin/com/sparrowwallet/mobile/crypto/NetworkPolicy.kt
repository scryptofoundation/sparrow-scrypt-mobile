package com.sparrowwallet.mobile.crypto

import kotlin.concurrent.Volatile

/**
 * The air-gapped switch. Every network path (Electrum, Sparrow Link, prices, the MWEB
 * scanner, background sync) calls [check] before it opens anything, so one flag keeps
 * the whole app offline. The app persists it and sets it at startup.
 */
object NetworkPolicy {
    @Volatile var blocked: Boolean = false

    fun check(what: String) {
        if(blocked) throw AirGappedException(what)
    }
}

class AirGappedException(val what: String) : IllegalStateException("Air-gapped mode is on: $what is disabled")
