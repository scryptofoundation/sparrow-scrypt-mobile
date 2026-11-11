package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletMetaTest {
    @Test
    fun metaWithoutCoinReadsAsLitecoin() {
        val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH"}""")
        assertEquals(Coin.LITECOIN, meta.coin)
        assertEquals(Network.MAINNET, meta.network)
        assertEquals("P2WPKH", meta.scriptType)
        assertEquals(WalletLinks(), meta.links)
    }

    @Test
    fun metaRoundTripsCoin() {
        val json = WalletMeta.encode(Network.TESTNET, Coin.DOGECOIN, "P2PKH", WalletLinks(mweb = "Other"))
        assertEquals(WalletMeta.Decoded(Network.TESTNET, Coin.DOGECOIN, "P2PKH", WalletLinks(mweb = "Other")), WalletMeta.decode(json))
    }

    @Test
    fun metaWithUnknownCoinFails() {
        assertFailsWith<IllegalArgumentException> {
            WalletMeta.decode("""{"network":"mainnet","scriptType":"P2PKH","coin":"bitcoin"}""")
        }
    }

    // a meta written by the previous build: one linkedWallet name, meaning the MWEB counterpart

    @Test
    fun oldMetaOnAPublicWalletReadsAsAnMwebLink() {
        val meta = WalletMeta.decode("""{"network":"mainnet","coin":"litecoin","scriptType":"P2WPKH","linkedWallet":"Savings Private"}""")
        assertEquals(WalletLinks(mweb = "Savings Private"), meta.links)
    }

    @Test
    fun oldMetaOnAnMwebWalletReadsAsItsParent() {
        val meta = WalletMeta.decode("""{"network":"mainnet","coin":"litecoin","scriptType":"MWEB","linkedWallet":"Savings"}""")
        assertEquals(WalletLinks(parent = "Savings"), meta.links)
    }

    @Test
    fun typedLinksRoundTrip() {
        val links = WalletLinks(mweb = "A Private", taproot = "A Taproot", parent = "Top")
        val json = WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH", links)
        assertEquals(WalletMeta.Decoded(Network.MAINNET, Coin.LITECOIN, "P2WPKH", links), WalletMeta.decode(json))
        val companion = WalletLinks(parent = "A")
        assertEquals(companion, WalletMeta.decode(WalletMeta.encode(Network.TESTNET, Coin.BELLSCOIN, "P2TR", companion)).links)
    }

    private fun oldField(json: String): String? =
        Json.parseToJsonElement(json).jsonObject["linkedWallet"]?.jsonPrimitive?.content

    @Test
    fun anMwebPairStillWritesTheOldField() {
        // both sides of the pair, as the previous build reads them
        assertEquals("Savings Private", oldField(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH",
            WalletLinks(mweb = "Savings Private"))))
        assertEquals("Savings", oldField(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "MWEB",
            WalletLinks(parent = "Savings"))))
        // a public wallet with both companions: the old field holds the MWEB one
        assertEquals("Savings Private", oldField(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH",
            WalletLinks(mweb = "Savings Private", taproot = "Savings Taproot"))))
    }

    @Test
    fun aTaprootOnlyLinkWritesNoOldField() {
        assertNull(oldField(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH", WalletLinks(taproot = "Savings Taproot"))))
        assertNull(oldField(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2TR", WalletLinks(parent = "Savings"))))
    }

    @Test
    fun linksWinOverTheOldField() {
        val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH","linkedWallet":"Old","links":{"taproot":"T"}}""")
        assertEquals(WalletLinks(taproot = "T"), meta.links)
    }

    @Test
    fun emptyOrNonTextLinkNamesReadAsNull() {
        for(value in listOf("\"\"", "null", "{}", "[]", "5")) {
            val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH","links":{"mweb":$value,"taproot":"T"}}""")
            assertEquals(WalletLinks(taproot = "T"), meta.links, value)
        }
        assertEquals(WalletLinks(), WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH","linkedWallet":""}""").links)
    }

    @Test
    fun unknownLinkKeysAreIgnored() {
        val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"P2WPKH","links":{"mweb":"M","rune":"R","extra":{"a":1}}}""")
        assertEquals(WalletLinks(mweb = "M"), meta.links)
    }

    @Test
    fun malformedLinksFallBackToTheOldField() {
        for(value in listOf("5", "null", "[]", "\"x\"")) {
            val meta = WalletMeta.decode("""{"network":"mainnet","scriptType":"MWEB","linkedWallet":"Savings","links":$value}""")
            assertEquals(WalletLinks(parent = "Savings"), meta.links, value)
        }
    }

    @Test
    fun metaHoldsOnlyNamesAndWalletKind() {
        val json = WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH", WalletLinks(mweb = "M", taproot = "T", parent = "P"))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(setOf("network", "coin", "scriptType", "linkedWallet", "links").containsAll(obj.keys), obj.keys.toString())
        assertTrue(setOf("mweb", "taproot", "parent").containsAll(obj["links"]!!.jsonObject.keys))
    }

    @Test
    fun anUnlinkedWalletWritesNoLinks() {
        val obj = Json.parseToJsonElement(WalletMeta.encode(Network.MAINNET, Coin.LITECOIN, "P2WPKH", WalletLinks())).jsonObject
        assertEquals(setOf("network", "coin", "scriptType"), obj.keys)
    }

    @Test
    fun companionsAreMwebThenTaproot() {
        assertEquals(listOf("M", "T"), WalletLinks(mweb = "M", taproot = "T", parent = "P").companions)
        assertEquals(listOf("T"), WalletLinks(taproot = "T").companions)
        assertTrue(WalletLinks(parent = "P").isCompanion)
        assertEquals(false, WalletLinks(mweb = "M").isCompanion)
    }
}
