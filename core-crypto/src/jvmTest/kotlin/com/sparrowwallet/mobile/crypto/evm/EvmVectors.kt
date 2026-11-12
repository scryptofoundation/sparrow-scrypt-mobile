package com.sparrowwallet.mobile.crypto.evm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The EVM vectors written by drongo's EvmVectorGeneratorTest (copy in tools/vectorgen), with the input builder its
 * "built" section describes. Every built input is checked against the length and SHA-256 the generator wrote, so a
 * builder slip cannot pass silently.
 */
object EvmVectors {
    val root: JsonObject by lazy { Json.parseToJsonElement(File("../vectors/evm.json").readText(Charsets.UTF_8)).jsonObject }

    fun list(name: String): List<JsonObject> = root[name]!!.jsonArray.map { it.jsonObject }

    fun obj(name: String): JsonObject = root[name]!!.jsonObject

    /** The text under key, or the one built from keyParts, checked against keyLength and keySha256. */
    fun input(entry: JsonObject, key: String): String {
        entry[key]?.let { return it.jsonPrimitive.content }
        val parts = entry[key + "Parts"] ?: fail("No $key or ${key}Parts in $entry")
        val text = build(parts)
        assertEquals(entry[key + "Length"]!!.jsonPrimitive.int, text.length, "built length of ${entry["label"]}")
        assertEquals(entry[key + "Sha256"]!!.jsonPrimitive.content, sha256(text), "built SHA-256 of ${entry["label"]}")
        return text
    }

    fun build(part: JsonElement): String = when(part) {
        is JsonPrimitive -> part.content
        is JsonArray -> part.joinToString("") { build(it) }
        is JsonObject -> when {
            "repeat" in part -> part["repeat"]!!.jsonPrimitive.content.repeat(part["times"]!!.jsonPrimitive.int)
            "base64" in part -> Base64.getEncoder().encodeToString(build(part["base64"]!!).toByteArray(Charsets.UTF_8))
            else -> fail("Unknown part $part")
        }
    }

    /** A decoded text value: "value", or "valueLength" and "valueSha256" for a long one. Null when refused. */
    fun assertTextValue(expected: JsonObject, actual: String?, label: String) {
        when {
            expected.flag("refused") -> assertEquals(null, actual, "$label: refused")
            "value" in expected -> assertEquals(expected["value"]!!.jsonPrimitive.content, actual, label)
            "valueLength" in expected -> {
                if(actual == null) fail("$label: expected a value, got refused")
                assertEquals(expected["valueLength"]!!.jsonPrimitive.int, actual.length, "$label: length")
                assertEquals(expected["valueSha256"]!!.jsonPrimitive.content, sha256(actual), "$label: SHA-256")
            }
            else -> fail("$label: neither value nor refused in $expected")
        }
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.boolean ?: false
}
