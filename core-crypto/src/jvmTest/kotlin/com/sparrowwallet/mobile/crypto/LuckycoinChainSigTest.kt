package com.sparrowwallet.mobile.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Luckycoin's signature hash. Since its chain reached block 276500 a Luckycoin node hashes a 32-byte chain id before
 * everything else, in the legacy and in the witness v0 signature hash alike (Luckycoin Core, LuckycoinFoundation/Luckycoin
 * 8b1fa2b5: validation.cpp `GetChainSigVersion`, `pindexPrev->nHeight >= 276500`; script/interpreter.cpp `SignatureHash`,
 * `if (chainSigVersion >= CHAINSIG_VERSION_REVIVAL) { ss << CHAIN_ID; }` in both branches; script/interpreter.h line 111,
 * `static const uint256 CHAIN_ID = uint256S("0x4c55434b59434f494e5f5245564956414c");`). Every expected digest here is
 * worked out in the test itself, from that definition, and never through the code under test.
 */
class LuckycoinChainSigTest {
    private val lky = ChainParams.of(Coin.LUCKYCOIN, Network.MAINNET)
    private val lkyTest = ChainParams.of(Coin.LUCKYCOIN, Network.TESTNET)
    private val master = PsbtFixtures.master

    /**
     * `uint256S("0x4c55434b59434f494e5f5245564956414c")` written into a hash as a uint256 is: 32 bytes little-endian, so
     * the 17 bytes of the number reversed, then 15 zero bytes.
     */
    private val chainIdHex = "4c55434b59434f494e5f5245564956414c".chunked(2).reversed().joinToString("") + "00".repeat(15)

    /**
     * A Luckycoin mainnet spend, mined at height 1186493, and the coinbase it spends, read from the public explorer:
     * https://luckyscan.org/api/tx/b001140170e57c567796b95ff719bb88265d29db82f90da30b0b9bf2f7ef47e0/hex and
     * https://luckyscan.org/api/tx/4d56433a6311070d89108e69b923fb08b3ca7eda93204ce9e207d20a4d0149f0/hex
     * (https://luckyscan.org/tx/b001140170e57c567796b95ff719bb88265d29db82f90da30b0b9bf2f7ef47e0).
     */
    private val spendId = "b001140170e57c567796b95ff719bb88265d29db82f90da30b0b9bf2f7ef47e0"
    private val spendHex = "0100000001f049014d0ad207e2e94c2093da7ecab308fb23b9698e10890d0711633a43564d000000006a47304402202339708ed16a94e4bd0b1073b879c450bf6237140d6189262a2a2f53a76f4a0902204cc7e7c4b39926846a4a88698e6a78720126a5867fa7e77cda77bf7f7adb89b7012103292c1b93867b2d5351ec55125986378e86ec2d99fcb666ad41c36c6649562772feffffff0202497e06000000001976a914910d5b6d20d820f5f1d9c3f5afd4f2a19d924fbb88ac8e59b001000000001976a914d9535f662182c873deef012f4ff8dd19c399e9a688acbc1a1200"
    private val prevId = "4d56433a6311070d89108e69b923fb08b3ca7eda93204ce9e207d20a4d0149f0"
    private val prevHex = "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0603babf110101ffffffff0160153208000000001976a9146f522c0f023a1e1a45b2d5b70f65f777412b8dfb88ac00000000"

    /** A DER signature (the sighash byte already taken off) as the 64 bytes r and s. */
    private fun compact(der: ByteArray): ByteArray {
        require(der[0] == 0x30.toByte())
        var at = 2
        fun int(): ByteArray {
            require(der[at] == 0x02.toByte())
            val length = der[at + 1].toInt()
            val value = der.copyOfRange(at + 2, at + 2 + length).dropWhile { it == 0.toByte() }.toByteArray()
            at += 2 + length
            return ByteArray(32 - value.size) + value
        }
        return int() + int()
    }

