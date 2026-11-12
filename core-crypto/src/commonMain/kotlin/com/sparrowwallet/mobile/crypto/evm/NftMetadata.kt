package com.sparrowwallet.mobile.crypto.evm

/**
 * An NFT's name, description, attributes and picture, read from its token URI. Port of drongo's NftMetadata, rule for
 * rule. Only metadata carried in the URI itself is read: a data: URI holding JSON (base64 or percent-encoded) or bare
 * JSON text. Nothing is ever fetched and nothing here refers to network or file code: an https, http, ipfs or ar URI is
 * not read, and a picture hosted that way is reported by its host only.
 *
 * The URI is chosen by whoever deployed the contract, so every step is bounded and strict, sizes are checked before
 * anything is decoded, and a failure is "unreadable" (null), never an exception or a best guess. Within readable JSON a
 * field of the wrong type reads as absent. All text is cleaned by [TextRules]: the name and attributes as one line,
 * the description with its line breaks, each capped in code points. Schemes, media types and hosts are compared in
 * ASCII case only; whitespace around a URI is space, tab, line feed and carriage return only.
 *
 * JSON goes through [MiniJson] (strict, object at the top, nesting at most 64, a repeated key keeps its last value).
 * Base64 must be canonical and padded, as on-chain encoders write it.
 */
data class NftMetadata(val name: String, val description: String, val attributes: List<Attribute>, val image: Image) {
    /** One trait, shown as name and value. */
    data class Attribute(val name: String, val value: String)

    /** The picture, as far as it can be read without fetching anything. */
    sealed interface Image {
        /** No picture that can be shown. */
        data object None : Image

        /** SVG text carried in the metadata: decoded, not yet validated. */
        data class Svg(val source: String) : Image

        /** A PNG, JPEG, GIF or WebP carried in the metadata, by its declared type; its bytes still decide what it is. */
        class Raster(val mediaType: String, bytes: ByteArray) : Image {
            private val raw = bytes.copyOf()

            val bytes: ByteArray
                get() = raw.copyOf()

            override fun equals(other: Any?): Boolean = other is Raster && mediaType == other.mediaType && raw.contentEquals(other.raw)

            override fun hashCode(): Int = 31 * mediaType.hashCode() + raw.contentHashCode()

            override fun toString(): String = "Raster(mediaType=$mediaType, bytes=${raw.size})"
        }

        /** A picture hosted elsewhere, named by its host ("IPFS" and "Arweave" for those networks). It is never fetched. */
        data class Hosted(val host: String) : Image
    }

