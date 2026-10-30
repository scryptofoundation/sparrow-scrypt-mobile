package com.sparrowwallet.mobile.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Host side of the air-gapped emulator run (tools/airgap-e2e/README.md). Does nothing
 * unless AIRGAP_E2E_DIR is set. Writes unsigned.psbt: a desktop-style PSBT (full previous
 * transaction, proprietary pairs) for the abandon-seed testnet wallet the phone restores.
 * When the phone's result is saved there as signed.psbt, checks that only partial
 * signatures were added and that it finalizes to exactly what direct signing produces.
 */
class AirgapE2eTest {
    private val hardened = 0x80000000L
    private val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    private val account = master.derivePath("m/84'/1'/0'")
    private val key0 = account.deriveChild(0).deriveChild(0)
    private val key1 = account.deriveChild(0).deriveChild(1)
    private val changeKey = account.deriveChild(1).deriveChild(0)
    private val destScript = Scripts.p2wpkhOutput(account.deriveChild(0).deriveChild(9).pubKey)
    private val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)
    private fun path(chain: Long, index: Long) = listOf(84L + hardened, 1L + hardened, 0L + hardened, chain, index)

    private val prevTx = Tx(2, listOf(TxInput("cc".repeat(32), 1, 0xFFFFFFFFL, ByteArray(107) { 3 })),
        listOf(TxOutput(1_500_000, Scripts.p2wpkhOutput(key0.pubKey))))
    private val inputs = listOf(
        SpendableInput(prevTx.txid(), 0, 1_500_000, key0),
        SpendableInput("bb".repeat(32), 1, 700_000, key1)
    )

    private fun unsignedPsbt(): ByteArray {
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 1_900_000, 3, changeScript)
        val base = Psbt.create(unsigned, master.fingerprint,
            pathFor = { if(it.key == key0) path(0, 0) else path(0, 1) },
            changeIndex = 1, changePubKey = changeKey.pubKey, changePath = path(1, 0))
        return PsbtFixtures.edit(base) { maps ->
            maps[0] += (byteArrayOf(0xFC.toByte(), 0x03) + "e2e".encodeToByteArray()) to byteArrayOf(1)
            maps[1].add(0, byteArrayOf(0x00) to prevTx.serialize())
            maps[4] += (byteArrayOf(0xFC.toByte(), 0x03) + "e2e".encodeToByteArray()) to byteArrayOf(2)
        }
    }

    @Test fun writeUnsignedOrCheckSigned() {
        val dir = System.getenv("AIRGAP_E2E_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val unsigned = unsignedPsbt()
        File(dir, "unsigned.psbt").writeText(Psbt.toBase64(unsigned))
        val signedFile = File(dir, "signed.psbt")
        if(!signedFile.exists()) return

        val signed = Psbt.fromBase64(signedFile.readText())
        val before = PsbtFixtures.splitMaps(unsigned)
        val after = PsbtFixtures.splitMaps(signed)
        for(m in before.indices) {
            val kept = after[m].filter { !(m in 1..2 && it.first[0].toInt() == 0x02) }
            assertEquals(before[m].map { it.first.toHex() + it.second.toHex() }, kept.map { it.first.toHex() + it.second.toHex() })
        }
        val direct = TxBuilder.build(inputs, destScript, 1_900_000, 3, changeScript)
        val final = Psbt.finalize(Psbt.parse(signed))
        assertContentEquals(direct.tx.serialize(), final.serialize())
        File(dir, "verified.txt").writeText("signed by the phone, finalizes to txid ${final.txid()}\n")
    }
}
