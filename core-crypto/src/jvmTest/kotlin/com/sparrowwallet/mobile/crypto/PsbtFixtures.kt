package com.sparrowwallet.mobile.crypto

/** The abandon-seed wallet and PSBT helpers shared by the PSBT tests. */
object PsbtFixtures {
    const val HARDENED = 0x80000000L
    val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    val account = master.derivePath("m/84'/2'/0'")
    val key0 = account.deriveChild(0).deriveChild(0)
    val key1 = account.deriveChild(0).deriveChild(1)
    val changeKey = account.deriveChild(1).deriveChild(0)
    val destKey = account.deriveChild(0).deriveChild(9)

    /** The transactions that created the two coins the fixture spends. */
    val prevA = Tx(2, listOf(TxInput("aa".repeat(32), 0, 0xFFFFFFFFL, ByteArray(0))),
        listOf(TxOutput(50_000_000, Scripts.p2wpkhOutput(key0.pubKey))))
    val prevB = Tx(2, listOf(TxInput("bb".repeat(32), 4, 0xFFFFFFFFL, ByteArray(0))),
        listOf(TxOutput(1_000, Scripts.p2wpkhOutput(destKey.pubKey)), TxOutput(30_000_000, Scripts.p2wpkhOutput(key1.pubKey))))

    val inputs = listOf(
        SpendableInput(prevA.txid(), 0, 50_000_000, key0, ScriptType.P2WPKH),
        SpendableInput(prevB.txid(), 1, 30_000_000, key1, ScriptType.P2WPKH)
    )
    val destScript = Scripts.p2wpkhOutput(destKey.pubKey)
    val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)

    fun accountPath(chain: Long, index: Long) =
        listOf(84L + HARDENED, 2L + HARDENED, 0L + HARDENED, chain, index)

    fun createPsbt(unsigned: UnsignedBuild): ByteArray = Psbt.create(
        unsigned,
        masterFingerprint = master.fingerprint,
        pathFor = { input ->
            when(input.key) {
                key0 -> accountPath(0, 0)
                key1 -> accountPath(0, 1)
                else -> error("unexpected input key")
            }
        },
        changeIndex = 1,
        changePubKey = changeKey.pubKey,
        changePath = accountPath(1, 0)
    )

    /** dest 0.6 LTC + change, from both inputs (0.8 LTC in), each with its previous transaction. */
    fun fixturePsbt(): ByteArray = edit(withoutPrevTxs()) { maps ->
        maps[1].add(0, byteArrayOf(0x00) to prevA.serialize())
        maps[2].add(0, byteArrayOf(0x00) to prevB.serialize())
    }

    /** The same spend as Passport-style desktop QRs send it: witness UTXOs only. */
    fun withoutPrevTxs(): ByteArray =
        createPsbt(TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript, params = Network.MAINNET.litecoin))

    /** The PSBT's maps in order: global, then one per input, then one per output. */
    fun splitMaps(psbt: ByteArray): List<MutableList<Pair<ByteArray, ByteArray>>> {
        val r = ByteReader(psbt)
        r.bytes(5)
        val maps = ArrayList<MutableList<Pair<ByteArray, ByteArray>>>()
        while(r.remaining() > 0) {
            val map = ArrayList<Pair<ByteArray, ByteArray>>()
            while(true) {
                val keyLen = r.varInt()
                if(keyLen == 0L) break
                map += r.bytes(keyLen.toInt()) to r.varBytes()
            }
            maps += map
        }
        return maps
    }

    fun joinMaps(maps: List<List<Pair<ByteArray, ByteArray>>>): ByteArray {
        val w = ByteWriter().bytes(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()))
        for(map in maps) {
            for((k, v) in map) w.varBytes(k).varBytes(v)
            w.u8(0)
        }
        return w.toByteArray()
    }

    /** Edits a PSBT's maps in place: map 0 is global, 1..n inputs, then outputs. */
    fun edit(psbt: ByteArray, change: (List<MutableList<Pair<ByteArray, ByteArray>>>) -> Unit): ByteArray =
        splitMaps(psbt).also(change).let(::joinMaps)

    fun derivationValue(fingerprint: ByteArray, path: List<Long>): ByteArray {
        val w = ByteWriter().bytes(fingerprint)
        for(child in path) w.u32le(child)
        return w.toByteArray()
    }
}
