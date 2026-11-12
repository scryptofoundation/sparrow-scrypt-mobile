package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.JsonValue
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.SocksProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a LitVM chain over JSON-RPC, read only (eth_chainId, eth_getBalance, eth_call, eth_getCode), as the desktop's
 * LitVmRpcClient reads it. Requests go through [HttpEndpoint]: the coin's proxy (the hostname resolved by the proxy),
 * the air-gap gate with the label "LitVM" before any socket, a capped body. They carry nothing that names the wallet: a
 * fixed JSON-RPC body, the generic user agent, ids from a counter. POSTs here are read-only queries, so they share the
 * kept connection (a dead kept one is retried once on a new connection).
 *
 * The chain id is verified once per client, before any other answer is used; a wrong one is remembered and nothing else
 * is asked. A [deadline] (on [clock]) stops new requests ([TimedOut]) and cuts each request's timeout to the time left.
 * [close] ends the client: every later call is refused before any request.
 */
class LitVmRpc private constructor(private val shared: Shared, private val deadline: () -> Long?) : LitVmCalls {
    constructor(network: LitVmNetwork, proxy: SocksProxy?, deadline: () -> Long? = { null }) :
        this(Shared(network, network.rpcUrl, proxy, ::monotonicMillis), deadline)

    private class Shared(val network: LitVmNetwork, rpcUrl: String, proxy: SocksProxy?, val clock: () -> Long) {
        @Volatile var closed = false
        /** Null until the chain id is read; then whether it is the network's. */
        @Volatile var chainMatches: Boolean? = null
        val ids = AtomicLong()
        val http = HttpEndpoint(rpcUrl, proxy, label = LABEL, usable = { !closed }, reusePosts = true)
    }

    override val network: LitVmNetwork get() = shared.network

    override val clock: () -> Long get() = shared.clock

    /** Throws unless the server reports the network's chain id; asked once per client. */
    suspend fun verifyChain() {
        when(shared.chainMatches) {
            true -> return
            false -> throw LitVmException(WRONG_CHAIN)
            null -> {}
        }
        val reported = LitVmReplies.quantity(request("eth_chainId", "[]"))
        if(reported != UInt256.of(shared.network.chainId)) {
            shared.chainMatches = false
            throw LitVmException(WRONG_CHAIN)
        }
        shared.chainMatches = true
    }

    override suspend fun balance(address: EvmAddress): UInt256 {
        verifyChain()
        return LitVmReplies.quantity(request("eth_getBalance", "[\"$address\",\"latest\"]"))
    }

    override suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int): ByteArray {
        val cap = maxResultBytes.coerceIn(0, LitVmCalls.MAX_RESULT_BYTES)
        verifyChain()
        return LitVmReplies.callResult(request("eth_call", "[{\"to\":\"$to\",\"data\":\"0x${data.toHex()}\"},\"latest\"]"), cap)
    }

    override suspend fun hasCode(address: EvmAddress): Boolean {
        verifyChain()
        return LitVmReplies.hasCode(request("eth_getCode", "[\"$address\",\"latest\"]"))
    }

    /** This client with a deadline: the earlier of it and this client's own. Shares the connection and the chain check. */
    override fun until(deadline: Long): LitVmRpc = LitVmRpc(shared) { this.deadline()?.let { minOf(it, deadline) } ?: deadline }

    /** Ends the client and every view of it: the call in progress fails with its connection, later ones are refused. */
    fun close() {
        shared.closed = true
        shared.http.close()
    }

    private suspend fun request(method: String, params: String): JsonValue {
        if(shared.closed) throw LitVmException(CLOSED)
        val limit = deadline()?.let { at ->
            val left = at - shared.clock()
            if(left <= 0) throw TimedOut()
            left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val id = shared.ids.incrementAndGet()
        val body = "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}"
        val response = shared.http.post("/", body, JSON, JSON, timeoutMs = limit)
        return LitVmReplies.result(response.status, response.body, id)
    }

    internal companion object {
        const val LABEL = "LitVM"
        const val WRONG_CHAIN = "This server is for another chain."
        const val CLOSED = "The LitVM connection was closed"
        private const val JSON = "application/json"

        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

        /** A client of another URL and clock, for tests against a local server. */
        fun forTest(network: LitVmNetwork, proxy: SocksProxy?, deadline: () -> Long?, clock: () -> Long, rpcUrl: String): LitVmRpc =
            LitVmRpc(Shared(network, rpcUrl, proxy, clock), deadline)
    }
}
