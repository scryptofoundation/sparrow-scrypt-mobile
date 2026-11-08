package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaprootSigningTest {
    /** BIP341 wallet-test-vectors.json, keyPathSpending: the unsigned tx and the utxos it spends. */
    private val bip341Tx =
        "02000000097de20cbff686da83a54981d2b9bab3586f4ca7e48f57f5b55963115f3b334e9c010000000000000000d7b7cab5" +
        "7b1393ace2d064f4d4a2cb8af6def61273e127517d44759b6dafdd990000000000fffffffff8e1f583384333689228c5d28e" +
        "ac13366be082dc57441760d957275419a418420000000000fffffffff0689180aa63b30cb162a73c6d2a38b7eeda2a83ece7" +
        "4310fda0843ad604853b0100000000feffffffaa5202bdf6d8ccd2ee0f0202afbbb7461d9264a25e5bfd3c5a52ee1239e0ba" +
        "6c0000000000feffffff956149bdc66faa968eb2be2d2faa29718acbfe3941215893a2a3446d32acd0500000000000000000" +
        "00e664b9773b88c09c32cb70a2a3e4da0ced63b7ba3b22f848531bbb1d5d5f4c94010000000000000000e9aa6b8e6c9de676" +
        "19e6a3924ae25696bb7b694bb677a632a74ef7eadfd4eabf0000000000ffffffffa778eb6a263dc090464cd125c466b5a996" +
        "67720b1c110468831d058aa1b82af10100000000ffffffff0200ca9a3b000000001976a91406afd46bcdfd22ef94ac122aa1" +
        "1f241244a37ecc88ac807840cb0000000020ac9a87f5594be208f8532db38cff670c450ed2fea8fcdefcc9a663f78bab962b" +
        "0065cd1d"

    private val bip341Amounts = listOf(
            420000000L,
            462000000L,
            294000000L,
            504000000L,
            630000000L,
            378000000L,
            672000000L,
            546000000L,
            588000000L
    )

    private val bip341Scripts = listOf(
            "512053a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343",
            "5120147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3",
            "76a914751e76e8199196d454941c45d1b3a323f1433bd688ac",
            "5120e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e",
            "512091b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605",
            "00147dd65592d0ab2fe0d0257d571abf032cd9db93dc",
            "512075169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831",
            "5120712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5",
            "512077e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220"
    )

    /** (input index, hashType, expected sigHash) for the keyPathSpending inputs with hashType 0 and 1. */
    private val bip341Sighashes = listOf(
        Triple(3, 1, "bf013ea93474aa67815b1b6cc441d23b64fa310911d991e713cd34c7f5d46669"),
        Triple(4, 0, "4f900a0bae3f1446fd48490c2958b5a023228f01661cda3496a11da502a7f7ef")
    )

    /** Parses a serialized tx (segwit or legacy) into a [Tx]; witnesses and scriptSigs are dropped. */
    private fun parse(hex: String): Tx {
        val r = ByteReader(hex.hexToBytes())
        val version = r.u32le()
        var count = r.varInt()
        if(count == 0L) {
            r.bytes(1) // flag
            count = r.varInt()
        }
        val inputs = List(count.toInt()) {
            val txid = r.bytes(32).reversedArray().toHex()
            val vout = r.u32le()
            r.varBytes()
            TxInput(txid, vout, r.u32le())
        }
        val outputs = List(r.varInt().toInt()) { TxOutput(r.u64le(), r.varBytes()) }
        // the locktime is the last four bytes in both layouts
        val raw = hex.hexToBytes()
        val locktime = ByteReader(raw.copyOfRange(raw.size - 4, raw.size)).u32le()
        return Tx(version, inputs, outputs, locktime)
    }

    @Test
    fun bip341KeyPathSighashVectors() {
        val tx = parse(bip341Tx)
        val scripts = bip341Scripts.map { it.hexToBytes() }
        for((index, hashType, expected) in bip341Sighashes) {
            assertEquals(expected, tx.taprootKeyPathSighash(index, bip341Amounts, scripts, hashType).toHex(), "input $index type $hashType")
        }
        assertEquals(listOf(3 to 1, 4 to 0), bip341Sighashes.map { it.first to it.second })
        assertFailsWith<IllegalArgumentException> { tx.taprootKeyPathSighash(0, bip341Amounts, scripts, 3) }
    }

    @Test
    fun bellscoinMainnetSpendVerifies() {
        val raw =
            "020000000001047b2ee853d440302db4a27719ceb84565a480f666be9043b126d3b7f071a925620400000000ffffffff7b2e" +
            "e853d440302db4a27719ceb84565a480f666be9043b126d3b7f071a925620500000000ffffffff1d91a72f0916d531992be5" +
            "501134692bf232d5fc692d3042fdcbd3e560da08c00000000000fffffffff96a889020ce6dba1f5cf065cb21c40666077abb" +
            "b5fbad9bcb54fd5d259c243e0000000000ffffffff07d007000000000000225120a066bb7b97a348a3ec0854bee453cc7b51" +
            "77affcb179c7adee5aa3256c659bdde803000000000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7ad" +
            "ee5aa3256c659bdd80b2ef4b000000001600146c068e55f537c15a3c52004a24d3d009ef37a9c500751903000000001976a9" +
            "14b9c6d0cd855028851de6b3c138ed27aeb7d3d48788ace803000000000000225120a066bb7b97a348a3ec0854bee453cc7b" +
            "5177affcb179c7adee5aa3256c659bdde803000000000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7" +
            "adee5aa3256c659bdd4080b5c32a000000225120a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c65" +
            "9bdd0140b1c7449908249036bfe73e9e47fa849cac23877cd3a7f8e7dff324adf6e2e44625aef7957293fce08d73e962803f" +
            "03cc510a2990e47db817c2bd886138df57a60140a9d2ade7920bfddece1e3e33a4a43f9ddf933e92cca91fc0aa1406afd808" +
            "a5a6b0386743346a203ada54434c2f63dd7919cdda7563e09f9dd95de2c50a2c80d40247304402200e64e733924ff55dd1c1" +
            "4d9af36182c329058dd7bc654a44b1a5fe106573bdab022074578e53ad666d5d90b68bea52f54cab0861969a6c5983772987" +
            "015bbcacc40f83210260dc26e2487d420916e9e98f8fe35cbc9b9806da83ceaee44e7e0e73cd8162c701402db8978ee82518" +
            "327a803cf65a141dfca47fad044223bf0f76862dd33d1cfffbe1fa9015854281ff8a89b1c126cb4a85c645eed6e30c95ff99" +
            "9ddc7232a3d43d00000000"
        val tx = parse(raw)
        val program = "a066bb7b97a348a3ec0854bee453cc7b5177affcb179c7adee5aa3256c659bdd"
        val taproot = "5120$program".hexToBytes()
        val scripts = listOf(taproot, taproot, "00146c068e55f537c15a3c52004a24d3d009ef37a9c5".hexToBytes(), taproot)
        val amounts = listOf(1000L, 1000L, 1000L, 184998248400L)
        val witnessSigs = mapOf(
            0 to "b1c7449908249036bfe73e9e47fa849cac23877cd3a7f8e7dff324adf6e2e44625aef7957293fce08d73e962803f03cc510a2990e47db817c2bd886138df57a6",
            1 to "a9d2ade7920bfddece1e3e33a4a43f9ddf933e92cca91fc0aa1406afd808a5a6b0386743346a203ada54434c2f63dd7919cdda7563e09f9dd95de2c50a2c80d4",
            3 to "2db8978ee82518327a803cf65a141dfca47fad044223bf0f76862dd33d1cfffbe1fa9015854281ff8a89b1c126cb4a85c645eed6e30c95ff999ddc7232a3d43d"
        )
        assertEquals("a81aa83bfb7fe6d716593d6d7af9cef0531a71b6d62e1717b1518d7878ba4ea3", tx.txid())
        for((i, sig) in witnessSigs) {
            val hash = tx.taprootKeyPathSighash(i, amounts, scripts, 0)
            assertTrue(secpVerifySchnorr(sig.hexToBytes(), hash, program.hexToBytes()), "input $i")
            val altered = amounts.toMutableList().also { it[i] = it[i] + 1 }
            assertFalse(secpVerifySchnorr(sig.hexToBytes(), tx.taprootKeyPathSighash(i, altered, scripts, 0), program.hexToBytes()))
        }
    }

    @Test
    fun p2trSpendIsSixtyFourByteWitness() {
        val master = HDKey.fromSeed(Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
        val keys = listOf(master.derivePath("m/86'/2'/0'/0/0"), master.derivePath("m/86'/2'/0'/0/1"))
        val tx = Tx(
            2,
            listOf(TxInput("11".repeat(32), 0), TxInput("22".repeat(32), 1)),
            listOf(TxOutput(90_000, Scripts.p2trOutput(keys[0].pubKey)))
        )
        InputSigner.signAll(tx, keys, ScriptType.P2TR, listOf(50_000L, 60_000L))
        for((i, input) in tx.inputs.withIndex()) {
            assertEquals(1, input.witness.size)
            assertEquals(64, input.witness[0].size)
            assertEquals(0, input.scriptSig.size)
            val scripts = keys.map { Scripts.p2trOutput(it.pubKey) }
            val hash = tx.taprootKeyPathSighash(i, listOf(50_000L, 60_000L), scripts)
            assertTrue(secpVerifySchnorr(input.witness[0], hash, Taproot.outputKey(keys[i].pubKey)))
        }
    }

    @Test
    fun signatureForAnotherOutputKeyIsNotReturned() {
        val master = HDKey.fromSeed(Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
        val key = master.derivePath("m/86'/2'/0'/0/0")
        val other = master.derivePath("m/86'/2'/0'/0/1")
        val hash = ByteArray(32) { 7 }
        assertEquals(64, Schnorr.signForInput(hash, key.privKey, Taproot.outputKey(key.pubKey)).size)
        val e = assertFailsWith<IllegalStateException> { Schnorr.signForInput(hash, key.privKey, Taproot.outputKey(other.pubKey)) }
        assertEquals("Generated signature failed verification", e.message)
        //the untweaked internal key is not the output key either
        assertFailsWith<IllegalStateException> { Schnorr.signForInput(hash, key.privKey, Taproot.xOnly(key.pubKey)) }
    }

    @Test
    fun estimateVsizeTaproot() {
        assertEquals(111L, TxBuilder.estimateVsize(listOf(ScriptType.P2TR), listOf(34)))
    }
}
