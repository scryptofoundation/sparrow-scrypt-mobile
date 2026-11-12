package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.evm.EvmVectors.flag
import com.sparrowwallet.mobile.crypto.evm.EvmVectors.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * NFT metadata, the strict JSON reader and the one text rule, held to the vectors drongo wrote from its own classes
 * (vectors/evm.json: metadata, json, clean, constants). The clean section's "svg" fields belong to SvgSubset and are
 * checked with it.
 */
class NftMetadataVectorTest {
    @Test
    fun constantsAreTheDesktops() {
        val constants = EvmVectors.obj("constants")
        assertEquals(constants["nftMaxUriChars"]!!.jsonPrimitive.int, NftMetadata.MAX_URI_CHARS)
        assertEquals(constants["nftMaxJsonChars"]!!.jsonPrimitive.int, NftMetadata.MAX_JSON_CHARS)
        assertEquals(constants["nftMaxNameLength"]!!.jsonPrimitive.int, NftMetadata.MAX_NAME_LENGTH)
        assertEquals(constants["nftMaxDescriptionLength"]!!.jsonPrimitive.int, NftMetadata.MAX_DESCRIPTION_LENGTH)
        assertEquals(constants["nftMaxAttributeLength"]!!.jsonPrimitive.int, NftMetadata.MAX_ATTRIBUTE_LENGTH)
        assertEquals(constants["nftMaxAttributes"]!!.jsonPrimitive.int, NftMetadata.MAX_ATTRIBUTES)
        assertEquals(constants["nftMaxSvgChars"]!!.jsonPrimitive.int, NftMetadata.MAX_SVG_CHARS)
        assertEquals(constants["nftMaxRasterBytes"]!!.jsonPrimitive.int, NftMetadata.MAX_RASTER_BYTES)
        val clean = EvmVectors.obj("clean")
        assertEquals(clean.str("unicodeVersion"), TextRules.UNICODE_VERSION)
        assertEquals(clean["maxLines"]!!.jsonPrimitive.int, TextRules.MAX_LINES)
    }

    @Test
    fun theLoaderKeepsALoneSurrogate() {
        val case = EvmVectors.obj("metadata")["tokenUris"]!!.jsonArray.map { it.jsonObject }
            .single { it.str("label") == "an unpaired surrogate in a plain data URI" }
        assertTrue('\uD800' in case.str("input"))
    }