    private fun verifies(der: ByteArray, digest: ByteArray, pubKey: ByteArray): Boolean = Secp256k1.verify(compact(der), digest, pubKey)

    /** The legacy SIGHASH_ALL preimage of [tx]'s input [index] spending [script]: worked out here, from Bitcoin's own rule. */
    private fun legacyPreimage(tx: Tx, index: Int, script: ByteArray): ByteArray {
        val stripped = tx.inputs.mapIndexed { i, input -> TxInput(input.prevTxid, input.vout, input.sequence, if(i == index) script else ByteArray(0)) }
        return Tx(tx.version, stripped, tx.outputs, tx.locktime).serialize(includeWitness = false) + "01000000".hexToBytes()
    }

    /** A legacy (no witness) transaction read here byte by byte: its version, inputs (with their scriptSigs) and locktime. */
    private class Legacy(val version: Long, val inputs: List<TxInput>, val scriptSigs: List<ByteArray>, val locktime: Long)

    private fun readLegacy(bytes: ByteArray): Legacy {
        var at = 0
        fun u(n: Int): Long { var v = 0L; for(k in 0 until n) v = v or ((bytes[at + k].toLong() and 0xFF) shl (8 * k)); at += n; return v }
        fun varInt(): Int { val first = bytes[at].toInt() and 0xFF; at++; require(first < 0xFD); return first }
        fun take(n: Int): ByteArray = bytes.copyOfRange(at, at + n).also { at += n }
        val version = u(4)
        val inputs = mutableListOf<TxInput>()
        val scriptSigs = mutableListOf<ByteArray>()
        repeat(varInt()) {
            val prevTxid = take(32).reversedArray().toHex()
            val vout = u(4)
            val scriptSig = take(varInt())
            inputs += TxInput(prevTxid, vout, u(4), scriptSig)
            scriptSigs += scriptSig
        }
        repeat(varInt()) { u(8); take(varInt()) }
        val locktime = u(4)
        require(at == bytes.size)
        return Legacy(version, inputs, scriptSigs, locktime)
    }

    private fun le(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((value ushr (8 * it)) and 0xFF).toByte() }

    /** The BIP143 SIGHASH_ALL preimage of a P2WPKH input: worked out here. */
    private fun bip143Preimage(tx: Tx, index: Int, pubKey: ByteArray, amount: Long): ByteArray {
        fun outpoint(input: TxInput) = input.prevTxid.hexToBytes().reversedArray() + le(input.vout, 4)
        val prevouts = sha256d(tx.inputs.fold(ByteArray(0)) { acc, it -> acc + outpoint(it) })
        val sequences = sha256d(tx.inputs.fold(ByteArray(0)) { acc, it -> acc + le(it.sequence, 4) })
        val outputs = sha256d(tx.outputs.fold(ByteArray(0)) { acc, it -> acc + le(it.value, 8) + byteArrayOf(it.script.size.toByte()) + it.script })
        val scriptCode = byteArrayOf(0x19, 0x76, 0xA9.toByte(), 0x14) + hash160(pubKey) + byteArrayOf(0x88.toByte(), 0xAC.toByte())
        val input = tx.inputs[index]
        return le(tx.version, 4) + prevouts + sequences + outpoint(input) + scriptCode + le(amount, 8) + le(input.sequence, 4) + outputs +
            le(tx.locktime, 4) + le(1, 4)
    }

    @Test
    fun theChainIdIsLuckycoinCoresUint256WrittenLittleEndian() {
        assertEquals(64, chainIdHex.length)
        assertEquals("4c415649564552" + "5f4e494f43594b43554c" + "00".repeat(15), chainIdHex)
        assertEquals("LUCKYCOIN_REVIVAL", chainIdHex.hexToBytes().take(17).reversed().toByteArray().decodeToString())
        assertEquals(chainIdHex, ChainSig.of(lky, null).prefix.toHex())
        assertEquals(0, ChainSig.NONE.prefix.size)
    }

