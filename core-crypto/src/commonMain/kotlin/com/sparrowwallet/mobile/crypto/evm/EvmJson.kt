package com.sparrowwallet.mobile.crypto.evm

/**
 * A JSON value as the strict reader ([MiniJson]) reads it, for code outside this module (the LitVM JSON-RPC client)
 * that must read JSON exactly as NFT metadata is read. Read only.
 */
sealed interface JsonValue {
    /** An object; a repeated key kept its last value, in the place of its first. */
    class Obj internal constructor(fields: Map<String, JsonValue>) : JsonValue {
        /** Read only: not a mutable map underneath that a cast could reach. */
        val fields: Map<String, JsonValue> = ReadOnlyMap(fields)

        operator fun get(name: String): JsonValue? = fields[name]
    }

    class Arr internal constructor(items: List<JsonValue>) : JsonValue {
        /** Read only: not a mutable list underneath that a cast could reach. */
        val items: List<JsonValue> = SvgSubset.ReadOnlyList(items)
    }

    class Str internal constructor(val value: String) : JsonValue

    /** A number, as the text BigDecimal.toPlainString() would give it ("3", "-32000", "1.50"). */
    class Num internal constructor(val text: String) : JsonValue

    class Bool internal constructor(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** The strict JSON reader's public entry: the same reader NFT metadata goes through. */
object EvmJson {
    /** The most this reads, whatever a caller asks for. */
    const val MAX_CHARS = 4_000_000

    /** The value of this text, or null when it is longer than maxChars or not strict JSON within the reader's bounds. */
    fun parse(text: String, maxChars: Int): JsonValue? {
        if(text.length > maxChars || text.length > MAX_CHARS) {
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

//A view that is only a Map, so no cast reaches a mutable map; equality is the contents' (lists use SvgSubset.ReadOnlyList)
private class ReadOnlyMap<K, V>(private val inner: Map<K, V>) : Map<K, V> by inner {
    override fun equals(other: Any?): Boolean = inner == other
    override fun hashCode(): Int = inner.hashCode()
    override fun toString(): String = inner.toString()
}