    @Test
    fun thePublicJsonEntryReadsAsTheReaderDoes() {
        val cases = EvmVectors.list("json")
        for(case in cases) {
            val input = EvmVectors.input(case, "input")
            val label = case.str("label")
            val parsed = EvmJson.parse(input, Int.MAX_VALUE)
            if(case.flag("refused")) assertEquals(null, parsed, label) else assertEquals(case.str("canonical"), canonical(assertNotNull(parsed, label)), label)
        }
        //The caller's character cap is checked before reading
        assertNotNull(EvmJson.parse("{\"a\":1}", 7))
        assertEquals(null, EvmJson.parse("{\"a\":1}", 6))
        val rpc = assertNotNull(EvmJson.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32000,\"message\":\"x\"},\"result\":null}", 1_000))
        rpc as JsonValue.Obj
        assertEquals("7", (rpc["id"] as JsonValue.Num).text)
        assertEquals("-32000", ((rpc["error"] as JsonValue.Obj)["code"] as JsonValue.Num).text)
        assertEquals(JsonValue.Null, rpc["result"])
        assertEquals(null, rpc["missing"])
    }

    @Test
    fun thePublicJsonValuesAreReadOnlyAndBounded() {
        val value = assertNotNull(EvmJson.parse("{\"a\":[1,2],\"b\":{}}", 100)) as JsonValue.Obj
        assertFalse(value.fields is MutableMap<*, *>)
        assertFailsWith<ClassCastException> { @Suppress("UNCHECKED_CAST") (value.fields as MutableMap<String, JsonValue>).clear() }
        val items = (value["a"] as JsonValue.Arr).items
        assertFalse(items is MutableList<*>)
        assertFailsWith<ClassCastException> { @Suppress("UNCHECKED_CAST") (items as MutableList<JsonValue>).clear() }
        assertEquals(2, items.size)
        //A ceiling of its own, whatever the caller allows
        assertEquals(4_000_000, EvmJson.MAX_CHARS)
        assertNotNull(EvmJson.parse("\"" + "a".repeat(3_999_998) + "\"", Int.MAX_VALUE))
        assertEquals(null, EvmJson.parse("\"" + "a".repeat(3_999_999) + "\"", Int.MAX_VALUE))
    }

    @Test
    fun jsonAsTheDesktopReadsIt() {
        val cases = EvmVectors.list("json")
        assertEquals(88, cases.size)
        for(case in cases) {
            val input = EvmVectors.input(case, "input")
            val label = case.str("label")
            val parsed = MiniJson.parse(input)
            when {
                case.flag("refused") -> assertEquals(null, parsed, label)
                "canonical" in case -> assertEquals(case.str("canonical"), canonical(assertNotNull(parsed, label).value), label)
                else -> fail("$label: neither canonical nor refused")
            }
        }
    }

    @Test
    fun tokenUrisAsTheDesktopReadsThem() {
        val cases = EvmVectors.obj("metadata")["tokenUris"]!!.jsonArray.map { it.jsonObject }
        assertEquals(176, cases.size)
        for(case in cases) {
            val input = EvmVectors.input(case, "input")
            val label = case.str("label")
            val parsed = NftMetadata.parse(input)
            when {
                case.flag("refused") -> assertEquals(null, parsed, label)
                "metadata" in case -> {
                    val expected = case["metadata"]!!.jsonObject
                    val metadata = assertNotNull(parsed, label)
                    assertEquals(expected.str("name"), metadata.name, "$label: name")
                    assertEquals(expected.str("description"), metadata.description, "$label: description")
                    val attributes = expected["attributes"]!!.jsonArray.map { it.jsonObject }
                    assertEquals(attributes.map { it.str("name") to it.str("value") }, metadata.attributes.map { it.name to it.value }, "$label: attributes")
                    assertImage(expected["image"]!!.jsonObject, metadata.image, label)
                }
                else -> fail("$label: neither metadata nor refused")
            }
        }
    }

    @Test
    fun imagesAtTheirOwnLimits() {
        val cases = EvmVectors.obj("metadata")["images"]!!.jsonArray.map { it.jsonObject }
        assertEquals(11, cases.size)
        for(case in cases) {
            assertImage(case["image"]!!.jsonObject, NftMetadata.image(EvmVectors.input(case, "input")), case.str("label"))
        }
    }

    @Test
    fun theTableIsTheDesktops() {
        val ranges = mutableListOf<List<Int>>()
        var start = -1
        for(c in 0..0x110000) {
            val member = c <= 0x10FFFF && TextRules.stripped(c)
            if(member && start < 0) {
                start = c
            } else if(!member && start >= 0) {
                ranges.add(listOf(start, c - 1))
                start = -1
            }
        }
        val expected = EvmVectors.obj("clean")["table"]!!.jsonArray.map { range -> range.jsonArray.map { it.jsonPrimitive.int } }
        assertEquals(24, expected.size)
        assertEquals(expected, ranges)
    }

    @Test
    fun everyCodePointAtTheTablesEdges() {
        val cases = EvmVectors.obj("clean")["codePoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(97, cases.size)
        for(case in cases) {
            val c = case["codePoint"]!!.jsonPrimitive.int
            val text = "a" + String(Character.toChars(c)) + "b"
            val label = "U+%04X".format(c)
            assertEquals(case["inTable"]!!.jsonPrimitive.boolean, TextRules.stripped(c), label)
            assertEquals(case.str("singleLine"), TextRules.singleLine(text, 64), "$label single line")
            assertEquals(case.str("description"), TextRules.description(text, 500), "$label description")
        }
    }

    @Test
    fun cleanedTexts() {
        val cases = EvmVectors.obj("clean")["texts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(36, cases.size)
        for(case in cases) {
            val text = EvmVectors.input(case, "input")
            val label = case.str("label")
            assertEquals(case.str("singleLine16"), TextRules.singleLine(text, 16), "$label single line 16")
            assertEquals(case.str("singleLine64"), TextRules.singleLine(text, 64), "$label single line 64")
            assertEquals(case.str("description500"), TextRules.description(text, 500), "$label description 500")
        }
    }

    /**
     * Every prefix of every metadata and JSON input reads without an exception, within a time bound. Inputs over 27,000
     * characters (12 built ones, from 80,033 to 536,022 characters) get a dense sample of prefixes instead (both ends,
     * 2,000 spread evenly, around every part boundary): every prefix of all of them would be some 10^11 character steps.
     */
    @Test
    fun nothingThrows() {
        val started = System.nanoTime()
        val metadata = EvmVectors.obj("metadata")
        val cases = metadata["tokenUris"]!!.jsonArray.map { it.jsonObject } + metadata["images"]!!.jsonArray.map { it.jsonObject } + EvmVectors.list("json")
        var prefixes = 0
        for(case in cases) {
            val input = EvmVectors.input(case, "input")
            for(end in prefixEnds(case, input.length)) {
                val prefix = input.substring(0, end)
                try {
                    //The entry points without their catch, so an exception anywhere inside fails this test
                    NftMetadata.read(prefix)
                    NftMetadata.readImage(prefix)
                    //Catches only its own Malformed signal
                    MiniJson.parse(prefix)
                    EvmJson.parse(prefix, Int.MAX_VALUE)
                } catch(e: Throwable) {
                    fail("${case.str("label")}: prefix of $end threw $e")
                }
                prefixes++
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(prefixes > 150_000, "$prefixes prefixes")
        assertTrue(seconds < 120, "took $seconds s")
    }

    private fun prefixEnds(case: JsonObject, length: Int): Set<Int> {
        if(length <= 27_000) {
            return (0..length).toSet()
        }
        val ends = sortedSetOf<Int>()
        ends.addAll(0..200)
        ends.addAll(length - 200..length)
        for(i in 1 until 2_000) {
            ends.add((length.toLong() * i / 2_000).toInt())
        }
        //Around each boundary between the input's parts
        var boundary = 0
        for(part in case["inputParts"] as JsonArray) {
            boundary += EvmVectors.build(part).length
            for(end in boundary - 3..boundary + 3) {
                if(end in 0..length) ends.add(end)
            }
        }
        return ends
    }

    private fun assertImage(expected: JsonObject, image: NftMetadata.Image, label: String) {
        when(expected.str("kind")) {
            "none" -> assertEquals(NftMetadata.Image.None, image, "$label: image")
            "svg" -> {
                val svg = image as? NftMetadata.Image.Svg ?: fail("$label: expected an SVG, got $image")
                assertEquals(expected["sourceLength"]!!.jsonPrimitive.int, svg.source.length, "$label: SVG length")
                assertEquals(expected.str("sourceSha256"), EvmVectors.sha256(svg.source), "$label: SVG SHA-256")
                expected["source"]?.let { assertEquals(it.jsonPrimitive.content, svg.source, "$label: SVG") }
            }
            "raster" -> {
                val raster = image as? NftMetadata.Image.Raster ?: fail("$label: expected a raster, got $image")
                assertEquals(expected.str("mediaType"), raster.mediaType, "$label: media type")
                assertEquals(expected["byteLength"]!!.jsonPrimitive.int, raster.bytes.size, "$label: raster length")
                assertEquals(expected.str("sha256"), EvmVectors.sha256(raster.bytes), "$label: raster SHA-256")
            }
            "hosted" -> assertEquals(NftMetadata.Image.Hosted(expected.str("host")), image, "$label: image")
            else -> fail("$label: unknown image kind $expected")
        }
    }

    private fun canonical(value: JsonValue): String = when(value) {
        JsonValue.Null -> "null"
        is JsonValue.Str -> quote(value.value)
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Num -> value.text
        is JsonValue.Obj -> value.fields.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + canonical(it.value) }
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { canonical(it) }
    }

    //As the generator writes a parsed value back: compact, in the reader's key order, numbers as toPlainString
    private fun canonical(value: Any?): String = when(value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is JsonNumber -> value.toPlainString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { quote(it.key as String) + ":" + canonical(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> fail("Not a JSON value: $value")
    }

    private fun quote(text: String): String {
        val quoted = StringBuilder("\"")
        for(c in text) {
            when {
                c == '"' || c == '\\' -> quoted.append('\\').append(c)
                c == '\n' -> quoted.append("\\n")
                c == '\t' -> quoted.append("\\t")
                c == '\r' -> quoted.append("\\r")
                c.code < 0x20 || c.code > 0x7E -> quoted.append("\\u%04x".format(c.code))
                else -> quoted.append(c)
            }
        }
        return quoted.append('"').toString()
    }
}