    /** The real mainnet spend: its signature is valid over the hash with the chain id in front, and over no other. */
    @Test
    fun aRealLuckycoinSpendVerifiesOnlyWithTheChainId() {
        val spend = RawTx.parse(spendHex.hexToBytes())
        val prev = RawTx.parse(prevHex.hexToBytes())
        val legacy = readLegacy(spendHex.hexToBytes())
        assertEquals(spendId, spend.txid, "the transaction is the one the explorer names")
        assertEquals(prevId, prev.txid)
        assertEquals(prevId, legacy.inputs.single().prevTxid)
        val spent = prev.outputs[legacy.inputs.single().vout.toInt()].script
        //the scriptSig: a signature with its sighash byte (SIGHASH_ALL), then the key the spent output pays to
        val scriptSig = spend.scriptSigs.single()
        assertContentEquals(scriptSig, legacy.scriptSigs.single())
        val sig = scriptSig.copyOfRange(1, 1 + scriptSig[0].toInt())
        val pubKey = scriptSig.copyOfRange(2 + sig.size, scriptSig.size)
        assertEquals(1, sig.last().toInt(), "SIGHASH_ALL")
        assertContentEquals(Scripts.p2pkhOutput(pubKey), spent, "the key owns the coin")
        val der = sig.copyOfRange(0, sig.size - 1)
        val tx = Tx(legacy.version, legacy.inputs, spend.outputs, legacy.locktime)
        assertEquals(spendId, tx.txid(), "read back whole")

        val withChainId = sha256d(chainIdHex.hexToBytes() + legacyPreimage(tx, 0, spent))
        val without = sha256d(legacyPreimage(tx, 0, spent))
        assertTrue(verifies(der, withChainId, pubKey), "valid with the chain id in front")
        assertFalse(verifies(der, without, pubKey), "and not without it")
        //the phone's digest is that one, for the chain the spend was made on
        assertContentEquals(withChainId, tx.legacySighashAll(0, spent, ChainSig.of(lky, 1_186_492)))
        assertContentEquals(without, tx.legacySighashAll(0, spent, ChainSig.NONE))
    }

    /**
     * Which signature hash a transaction takes: Luckycoin mainnet always the chain id (its chain is far past the
     * height); Luckycoin's test network from a tip of 276500 on, and not with a tip below it or not known; no other
     * coin ever.
     */
    @Test
    fun theChainIdIsForLuckycoinFromItsHeightAndForNoOtherCoin() {
        val height = ChainSig.LUCKYCOIN_REVIVAL_HEIGHT
        assertEquals(276_500L, height)
        for(tip in listOf(null, 0L, height - 1, height, 1_186_493L)) {
            assertEquals(chainIdHex, ChainSig.of(lky, tip).prefix.toHex(), "mainnet at $tip")
        }
        assertEquals(0, ChainSig.of(lkyTest, height - 1).prefix.size, "the tip below the height: the next block's parent is below it")
        assertEquals(chainIdHex, ChainSig.of(lkyTest, height).prefix.toHex(), "the tip at the height")
        assertEquals(chainIdHex, ChainSig.of(lkyTest, height + 1).prefix.toHex())
        assertEquals(0, ChainSig.of(lkyTest, null).prefix.size, "a test chain whose tip is not known")
        assertEquals(0, ChainSig.of(lkyTest, 500).prefix.size, "a short regtest chain")
        for(coin in Coin.entries.filter { it != Coin.LUCKYCOIN }) {
            for(network in Network.entries) {
                for(tip in listOf(null, 0L, height - 1, height, 5_000_000L)) {
                    assertEquals(0, ChainSig.of(ChainParams.of(coin, network), tip).prefix.size, "$coin $network $tip")
                }
            }
        }
    }

