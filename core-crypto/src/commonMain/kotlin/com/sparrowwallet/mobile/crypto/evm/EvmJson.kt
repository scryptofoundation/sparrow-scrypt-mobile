package com.sparrowwallet.mobile.crypto.evm

/**
 * A JSON value as the strict reader ([MiniJson]) reads it, for code outside this module (the LitVM JSON-RPC client)
 * that must read JSON exactly as NFT metadata is read. Read only.
 */
sealed interface JsonValue {
    /** An object; a repeated key kept its last value, in the place of its first. */
    class Obj internal constructor(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(name: String): JsonValue? = fields[name]
    }

    class Arr internal constructor(val items: List<JsonValue>) : JsonValue

    class Str internal constructor(val value: String) : JsonValue

    /** A number, as the text BigDecimal.toPlainString() would give it ("3", "-32000", "1.50"). */
    class Num internal constructor(val text: String) : JsonValue

    class Bool internal constructor(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** The strict JSON reader's public entry: the same reader NFT metadata goes through. */
object EvmJson {
    /** The value of this text, or null when it is longer than maxChars or not strict JSON within the reader's bounds. */
    fun parse(text: String, maxChars: Int): JsonValue? {
        if(text.length > maxChars) {
            return null
        }
        val parsed = MiniJson.parse(text) ?: return null
        return convert(parsed.value)
    }

    //Nesting is at most 64 deep (the reader refuses more), so this recursion is bounded
    private fun convert(value: Any?): JsonValue = when(value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is JsonNumber -> JsonValue.Num(value.toPlainString())
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { (key, item) -> key as String to convert(item) })
        is List<*> -> JsonValue.Arr(value.map { convert(it) })
        else -> throw IllegalStateException("Not a JSON value")
    }
}
