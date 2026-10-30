package com.sparrowwallet.mobile.crypto

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The air-gapped switch. Every network path (Electrum, Sparrow Link, prices, the MWEB
 * scanner, background sync) calls [check] before it opens anything, so one flag keeps
 * the whole app offline. The app persists it and sets it at startup. Connections that
 * stay open register with [whileOpen], so turning the switch on also cuts them.
 */
@OptIn(ExperimentalAtomicApi::class)
object NetworkPolicy {
    private class Cutter(val cut: () -> Unit)

    private val open = AtomicReference<Set<Cutter>>(emptySet())

    @Volatile var blocked: Boolean = false
        set(value) {
            field = value
            if(value) open.load().forEach { runCatching(it.cut) }
        }

    fun check(what: String) {
        if(blocked) throw AirGappedException(what)
    }

    /**
     * Runs [cut] when air-gapped mode turns on (at once if it already is). Returns the
     * function that stops watching, to call when the connection closes normally.
     */
    fun whileOpen(cut: () -> Unit): () -> Unit {
        val cutter = Cutter(cut)
        update { it + cutter }
        if(blocked) runCatching(cut) // the switch may have turned on before we were added
        return { update { it - cutter } }
    }

    private fun update(change: (Set<Cutter>) -> Set<Cutter>) {
        while(true) {
            val current = open.load()
            if(open.compareAndSet(current, change(current))) return
        }
    }
}

class AirGappedException(val what: String) : IllegalStateException("Air-gapped mode is on: $what is disabled")