    private fun account(params: ChainParams): HDKey = master.derivePath("m/44'/${params.coinType}'/0'")

    /** Two P2PKH coins of [params]' wallet and a send of most of them, signed by the ordinary Send's builder at [tip]. */
    private fun send(params: ChainParams, tip: Long?): Pair<Tx, List<Pair<HDKey, ByteArray>>> {
        val account = account(params)
        val keys = listOf(account.deriveChild(0).deriveChild(0), account.deriveChild(0).deriveChild(1))
        val inputs = keys.mapIndexed { i, key -> SpendableInput("${i + 1}".repeat(64), 0, 3_000_000_000, key, ScriptType.P2PKH) }
        val built = TxBuilder.build(inputs, Scripts.p2pkhOutput(account.deriveChild(0).deriveChild(9).pubKey), 4_000_000_000, 1_000,
            Scripts.p2pkhOutput(account.deriveChild(1).deriveChild(0).pubKey), params = params, chainTip = tip)
        return built.tx to built.tx.inputs.map { input ->
            val key = keys[input.prevTxid[0].digitToInt() - 1]
            key to input.scriptSig.copyOfRange(1, 1 + input.scriptSig[0].toInt()).dropLast(1).toByteArray()
        }
    }

    /** Whether every input of [tx] (from [send]) is signed over the legacy digest with [prefix] in front. */
    private fun signedOver(tx: Tx, sigs: List<Pair<HDKey, ByteArray>>, prefix: String): Boolean = sigs.withIndex().all { (i, signed) ->
        val (key, der) = signed
        verifies(der, sha256d(prefix.hexToBytes() + legacyPreimage(tx, i, Scripts.p2pkhOutput(key.pubKey))), key.pubKey)
    }

    @Test
    fun theOrdinarySendSignsLuckycoinWithTheChainIdFromItsHeight() {
        //mainnet: whatever the tip
        for(tip in listOf(1_186_493L, null)) {
            val (tx, sigs) = send(lky, tip)
            assertTrue(signedOver(tx, sigs, chainIdHex), "mainnet at $tip")
            assertFalse(signedOver(tx, sigs, ""))
        }
        //the test network, at the boundary and on a short regtest chain
        val (below, belowSigs) = send(lkyTest, ChainSig.LUCKYCOIN_REVIVAL_HEIGHT - 1)
        assertTrue(signedOver(below, belowSigs, "") && !signedOver(below, belowSigs, chainIdHex), "tip 276499")
        val (at, atSigs) = send(lkyTest, ChainSig.LUCKYCOIN_REVIVAL_HEIGHT)
        assertTrue(signedOver(at, atSigs, chainIdHex) && !signedOver(at, atSigs, ""), "tip 276500")
        val (regtest, regtestSigs) = send(lkyTest, 500)
        assertTrue(signedOver(regtest, regtestSigs, "") && !signedOver(regtest, regtestSigs, chainIdHex), "a regtest chain below the height")
        //another coin with the same script type, far past the height: no chain id
        val doge = ChainParams.of(Coin.DOGECOIN, Network.MAINNET)
        val (dogeTx, dogeSigs) = send(doge, 5_000_000)
        assertTrue(signedOver(dogeTx, dogeSigs, "") && !signedOver(dogeTx, dogeSigs, chainIdHex))
    }

