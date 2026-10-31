package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Keys and addresses for every coin, network and script type, against drongo's vectors/coins.json. */
class CoinParityTest {
    private val master = HDKey.fromSeed(Bip39.seed(CoinVectors.file.mnemonic, ""))

    @Test fun keysAndAddressesMatchDrongoForEveryCoin() {
        for(e in CoinVectors.file.entries) {
            val p = ChainParams.of(Coin.valueOf(e.coin), Network.valueOf(e.network))
            for(t in e.scriptTypes) {
                val st = ScriptType.valueOf(t.scriptType)
                val account = master.derivePath(t.derivationPath)
                val tag = "${e.coin} ${e.network} ${t.scriptType}"
                assertEquals(t.accountXpub, account.xpub(p.xpubHeader), tag)
                assertEquals(t.receive0Wif, account.deriveChild(0).deriveChild(0).wif(p.wifVersion), tag)
                t.receiveAddresses.forEachIndexed { i, a ->
                    assertEquals(a, Addresses.forKey(account.deriveChild(0).deriveChild(i.toLong()).pubKey, st, p), "$tag r$i")
                }
                t.changeAddresses.forEachIndexed { i, a ->
                    assertEquals(a, Addresses.forKey(account.deriveChild(1).deriveChild(i.toLong()).pubKey, st, p), "$tag c$i")
                }
                val script = Addresses.toScriptPubKey(t.receiveAddresses[0], p)
                assertEquals(t.receiveAddresses[0], Addresses.fromScript(script, p), tag)
            }
        }
    }

    @Test fun dogeAddressIsValidForDingoAndRefusedForLitecoin() {
        val doge = CoinVectors.entry(Coin.DOGECOIN, Network.MAINNET).scriptTypes[0].receiveAddresses[0]
        val dingoScript = Addresses.toScriptPubKey(doge, ChainParams.of(Coin.DINGOCOIN, Network.MAINNET))
        assertContentEquals(Addresses.toScriptPubKey(doge, ChainParams.of(Coin.DOGECOIN, Network.MAINNET)), dingoScript)
        val e = assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(doge, ChainParams.of(Coin.LITECOIN, Network.MAINNET))
        }
        assertEquals("Not a Litecoin address", e.message)
    }

    @Test fun dogeAddressShowsWhichOtherCoinsShareItsEncodingForABellscoinWallet() {
        // Dogecoin P2PKH and Bellscoin P2SH are both version 30: the address decodes, but is ambiguous
        val doge = CoinVectors.entry(Coin.DOGECOIN, Network.MAINNET).scriptTypes[0].receiveAddresses[0]
        val bells = ChainParams.of(Coin.BELLSCOIN, Network.MAINNET)
        assertEquals(listOf(Coin.DOGECOIN, Coin.DINGOCOIN), Addresses.coinsSharingEncoding(doge, bells))
        val ltc = CoinVectors.entry(Coin.LITECOIN, Network.MAINNET).scriptTypes.first { it.scriptType == "P2WPKH" }.receiveAddresses[0]
        assertEquals(emptyList(), Addresses.coinsSharingEncoding(ltc, ChainParams.of(Coin.LITECOIN, Network.MAINNET)))
    }

    @Test fun litecoinBase58AddressOnTheOtherNetworkNamesIt() {
        val mainnet = CoinVectors.entry(Coin.LITECOIN, Network.MAINNET).scriptTypes.first { it.scriptType == "P2PKH" }.receiveAddresses[0]
        val e = assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey(mainnet, Network.TESTNET.litecoin) }
        assertEquals("That's a Mainnet address, but this wallet is on Testnet", e.message)
    }

    @Test fun witnessV1IsAcceptedOnlyWhereTaprootIsActive() {
        val program = ByteArray(32) { 7 }
        assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(Bech32.encodeWitness("bel", 1, program), ChainParams.of(Coin.BELLSCOIN, Network.MAINNET))
        }
        Addresses.toScriptPubKey(Bech32.encodeWitness("ltc", 1, program), ChainParams.of(Coin.LITECOIN, Network.MAINNET))
        Addresses.toScriptPubKey(Bech32.encodeWitness("tjc", 1, program), ChainParams.of(Coin.JUNKCOIN, Network.TESTNET))
    }

    @Test fun bellsSegwitAddressIsRefusedByLegacyOnlyCoins() {
        val bells = CoinVectors.entry(Coin.BELLSCOIN, Network.MAINNET).scriptTypes.first { it.scriptType == "P2WPKH" }.receiveAddresses[0]
        assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(bells, ChainParams.of(Coin.DOGECOIN, Network.MAINNET))
        }
    }
}
