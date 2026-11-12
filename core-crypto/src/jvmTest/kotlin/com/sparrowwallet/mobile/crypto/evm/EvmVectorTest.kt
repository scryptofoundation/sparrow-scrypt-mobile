package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.evm.EvmVectors.flag
import com.sparrowwallet.mobile.crypto.evm.EvmVectors.str
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.secpPublicKeyCreate
import com.sparrowwallet.mobile.crypto.secpPublicKeyUncompressed
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keccak, the account address, EIP-55 and the ABI, held to the vectors drongo wrote from its own classes
 * (vectors/evm.json: keccak, addresses, eip55, abi, tokenIds).
 */
class EvmVectorTest {
    @Test
    fun keccakMatchesTheDesktop() {
        val cases = EvmVectors.list("keccak")
        assertEquals(11, cases.size)
        for(case in cases) {
            val input = case.str("input").hexToBytes()
            assertEquals(case["length"]!!.jsonPrimitive.int, input.size)
            assertEquals(case.str("digest"), keccak256(input).toHex(), case.str("label"))
        }
    }

    @Test
    fun theSeedGivesThePublicKeyAtTheEvmPath() {
        var accounts = 0
        for(seed in EvmVectors.list("addresses")) {
            val master = HDKey.fromSeed(seed.str("seed").hexToBytes())
            for(account in seed["accounts"]!!.jsonArray.map { it.jsonObject }) {
                val path = account.str("path")
                assertEquals(EvmAccount.DERIVATION_PREFIX + account["index"]!!.jsonPrimitive.int, path)
                val uncompressed = secpPublicKeyUncompressed(master.derivePath(path).privKey)
                assertEquals(65, uncompressed.size)
                assertEquals(4, uncompressed[0].toInt())
                assertEquals(account.str("publicKey"), uncompressed.toHex(), "${seed.str("name")} $path")
                accounts++
            }
        }
        assertEquals(10, accounts)
    }

    @Test
    fun thePublicKeyGivesTheAddress() {
        var accounts = 0
        for(seed in EvmVectors.list("addresses")) {
            for(account in seed["accounts"]!!.jsonArray.map { it.jsonObject }) {
                assertEquals(account.str("address"), EvmAddress.ofPublicKey(account.str("publicKey").hexToBytes()).toString())
                accounts++
            }
        }
        assertEquals(10, accounts)
    }

    @Test
    fun theMnemonicGivesTheAddressThroughThePhonesSeedCode() {
        val seeds = EvmVectors.list("addresses")
        assertEquals(5, seeds.size)
        assertTrue(seeds.any { it.str("passphrase").isNotEmpty() })
        assertTrue(seeds.any { it.str("seedType") == "ELECTRUM" })
        for(seed in seeds) {
            val mnemonic = seed.str("mnemonic")
            val passphrase = seed.str("passphrase")
            val seedBytes = when(seed.str("seedType")) {
                "BIP39" -> Bip39.seed(mnemonic, passphrase)
                "ELECTRUM" -> ElectrumSeed.toSeed(mnemonic, passphrase)
                else -> fail("Unknown seed type in ${seed.str("name")}")
            }
            assertEquals(seed.str("seed"), seedBytes.toHex(), seed.str("name"))
            val master = HDKey.fromSeed(seedBytes)
            for(account in seed["accounts"]!!.jsonArray.map { it.jsonObject }) {
                val index = account["index"]!!.jsonPrimitive.int
                assertEquals(account.str("address"), EvmAccount.address(master, index).toString(), "${seed.str("name")} /$index")
            }
            assertEquals(seed["accounts"]!!.jsonArray[0].jsonObject.str("address"), EvmAccount.address(master).toString())
        }
        //The passphrase changes the address
        val plain = seeds.first { it.str("name") == "drongo-test-seed" }["accounts"]!!.jsonArray[0].jsonObject.str("address")
        val withPassphrase = seeds.first { it.str("name") == "drongo-test-seed-passphrase" }["accounts"]!!.jsonArray[0].jsonObject.str("address")
        assertTrue(plain != withPassphrase)
    }

    @Test
    fun theAccountGivesOnlyTheAddress() {
        val master = HDKey.fromSeed(EvmVectors.list("addresses")[0].str("seed").hexToBytes())
        val before = master.privKey.copyOf()
        EvmAccount.address(master, 0)
        assertContentEquals(before, master.privKey, "the caller's master key is untouched")
        assertFailsWith<IllegalArgumentException> { EvmAccount.address(master, -1) }
        //The object's only public function returns an EvmAddress
        val methods = EvmAccount::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue(methods.isNotEmpty())
        for(method in methods) {
            assertTrue(method.name.startsWith("address"), "unexpected public function ${method.name}")
            assertEquals(EvmAddress::class.java, method.returnType)
        }
    }