    /**
     * The phone's PSBT signer (a PSBT made for this wallet on the PC, signed here, online or offline) signs Luckycoin
     * with the chain id on mainnet whatever the tip it knows, and on the test network by the tip its last sync saw.
     */
    @Test
    fun thePsbtSignerSignsLuckycoinWithTheChainIdFromItsHeight() {
        val h = PsbtFixtures.HARDENED
        for((params, tip, prefix) in listOf(Triple(lky, null, chainIdHex), Triple(lky, 1_186_493L, chainIdHex), Triple(lkyTest, null, ""),
            Triple(lkyTest, ChainSig.LUCKYCOIN_REVIVAL_HEIGHT - 1, ""), Triple(lkyTest, ChainSig.LUCKYCOIN_REVIVAL_HEIGHT, chainIdHex))) {
            val path = listOf(44L + h, params.coinType.toLong() + h, h)
            var account = master
            for(child in path) account = account.deriveChild(child)
            val keys = (0..1).map { account.deriveChild(0).deriveChild(it.toLong()) }
            val inputs = keys.mapIndexed { i, key ->
                val prev = Tx(2, listOf(TxInput("${i + 5}".repeat(64), 0, 0xFFFFFFFFL)), listOf(TxOutput(3_000_000_000, Scripts.p2pkhOutput(key.pubKey))))
                SpendableInput(prev.txid(), 0, 3_000_000_000, key, ScriptType.P2PKH, prev.serialize())
            }
            val unsigned = TxBuilder.buildUnsigned(inputs, Scripts.p2pkhOutput(account.deriveChild(0).deriveChild(9).pubKey), 4_000_000_000, 1_000,
                Scripts.p2pkhOutput(account.deriveChild(1).deriveChild(0).pubKey), params = params)
            val psbt = Psbt.create(unsigned, master.fingerprint,
                pathFor = { input -> path + listOf(0L, if(input.key.pubKey.contentEquals(keys[0].pubKey)) 0L else 1L) },
                changeIndex = 1, changePubKey = account.deriveChild(1).deriveChild(0).pubKey, changePath = path + listOf(1L, 0L))
            val tx = Psbt.finalize(Psbt.parse(PsbtSigner.sign(Psbt.parse(psbt), master, params, path, ScriptType.P2PKH, chainTip = tip)))
            val sigs = tx.inputs.map { input ->
                inputs.first { it.txid == input.prevTxid }.key to input.scriptSig.copyOfRange(1, input.scriptSig[0].toInt())
            }
            assertTrue(signedOver(tx, sigs, prefix), "${params.network} at $tip")
            assertFalse(signedOver(tx, sigs, if(prefix.isEmpty()) chainIdHex else ""), "${params.network} at $tip")
        }
    }

    /** A witness v0 input gets the chain id in front of its BIP143 preimage too, as Luckycoin Core writes it in both branches. */
    @Test
    fun aWitnessInputIsSignedWithTheChainIdToo() {
        val key = master.derivePath("m/84'/0'/0'/0/0")
        val other = master.derivePath("m/84'/0'/0'/0/1")
        val tx = Tx(2, listOf(TxInput("aa".repeat(32), 1), TxInput("bb".repeat(32), 0)),
            listOf(TxOutput(1_000_000_000, Scripts.p2pkhOutput(other.pubKey))), locktime = 1_186_000)
        InputSigner.signAll(tx, listOf(key, other), ScriptType.P2WPKH, listOf(2_000_000_000, 5_000_000), ChainSig.of(lky, 1_186_493))
        for((i, signer) in listOf(key to 2_000_000_000L, other to 5_000_000L).withIndex()) {
            val (k, amount) = signer
            val witnessSig = tx.inputs[i].witness[0].let { it.copyOfRange(0, it.size - 1) }
            assertTrue(verifies(witnessSig, sha256d(chainIdHex.hexToBytes() + bip143Preimage(tx, i, k.pubKey, amount)), k.pubKey), "input $i")
            assertFalse(verifies(witnessSig, sha256d(bip143Preimage(tx, i, k.pubKey, amount)), k.pubKey), "input $i")
        }
        //and the phone's own digest is the same one
        assertContentEquals(sha256d(chainIdHex.hexToBytes() + bip143Preimage(tx, 0, key.pubKey, 2_000_000_000)),
            tx.bip143SighashAll(0, Scripts.p2pkhScriptCode(key.pubKey), 2_000_000_000, ChainSig.of(lky, null)))
    }
}
