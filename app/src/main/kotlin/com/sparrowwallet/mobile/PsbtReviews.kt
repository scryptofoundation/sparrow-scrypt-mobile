package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Psbt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A PSBT and its review, tied to the exact text that was reviewed. */
class ReviewedPsbt<R>(val text: String, val result: Result<Pair<ByteArray, R>>) {
    /** Signing is allowed only while the field still holds the text that was reviewed. */
    fun isFor(input: String) = input.trim() == text
}

/**
 * Reviews pasted or typed PSBT text once typing pauses, off the main thread. [review]
 * unlocks the wallet (seed stretching), so it must not run on every keystroke.
 */
@OptIn(FlowPreview::class)
fun <R> psbtReviews(texts: Flow<String>, pauseMs: Long = 300, review: (ByteArray) -> R): Flow<ReviewedPsbt<R>?> =
    texts.map { it.trim() }
        .distinctUntilChanged()
        .debounce(pauseMs)
        .map { text ->
            if(text.isEmpty()) null
            else ReviewedPsbt(text, runCatching { Psbt.fromBase64(text).let { it to review(it) } })
        }
        .flowOn(Dispatchers.Default)
