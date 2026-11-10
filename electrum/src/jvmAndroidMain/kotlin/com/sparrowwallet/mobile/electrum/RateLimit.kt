package com.sparrowwallet.mobile.electrum

import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * In a coroutine context holding this, a backend call the server rate-limits (HTTP 429) fails at once with a
 * [RateLimitedException] instead of waiting out the server's Retry-After. The inscription check's calls run under it,
 * so a check on a rate-limited server ends (saving what it decided) rather than keeping the server busy; the wallet's
 * own calls (sync, send) run without it and keep their retries.
 */
object NoRateLimitWait : CoroutineContext.Element, CoroutineContext.Key<NoRateLimitWait> {
    override val key: CoroutineContext.Key<*> get() = this
}

/** A call refused with HTTP 429 under [NoRateLimitWait]. */
class RateLimitedException(message: String) : IOException(message)