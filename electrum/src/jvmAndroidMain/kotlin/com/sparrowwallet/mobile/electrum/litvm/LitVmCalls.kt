package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.EvmJson
import com.sparrowwallet.mobile.crypto.evm.JsonValue
import com.sparrowwallet.mobile.crypto.evm.TextRules
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.electrum.RateLimitedException
import java.io.IOException

/** A LitVM read that failed: the network, the server, a malformed answer, or a chain other than the network's. */
open class LitVmException(message: String) : IOException(message)

/** The called contract reverted: its own answer, not a failure of the network or the node. */
class CallReverted(message: String) : LitVmException(message)

/**
 * The contract answered more than the caller allows. The caller decides what that means: no URI for tokenURI, a
 * failure for a call whose answer is a word.
 */
class ResultTooLarge : LitVmException("The contract's answer is too large")

/** Not sent: the deadline had passed. */
class TimedOut : LitVmException("Timed out")

/**
 * What the collection reader and the lister ask a LitVM chain, so they can be tested with a fake. [LitVmRpc] is the real
 * one. Every answer has been checked against the network's chain id first.
 */
interface LitVmCalls {
    val network: LitVmNetwork

    /** Milliseconds on a monotonic clock: the clock [until] deadlines are on. */
    val clock: () -> Long

    /**
     * eth_call against the latest block: the raw return data, at most [maxResultBytes] (held to 100,000). A revert is
     * [CallReverted]; an answer over the cap is [ResultTooLarge]; anything else that goes wrong is an IOException.
     */
    suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int = DEFAULT_RESULT_BYTES): ByteArray

    /** The account's balance in wei. */
    suspend fun balance(address: EvmAddress): UInt256

    /** Whether the address holds contract code at the latest block. */
    suspend fun hasCode(address: EvmAddress): Boolean

    /** These calls with a deadline on [clock]: none starts at or after it ([TimedOut]), and each is cut to the time left. */
    fun until(deadline: Long): LitVmCalls

    companion object {
        const val DEFAULT_RESULT_BYTES = 8_192
        const val MAX_RESULT_BYTES = 100_000
    }
}

/**
 * How a JSON-RPC reply is read, in this order: HTTP 429, any other status but 2xx, the size, strict JSON (the reader NFT
 * metadata goes through), an object, the id, an error, then the result. Shared by [LitVmRpc] and the tests' fake node,
 * so both read a reply the same way.
 */
internal object LitVmReplies {
    const val MAX_RESPONSE_CHARS = 1_000_000
    //Server and contract text goes into an exception message only cleaned and capped
    private const val MAX_MESSAGE = 120
    private const val NOT_JSON_RPC = "The LitVM server's answer is not JSON-RPC"
    private const val INVALID_RESULT = "The LitVM server gave an invalid result"
    //A 256-bit quantity is at most 64 hex digits
    private const val MAX_QUANTITY_DIGITS = 64

    /** The result of the reply to request [id]. */
    fun result(status: Int, body: String, id: Long): JsonValue {
        if(status == 429) throw RateLimitedException("The LitVM server answered HTTP 429 (rate limited)")
        if(status / 100 != 2) throw LitVmException("The LitVM server answered HTTP $status")
        if(body.length > MAX_RESPONSE_CHARS) throw LitVmException("The LitVM server's answer is too large")
        val reply = EvmJson.parse(body, MAX_RESPONSE_CHARS) as? JsonValue.Obj ?: throw LitVmException(NOT_JSON_RPC)
        val replyId = reply["id"] as? JsonValue.Num
        if(replyId == null || replyId.text != id.toString()) throw LitVmException("The LitVM server answered another request")
        val error = reply["error"]
        if(error != null && error != JsonValue.Null) {
            if(error !is JsonValue.Obj) throw LitVmException(NOT_JSON_RPC)
            val message = (error["message"] as? JsonValue.Str)?.value ?: ""
            val shown = TextRules.singleLine(message, MAX_MESSAGE)
            //geth and Nitro report a revert with data as code 3, one without data as -32000 "execution reverted". Kept
            //narrow: any other error (a rate limit, a node fault) must not read as the contract's answer
            if(isThree(error["code"]) || message.startsWith("execution reverted")) throw CallReverted(shown.ifEmpty { "execution reverted" })
            throw LitVmException(shown.ifEmpty { "The LitVM server answered with an error" })
        }
        return reply["result"] ?: throw LitVmException(NOT_JSON_RPC)
    }

    //The number 3, however written (3, 3.0, 3e0)
    private fun isThree(code: JsonValue?): Boolean {
        val text = (code as? JsonValue.Num)?.text ?: return false
        val trimmed = if('.' in text) text.trimEnd('0').trimEnd('.') else text
        return trimmed == "3"
    }

    /** An eth_call result: "0x" and an even number of hex digits, at most [maxResultBytes] bytes. */
    fun callResult(result: JsonValue, maxResultBytes: Int): ByteArray {
        val hex = (result as? JsonValue.Str)?.value ?: throw LitVmException(INVALID_RESULT)
        if(!hex.startsWith("0x") || hex.length % 2 != 0) throw LitVmException(INVALID_RESULT)
        //Told before a digit is read or a byte allocated
        if((hex.length - 2) / 2 > maxResultBytes) throw ResultTooLarge()
        return hexBytes(hex, 2) ?: throw LitVmException(INVALID_RESULT)
    }

    /** A JSON-RPC quantity: "0x" and at most 64 hex digits ("0x" alone is zero). */
    fun quantity(result: JsonValue): UInt256 {
        val hex = (result as? JsonValue.Str)?.value ?: throw LitVmException(INVALID_RESULT)
        if(!hex.startsWith("0x") || hex.length - 2 > MAX_QUANTITY_DIGITS) throw LitVmException(INVALID_RESULT)
        val digits = if(hex.length % 2 == 0) hex.substring(2) else "0" + hex.substring(2)
        val bytes = hexBytes(digits, 0) ?: throw LitVmException(INVALID_RESULT)
        return UInt256.fromBytes(bytes) ?: throw LitVmException(INVALID_RESULT)
    }

    /** eth_getCode: "0x" and hex digits; true when there is any code. */
    fun hasCode(result: JsonValue): Boolean {
        val hex = (result as? JsonValue.Str)?.value ?: throw LitVmException(INVALID_RESULT)
        if(!hex.startsWith("0x") || hex.length % 2 != 0 || hexBytes(hex, 2) == null) throw LitVmException(INVALID_RESULT)
        return hex.length > 2
    }

    //ASCII hex digits from [from] on, an even count, or null
    private fun hexBytes(hex: String, from: Int): ByteArray? {
        val out = ByteArray((hex.length - from) / 2)
        for(i in out.indices) {
            val high = digit(hex[from + 2 * i])
            val low = digit(hex[from + 2 * i + 1])
            if(high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when(c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