    companion object {
        /** The longest token URI read, as the ERC-721 reader's tokenURI cap allows. */
        const val MAX_URI_CHARS = 100_000
        const val MAX_JSON_CHARS = 20_000
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_ATTRIBUTE_LENGTH = 64
        const val MAX_ATTRIBUTES = 20
        const val MAX_SVG_CHARS = 100_000
        const val MAX_RASTER_BYTES = 400_000

        //Room for the largest raster in base64 (533,336 characters) and its header; only bounds a direct image() call
        private const val MAX_IMAGE_URI_CHARS = 600_000
        private const val MAX_HOST_LENGTH = 253
        //UTF-8 needs at most 3 bytes for each UTF-16 character, so more bytes than this proves too many characters
        private const val MAX_BYTES_PER_CHAR = 3
        private val RASTER_TYPES = listOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val CHARSETS = listOf("charset=utf-8", "charset=utf8", "charset=us-ascii")
        //The standard alphabet's value of each ASCII character, -1 for the rest
        private val BASE64 = IntArray(128) { -1 }.also { table ->
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { value, c -> table[c.code] = value }
        }

        private fun base64Value(c: Char): Int = if(c.code < 128) BASE64[c.code] else -1

        /** The metadata in this token URI, or null when it is unreadable or not carried in the URI. Never throws. */
        fun parse(tokenUri: String): NftMetadata? = try {
            read(tokenUri)
        } catch(e: RuntimeException) {
            null
        }

        /** As [parse], without the catch, so a test can see that nothing in here throws. */
        internal fun read(tokenUri: String): NftMetadata? {
            if(tokenUri.length > MAX_URI_CHARS) {
                return null
            }
            val uri = asciiStrip(tokenUri)
            val json = when {
                uri.startsWith("{") -> uri
                startsWithIgnoreCase(uri, "data:") -> {
                    val data = DataUri.parse(uri)
                    if(data == null || data.mediaType != "application/json") {
                        return null
                    }
                    data.text(MAX_JSON_CHARS)
                }
                else -> return null
            }
            if(json == null || json.length > MAX_JSON_CHARS) {
                return null
            }
            val fields = MiniJson.parse(json)?.value as? Map<*, *> ?: return null
            val description = fields["description"]
            return NftMetadata(text(fields["name"], MAX_NAME_LENGTH),
                if(description is String) TextRules.description(description, MAX_DESCRIPTION_LENGTH) else "",
                attributes(fields["attributes"]), image(fields))
        }

        private fun attributes(value: Any?): List<Attribute> {
            val attributes = ArrayList<Attribute>()
            if(value !is List<*>) {
                return attributes
            }
            for(item in value) {
                if(attributes.size == MAX_ATTRIBUTES) {
                    break
                }
                if(item !is Map<*, *>) {
                    continue
                }
                val shown = scalar(item["value"])
                if(shown != null) {
                    attributes.add(Attribute(text(item["trait_type"], MAX_ATTRIBUTE_LENGTH), TextRules.singleLine(shown, MAX_ATTRIBUTE_LENGTH)))
                }
            }
            return attributes
        }

        //A string, number or boolean as text; anything else has no value to show
        private fun scalar(value: Any?): String? = when(value) {
            is String -> value
            //Cutting a number at the cap would show a different number: one too long to show is not shown at all
            is JsonNumber -> value.toPlainString().takeIf { it.length <= MAX_ATTRIBUTE_LENGTH }
            is Boolean -> value.toString()
            else -> null
        }

        //Inside read, which parse wraps: the image reads here do not catch, so nothingThrows sees them
        private fun image(fields: Map<*, *>): Image {
            val uri = fields["image"]
            var image = if(uri is String) readImage(uri) else Image.None
            val data = fields["image_data"]
            if(image == Image.None && data is String) {
                image = if(asciiStrip(data).startsWith("<")) svg(data) else readImage(data)
            }
            return image
        }

        /** What an image URI can show without fetching anything. Never throws. */
        internal fun image(uri: String): Image = try {
            readImage(uri)
        } catch(e: RuntimeException) {
            Image.None
        }

        /** As [image], without the catch, so a test can see that nothing in here throws. */
        internal fun readImage(uri: String): Image {
            if(uri.length > MAX_IMAGE_URI_CHARS) {
                return Image.None
            }
            val text = asciiStrip(uri)
            return when {
                startsWithIgnoreCase(text, "data:") -> dataImage(text)
                startsWithIgnoreCase(text, "https://") || startsWithIgnoreCase(text, "http://") ->
                    host(text.substring(text.indexOf("://") + 3))?.let { Image.Hosted(it) } ?: Image.None
                startsWithIgnoreCase(text, "ipfs://") -> Image.Hosted("IPFS")
                startsWithIgnoreCase(text, "ar://") -> Image.Hosted("Arweave")
                else -> Image.None
            }
        }

        private fun dataImage(uri: String): Image {
            val data = DataUri.parse(uri) ?: return Image.None
            if(data.mediaType == "image/svg+xml") {
                return data.text(MAX_SVG_CHARS)?.let { svg(it) } ?: Image.None
            }
            if(data.mediaType in RASTER_TYPES && data.base64) {
                return base64(data.payload, MAX_RASTER_BYTES)?.let { Image.Raster(data.mediaType, it) } ?: Image.None
            }
            return Image.None
        }

        private fun svg(source: String): Image =
            if(asciiStrip(source).isEmpty() || source.length > MAX_SVG_CHARS) Image.None else Image.Svg(source)

        /**
         * The host of an http(s) URI after its scheme: what a browser would contact, so any user-info decoy before an @
         * is dropped, as is the port. Only ASCII letters, digits, dots and hyphens, at most 253; anything else is null.
         */
        private fun host(rest: String): String? {
            var end = rest.length
            for(stop in charArrayOf('/', '?', '#', '\\')) {
                val index = rest.indexOf(stop)
                if(index in 0 until end) {
                    end = index
                }
            }
            var authority = rest.substring(0, end)
            authority = authority.substring(authority.lastIndexOf('@') + 1)
            val colon = authority.lastIndexOf(':')
            if(colon >= 0) {
                val port = authority.substring(colon + 1)
                if(!port.all { it in '0'..'9' }) {
                    return null
                }
                authority = authority.substring(0, colon)
            }
            if(authority.isEmpty() || authority.length > MAX_HOST_LENGTH || !authority.all { isHostCharacter(it) }) {
                return null
            }
            return asciiLower(authority)
        }

        private fun isHostCharacter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

        /**
         * Strict base64: the standard alphabet, padded, and canonical (no stray bits), exactly as an encoder writes it.
         * The decoded size is known from the encoded length, so an over-large payload is refused before decoding.
         */
        internal fun base64(encoded: String, maxBytes: Int): ByteArray? {
            if(encoded.length % 4 != 0 || encoded.length.toLong() / 4 * 3 - 2 > maxBytes) {
                return null
            }
            val padding = when {
                encoded.endsWith("==") -> 2
                encoded.endsWith("=") -> 1
                else -> 0
            }
            val size = encoded.length / 4 * 3 - padding
            if(size < 0 || size > maxBytes) {
                return null
            }
            val out = ByteArray(size)
            var o = 0
            var i = 0
            while(i < encoded.length) {
                val last = i + 4 == encoded.length
                val a = base64Value(encoded[i])
                val b = base64Value(encoded[i + 1])
                val c = if(last && padding == 2) 0 else base64Value(encoded[i + 2])
                val d = if(last && padding >= 1) 0 else base64Value(encoded[i + 3])
                if(a < 0 || b < 0 || c < 0 || d < 0) {
                    return null
                }
                val bits = (a shl 18) or (b shl 12) or (c shl 6) or d
                out[o++] = (bits shr 16).toByte()
                if(o < size) out[o++] = (bits shr 8).toByte()
                if(o < size) out[o++] = bits.toByte()
                //Canonical: the bits a padded quad leaves over are zero
                if(last && ((padding == 1 && (bits and 0xFF) != 0) || (padding == 2 && (bits and 0xFFFF) != 0))) {
                    return null
                }
                i += 4
            }
            return out
        }

        /**
         * Percent-decoding as a data: URI defines it: %XX (two ASCII hex digits) is a byte, any other character stands
         * for its own UTF-8 bytes, and + is a plus sign. A malformed escape, an unpaired surrogate, or more than maxBytes
         * gives null.
         */
        internal fun percentDecode(text: String, maxBytes: Int): ByteArray? {
            val out = ByteSink(minOf(text.length, maxBytes) + 4)
            var i = 0
            while(i < text.length) {
                val c = text[i]
                if(c == '%') {
                    val high = if(i + 1 < text.length) hexDigit(text[i + 1]) else -1
                    val low = if(i + 2 < text.length) hexDigit(text[i + 2]) else -1
                    if(high < 0 || low < 0) {
                        return null
                    }
                    out.write((high shl 4) or low)
                    i += 3
                } else {
                    val codePoint = TextRules.codePointOf(text, i)
                    if(TextRules.loneSurrogate(codePoint)) {
                        return null
                    }
                    Utf8.encodeCodePoint(codePoint, out)
                    i += TextRules.charCount(codePoint)
                }
                if(out.size > maxBytes) {
                    return null
                }
            }
            return out.toByteArray()
        }

        private fun hexDigit(c: Char): Int = when(c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        //One line of text by TextRules, capped in code points
        private fun text(value: Any?, maxLength: Int): String = if(value is String) TextRules.singleLine(value, maxLength) else ""

        //Space, tab, line feed and carriage return removed from both ends: the JSON whitespace, not the platform's
        private fun asciiStrip(text: String): String {
            var start = 0
            var end = text.length
            while(start < end && TextRules.separator(text[start].code)) {
                start++
            }
            while(end > start && TextRules.separator(text[end - 1].code)) {
                end--
            }
            return text.substring(start, end)
        }

        //A-Z to a-z only: no other character lower-cases to ASCII
        private fun asciiLower(text: String): String {
            val lower = StringBuilder(text.length)
            for(c in text) {
                lower.append(if(c in 'A'..'Z') c + ('a' - 'A') else c)
            }
            return lower.toString()
        }

        //Compared in ASCII case only, so that no other character (the long s, the Kelvin sign) matches a prefix letter
        private fun startsWithIgnoreCase(text: String, prefix: String): Boolean =
            text.length >= prefix.length && asciiLower(text.substring(0, prefix.length)) == prefix

        /**
         * data:[media type][;parameter]*[;base64],payload. The media type is compared in lower case; a charset other than
         * UTF-8 or US-ASCII is refused, other parameters are ignored, and base64 must be the last parameter.
         */
        private class DataUri(val mediaType: String, val base64: Boolean, val payload: String) {
            /** The payload as UTF-8 text of at most maxChars characters, or null when it is not. */
            fun text(maxChars: Int): String? {
                val maxBytes = maxChars * MAX_BYTES_PER_CHAR
                val bytes = (if(base64) NftMetadata.base64(payload, maxBytes) else percentDecode(payload, maxBytes)) ?: return null
                val text = Utf8.decodeStrict(bytes) ?: return null
                return if(text.length > maxChars) null else text
            }

            companion object {
                fun parse(uri: String): DataUri? {
                    val comma = uri.indexOf(',')
                    if(comma < 0) {
                        return null
                    }
                    val parameters = uri.substring("data:".length, comma).split(";")
                    var base64 = false
                    for(i in 1 until parameters.size) {
                        val parameter = asciiLower(asciiStrip(parameters[i]))
                        if(parameter == "base64") {
                            if(i != parameters.size - 1) {
                                return null
                            }
                            base64 = true
                        } else if(parameter.startsWith("charset=") && parameter !in CHARSETS) {
                            return null
                        }
                    }
                    return DataUri(asciiLower(asciiStrip(parameters[0])), base64, uri.substring(comma + 1))
                }
            }
        }
    }
}
