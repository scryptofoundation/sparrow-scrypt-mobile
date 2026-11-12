package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.crypto.toHex
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * A node's answer as the desktop's Erc721Test writes them: an HTTP status and a JSON-RPC body. ID in the body becomes
 * the request's id, as a real node echoes it.
 */
class Reply(val status: Int, val body: String) {
    companion object {
        const val ID = "\"ID\""

        fun result(hexWithout0x: String) = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"result\":\"0x$hexWithout0x\"}")

        //geth and Nitro report a revert with data as code 3
        fun revert() = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"error\":{\"code\":3,\"message\":\"execution reverted\",\"data\":\"0x2a81c9a6\"}}")

        //A revert without data comes back as a generic server error with the same message
        fun nitroRevert() = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}")

        fun nodeError() = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":$ID,\"error\":{\"code\":-32005,\"message\":\"request limit exceeded\"}}")

        fun http(status: Int) = Reply(status, "busy")
    }
}

/**
 * A fake LitVM node behind [LitVmCalls]: eth_getCode and eth_call against fake contracts, each a function of its
 * calldata hex. Every reply goes through [LitVmReplies], the code [LitVmRpc] reads replies with, so a reply here is read
 * exactly as one from a real server would be. The clock is the test's; a view from [until] refuses a call at or after
 * its deadline with [TimedOut], as [LitVmRpc] does.
 */
class FakeNode(override val network: LitVmNetwork = LitVmNetwork.LITEFORGE_TESTNET) : LitVmCalls {
    private val contracts = HashMap<EvmAddress, (String) -> Reply>()
    private val methods = Collections.synchronizedList(ArrayList<String>())
    private var ids = 0L
    @Volatile var override: Reply? = null
    @Volatile var unreachable = false
    @Volatile var now: () -> Long = { 0L }

    override val clock: () -> Long get() = { now() }

    fun contract(address: EvmAddress, answers: (String) -> Reply) {
        contracts[address] = answers
    }

    fun calls(method: String): List<String> = synchronized(methods) { methods.filter { it == method } }

    private fun reply(method: String, reply: () -> Reply): com.sparrowwallet.mobile.crypto.evm.JsonValue {
        if(unreachable) throw IOException("Connection refused")
        methods += method
        val id = ++ids
        val r = override ?: reply()
        return LitVmReplies.result(r.status, r.body.replace(Reply.ID, id.toString()), id, call = method == "eth_call")
    }

    override suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int): ByteArray {
        val result = reply("eth_call") { contracts[to]?.invoke(data.toHex()) ?: Reply.result("") }
        return LitVmReplies.callResult(result, maxResultBytes.coerceIn(0, LitVmCalls.MAX_RESULT_BYTES))
    }

    override suspend fun balance(address: EvmAddress): UInt256 = LitVmReplies.quantity(reply("eth_getBalance") { Reply.result("0") })

    override suspend fun hasCode(address: EvmAddress): Boolean =
        LitVmReplies.hasCode(reply("eth_getCode") { if(address in contracts) Reply.result("6080604052") else Reply.result("") })

    override fun until(deadline: Long): LitVmCalls = Bounded(this, deadline)

    private class Bounded(val node: LitVmCalls, val deadline: Long) : LitVmCalls {
        override val network get() = node.network
        override val clock get() = node.clock

        private fun check() {
            if(clock() >= deadline) throw TimedOut()
        }

        override suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int): ByteArray = check().let { node.call(to, data, maxResultBytes) }
        override suspend fun balance(address: EvmAddress): UInt256 = check().let { node.balance(address) }
        override suspend fun hasCode(address: EvmAddress): Boolean = check().let { node.hasCode(address) }
        override fun until(deadline: Long): LitVmCalls = Bounded(node, minOf(this.deadline, deadline))
    }

    companion object {
        val BADGE = EvmAddress.parse("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3")!!
        val HOLDER = EvmAddress.parse("0x840e41D4484041A43f9307a90389edeB3d111fb3")!!
        val OTHER = EvmAddress.parse("0x000000000000000000000000000000000000dEaD")!!

        fun word(value: Long): String = "%064x".format(value)

        fun word(value: UInt256): String = value.toWord().toHex()

        /** An ABI-encoded string result, written out here so an encoding mistake cannot agree with itself. */
        fun string(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return word(32) + word(bytes.size.toLong()) + bytes.toHex() + "00".repeat((32 - bytes.size % 32) % 32)
        }

        fun hex(address: EvmAddress): String = address.bytes.toHex()

        /** A string of these code points, so no invisible character is written into a source file. */
        fun codePoints(vararg c: Int): String = String(c, 0, c.size)

        fun resource(name: String): String = File("../vectors/evm/nft/$name").readText(Charsets.UTF_8)
    }
}