    @Test
    fun theUncompressedKeyIsTheCompressedOnesPoint() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val compressed = secpPublicKeyCreate(key)
        val uncompressed = secpPublicKeyUncompressed(key)
        assertContentEquals(compressed.copyOfRange(1, 33), uncompressed.copyOfRange(1, 33))
        assertEquals(if(uncompressed[64].toInt() and 1 == 0) 2 else 3, compressed[0].toInt())
    }

    @Test
    fun eip55AsTheDesktopForTheCanonicalShape() {
        val cases = EvmVectors.list("eip55")
        assertEquals(60, cases.size)
        for(case in cases) {
            val input = case.str("input")
            val label = "${case.str("label")}: $input"
            val kind = case.str("kind")
            assertTrue(kind in setOf("canonical", "desktopLeniency", "notAnAddress"), label)
            val parsed = EvmAddress.parse(input)
            if(case.flag("phoneValid")) {
                assertNotNull(parsed, label)
                assertEquals(case.str("address"), parsed.toString(), label)
            } else {
                assertNull(parsed, label)
            }
            //On the canonical shape the phone and the desktop agree
            if(kind == "canonical") {
                assertEquals(case["valid"]!!.jsonPrimitive.boolean, parsed != null, label)
            }
            //Every address the desktop gives reads back as itself
            if(case.flag("valid")) {
                assertEquals(case.str("address"), EvmAddress.parse(case.str("address")).toString(), label)
            }
        }
    }

    @Test
    fun selectorsMatchTheDesktop() {
        val cases = EvmVectors.obj("abi")["selectors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, cases.size)
        for(case in cases) {
            assertEquals(case.str("selector"), Abi.selector(case.str("signature")).toHex(), case.str("signature"))
        }
    }

    @Test
    fun everyCallTheDesktopMakes() {
        val cases = EvmVectors.obj("abi")["calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(19, cases.size)
        for(case in cases) {
            val args = case["args"]!!.jsonArray.map { it.jsonObject }.map { arg ->
                val (type, value) = arg.entries.single()
                val text = value.jsonPrimitive.content
                when(type) {
                    "bytes4" -> text.hexToBytes()
                    "address" -> EvmAddress.parse(text) ?: fail("Bad address $text")
                    "uint256" -> UInt256.parseDecimal(text) ?: fail("Bad uint256 $text")
                    else -> fail("Unknown argument type $type")
                }
            }
            assertEquals(case.str("data"), Abi.call(case.str("signature"), *args.toTypedArray()).toHex(), "${case.str("signature")} $args")
        }
    }

    @Test
    fun callRefusesArgumentsItDoesNotKnow() {
        for(arg in listOf<Any>("text", 5, 5L, ByteArray(5), ByteArray(0), ByteArray(33), BigInteger.ONE, 1.0, listOf(1))) {
            assertFailsWith<IllegalArgumentException>(arg.toString()) { Abi.call("f(uint256)", arg) }
        }
    }

    @Test
    fun boolResults() {
        val cases = results("bools", 9)
        for(case in cases) {
            val value = Abi.bool(case.str("result").hexToBytes())
            if(case.flag("refused")) assertNull(value, case.str("label")) else assertEquals(case["value"]!!.jsonPrimitive.boolean, value, case.str("label"))
        }
    }

    @Test
    fun uintResults() {
        val cases = results("uints", 9)
        for(case in cases) {
            val value = Abi.uint(case.str("result").hexToBytes())
            if(case.flag("refused")) assertNull(value, case.str("label")) else assertEquals(case.str("value"), value.toString(), case.str("label"))
        }
    }

    @Test
    fun addressResults() {
        val cases = results("addresses", 8)
        for(case in cases) {
            val value = Abi.address(case.str("result").hexToBytes())
            if(case.flag("refused")) assertNull(value, case.str("label")) else assertEquals(case.str("value"), value.toString(), case.str("label"))
        }
    }

    @Test
    fun stringResultsAtBothLimits() {
        val cases = results("strings", 50)
        for(case in cases) {
            val result = EvmVectors.input(case, "result").hexToBytes()
            val label = case.str("label")
            EvmVectors.assertTextValue(case["limit4096"]!!.jsonObject, Abi.string(result), "$label (4,096)")
            EvmVectors.assertTextValue(case["limit100000"]!!.jsonObject, Abi.string(result, 100_000), "$label (100,000)")
        }
    }

    @Test
    fun noDecodeThrowsOnAnyPrefixOfAResult() {
        for(section in listOf("bools", "uints", "addresses", "strings")) {
            for(case in EvmVectors.obj("abi")[section]!!.jsonArray.map { it.jsonObject }) {
                val result = EvmVectors.input(case, "result").hexToBytes()
                val ends = if(result.size <= 1_024) 0..result.size else (0..300) + (result.size - 300..result.size)
                for(end in ends) {
                    val prefix = result.copyOf(end)
                    Abi.bool(prefix)
                    Abi.uint(prefix)
                    Abi.address(prefix)
                    Abi.string(prefix)
                    Abi.string(prefix, 100_000)
                }
            }
        }
        //Word indexes far past the end, or negative, are refused rather than overflowing
        val word = ByteArray(32)
        assertNull(Abi.uint(word, Int.MAX_VALUE))
        assertNull(Abi.uint(word, -1))
        assertNull(Abi.address(word, 1))
        assertNull(Abi.bool(word, 67_108_864))
    }

    @Test
    fun vaultBadgeTokenIds() {
        val cases = EvmVectors.list("tokenIds")
        assertEquals(15, cases.size)
        for(case in cases) {
            val owner = EvmAddress.parse(case.str("owner")) ?: fail("Bad owner")
            val id = UInt256.fromBytes(owner.bytes)!!.shl8Or(case["world"]!!.jsonPrimitive.int)
            assertNotNull(id)
            val label = "${case.str("owner")} world ${case["world"]}"
            assertEquals(case.str("decimal"), id.toString(), label)
            assertEquals(case.str("hex"), id.toHex(), label)
            assertEquals(case.str("word"), id.toWord().toHex(), label)
            assertEquals(id, UInt256.parseDecimal(case.str("decimal")))
            assertEquals(id, UInt256.fromBytes(case.str("word").hexToBytes()))
        }
    }

    @Test
    fun uint256AgreesWithBigInteger() {
        val max = BigInteger.TWO.pow(256) - BigInteger.ONE
        val values = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(Long.MAX_VALUE), BigInteger.TWO.pow(64),
            BigInteger.TWO.pow(255), max, BigInteger("192999610039298982820587812382159909390890047615745"))
        for(value in values) {
            val parsed = UInt256.parseDecimal(value.toString()) ?: fail("Refused $value")
            assertEquals(value.toString(), parsed.toString())
            assertEquals(value.toString(16), parsed.toHex())
            assertEquals(value.bitLength(), parsed.bitLength)
            val word = value.toByteArray().let { raw -> ByteArray(32).also { raw.copyInto(it, 32 - minOf(raw.size, 32), maxOf(0, raw.size - 32)) } }
            assertContentEquals(word, parsed.toWord())
            for(other in values) {
                assertEquals(value.compareTo(other).coerceIn(-1, 1), parsed.compareTo(UInt256.parseDecimal(other.toString())!!).coerceIn(-1, 1))
            }
        }
        assertEquals(UInt256.of(4441), UInt256.parseDecimal("4441"))
        for(bad in listOf("", "-1", "+1", " 1", "1 ", "1a", "0x10", (max + BigInteger.ONE).toString(), "1".repeat(100), "١")) {
            assertNull(UInt256.parseDecimal(bad), bad)
        }
        assertEquals("0", UInt256.parseDecimal("000")!!.toString())
        assertNull(UInt256.fromBytes(ByteArray(33) { 1 }))
        assertEquals(UInt256.MAX, UInt256.fromBytes(ByteArray(33) { if(it == 0) 0 else -1 }))
        assertNull(UInt256.MAX.shl8Or(1))
        assertFailsWith<IllegalArgumentException> { UInt256.ZERO.shl8Or(256) }
        assertFailsWith<IllegalArgumentException> { UInt256.of(-1) }
    }

    @Test
    fun evmCommonCodeUsesNoPlatformClasses() {
        val dir = File("src/commonMain/kotlin/com/sparrowwallet/mobile/crypto/evm")
        val files = dir.listFiles { file -> file.name.endsWith(".kt") }!!.toList()
        assertTrue(files.size >= 5)
        for(file in files) {
            val text = file.readText()
            for(banned in listOf("java.", "javax.", "android.", "Charsets", "codePointAt", "Character.", "String.format", ".lowercase(", ".uppercase(",
                    ".trim(", "isWhitespace", "isLetter", "isDigit", "Socket", "URL", "URI(", "File(", "\u2014")) {
                assertTrue(banned !in text, "${file.name} uses $banned")
            }
            //Nothing from outside this module: no network, file or platform code
            for(line in text.lines().filter { it.startsWith("import ") }) {
                assertTrue(line.startsWith("import com.sparrowwallet.mobile.crypto."), "${file.name}: $line")
            }
        }
    }

    private fun results(name: String, count: Int) = EvmVectors.obj("abi")[name]!!.jsonArray.map { it.jsonObject }.also { cases ->
        assertEquals(count, cases.size)
        for(case in cases) {
            assertTrue(case.flag("refused") || "value" in case || "limit4096" in case, "neither value nor refused: $case")
        }
    }
}
