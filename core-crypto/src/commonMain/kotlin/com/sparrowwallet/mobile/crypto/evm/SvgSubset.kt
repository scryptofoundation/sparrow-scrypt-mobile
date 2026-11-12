package com.sparrowwallet.mobile.crypto.evm

/**
 * A strict subset of SVG, read into a small immutable drawing model. Port of drongo's SvgSubset, check for check, so the
 * phone accepts and refuses the same files with the same reason word and describes each accepted one with the same
 * lines (vectors/evm/svg). drongo's header comment is the contract; in short:
 *
 * An NFT's picture is chosen by whoever deployed the contract, so only plain shapes with plain paint and transforms are
 * read. Anything outside the subset refuses the whole file ([parse] is null and the caller shows a placeholder): nothing
 * is repaired and nothing is drawn in part. The reader is hand-written, uses no XML library and no regular expression,
 * never recurses and is bounded everywhere: at most 100,000 UTF-16 characters (checked first), 2,000 elements, 32 levels
 * of nesting, 10,000 path segments or points, 16 transforms per attribute, every entry of every composed matrix within
 * 1e9, viewBox and width or height sides from 1 to 4,096, font size in (0, 4096], text of at most 200 code points after
 * cleaning by [TextRules]' fixed table.
 *
 * Numbers are tokens of sign, digits, an optional fraction and an optional exponent of at most 3 digits, at most 64
 * characters, converted as the desktop converts them (Double.parseDouble there, the platform's correctly rounded
 * toDouble here, on a token already validated as plain ASCII) and at most 1,000,000 in magnitude. Rotation uses exact
 * values at 0, 90, 180 and 270 degrees and the platform's cos and sin otherwise (StrictMath on the desktop): the two
 * may differ in the last bit, which the stable form's six decimals hide.
 *
 * Each shape exposes one placement, its composed matrix {a, b, c, d, e, f} (x' = a x + c y + e, y' = b x + d y + f):
 * the viewBox translation, then each ancestor's transforms, then its own, outermost first. The list it came from is not
 * kept.
 *
 * Stable text form ([describe]): "size W H", then one line per shape, numbers as the exact binary value rounded half-even
 * to six decimals, trailing zeros removed, "0" for zero (see [number]).
 */
object SvgSubset {
    const val MAX_CHARS = 100_000
    const val MAX_ELEMENTS = 2_000
    const val MAX_DEPTH = 32
    const val MAX_SIDE = 4_096.0
    const val MIN_SIDE = 1.0
    const val MAX_FONT_SIZE = 4_096.0
    const val MAX_MAGNITUDE = 1_000_000.0
    const val MAX_PATH_SEGMENTS = 10_000
    const val MAX_POINTS = 10_000
    const val MAX_TRANSFORMS = 16
    const val MAX_COMPOSED = 1_000_000_000.0
    const val MAX_TEXT = 200
    internal const val MAX_NUMBER_CHARS = 64
    internal const val MAX_NAME_CHARS = 64

    private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
    private const val XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
    private const val NONE = -1

    private val SHAPES = setOf("rect", "circle", "ellipse", "line", "polyline", "polygon", "path")
    private val ATTRIBUTES: Map<String, Set<String>> = mapOf(
        "svg" to attributes(true, true, "viewBox", "width", "height", "version", "shape-rendering"),
        "g" to attributes(true, true, "transform", "shape-rendering"),
        "rect" to attributes(true, false, "transform", "shape-rendering", "x", "y", "width", "height", "rx", "ry"),
        "circle" to attributes(true, false, "transform", "shape-rendering", "cx", "cy", "r"),
        "ellipse" to attributes(true, false, "transform", "shape-rendering", "cx", "cy", "rx", "ry"),
        "line" to attributes(true, false, "transform", "shape-rendering", "x1", "y1", "x2", "y2"),
        "polyline" to attributes(true, false, "transform", "shape-rendering", "points"),
        "polygon" to attributes(true, false, "transform", "shape-rendering", "points"),
        "path" to attributes(true, false, "transform", "shape-rendering", "d"),
        "text" to attributes(true, true, "transform", "x", "y"),
        "title" to attributes(false, false),
        "desc" to attributes(false, false))

    private val COLOUR_NAMES = mapOf("black" to 0x000000, "silver" to 0xC0C0C0, "gray" to 0x808080, "white" to 0xFFFFFF, "maroon" to 0x800000,
        "red" to 0xFF0000, "purple" to 0x800080, "fuchsia" to 0xFF00FF, "green" to 0x008000, "lime" to 0x00FF00, "olive" to 0x808000,
        "yellow" to 0xFFFF00, "navy" to 0x000080, "blue" to 0x0000FF, "teal" to 0x008080, "aqua" to 0x00FFFF)

    private val MONOSPACE_FAMILIES = setOf("monospace", "ui-monospace", "courier", "courier new", "consolas", "menlo", "monaco", "lucida console",
        "dejavu sans mono", "liberation mono", "roboto mono", "sf mono", "source code pro", "fira code", "fira mono", "jetbrains mono",
        "ibm plex mono", "andale mono")
    private val OTHER_FAMILIES = setOf("serif", "sans-serif", "system-ui", "cursive", "fantasy", "ui-serif", "ui-sans-serif", "arial", "helvetica",
        "helvetica neue", "verdana", "tahoma", "trebuchet ms", "georgia", "times", "times new roman", "segoe ui", "roboto", "inter", "impact")
    private val SHAPE_RENDERING = setOf("auto", "optimizespeed", "crispedges", "geometricprecision")
    private val VERSIONS = setOf("1.0", "1.1", "2.0")

    // ---------------- The model ----------------

    /** The drawing's size (from the viewBox, else width and height) and its shapes in document order. */
    class Drawing internal constructor(val width: Double, val height: Double, shapes: List<Shape>) {
        val shapes: List<Shape> = ReadOnlyList(shapes)
    }

    /** A colour as 0xAARRGGBB; a shape's paint is null when it is none. */
    data class Paint(val argb: Int)

    /** An affine matrix {a, b, c, d, e, f} in SVG order: (x, y) to (a x + c y + e, b x + d y + f). */
    data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        fun toArray(): DoubleArray = doubleArrayOf(a, b, c, d, e, f)

        internal companion object {
            fun of(m: DoubleArray): Matrix = Matrix(m[0], m[1], m[2], m[3], m[4], m[5])
        }
    }

    /** A shape's resolved paint and its one placement, the composed matrix (every entry finite and within MAX_COMPOSED). */
    class Style internal constructor(val fill: Paint?, val stroke: Paint?, val strokeWidth: Double, val opacity: Double, val composedMatrix: Matrix) {
        override fun equals(other: Any?): Boolean = other is Style && fill == other.fill && stroke == other.stroke &&
            strokeWidth.compareTo(other.strokeWidth) == 0 && opacity.compareTo(other.opacity) == 0 && composedMatrix == other.composedMatrix

        override fun hashCode(): Int = ((fill.hashCode() * 31 + stroke.hashCode()) * 31 + strokeWidth.hashCode()) * 31 + opacity.hashCode() * 31 +
            composedMatrix.hashCode()

        override fun toString(): String = "Style(fill=$fill, stroke=$stroke, strokeWidth=$strokeWidth, opacity=$opacity, matrix=$composedMatrix)"
    }

    sealed interface Shape {
        val style: Style
        val fill: Paint? get() = style.fill
        val stroke: Paint? get() = style.stroke
        val strokeWidth: Double get() = style.strokeWidth
        val opacity: Double get() = style.opacity

        /** The one matrix to draw this shape with. */
        val composedMatrix: Matrix get() = style.composedMatrix
    }

    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double, val rx: Double, val ry: Double, override val style: Style) : Shape

    data class Circle(val cx: Double, val cy: Double, val r: Double, override val style: Style) : Shape

    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double, override val style: Style) : Shape

    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val style: Style) : Shape

    /** Points as x0, y0, x1, y1, ...; closed for a polygon. */
    class Polyline internal constructor(points: DoubleArray, val closed: Boolean, override val style: Style) : Shape {
        private val coords = points.copyOf()

        val pointCount: Int get() = coords.size / 2

        /** A copy of the coordinates. */
        fun points(): DoubleArray = coords.copyOf()

        fun x(i: Int): Double = coords[2 * i]

        fun y(i: Int): Double = coords[2 * i + 1]

        override fun equals(other: Any?): Boolean = other is Polyline && closed == other.closed && coords.contentEquals(other.coords) && style == other.style

        override fun hashCode(): Int = (coords.contentHashCode() * 31 + closed.hashCode()) * 31 + style.hashCode()

        override fun toString(): String = "Polyline(points=${coords.size / 2}, closed=$closed, style=$style)"
    }

    class PathShape internal constructor(segments: List<Segment>, override val style: Style) : Shape {
        val segments: List<Segment> = ReadOnlyList(segments)

        override fun equals(other: Any?): Boolean = other is PathShape && segments == other.segments && style == other.style

        override fun hashCode(): Int = segments.hashCode() * 31 + style.hashCode()

        override fun toString(): String = "PathShape(segments=${segments.size}, style=$style)"
    }

    enum class Anchor { START, MIDDLE, END }

    data class Text(val x: Double, val y: Double, val size: Double, val anchor: Anchor, val bold: Boolean, val monospace: Boolean, val content: String,
                    override val style: Style) : Shape

    /** Path segments, all in absolute coordinates. */
    sealed interface Segment

    data class MoveTo(val x: Double, val y: Double) : Segment

    data class LineTo(val x: Double, val y: Double) : Segment

    data class CubicTo(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : Segment

    data class QuadTo(val x1: Double, val y1: Double, val x: Double, val y: Double) : Segment

    data class ArcTo(val rx: Double, val ry: Double, val rotation: Double, val largeArc: Boolean, val sweep: Boolean, val x: Double, val y: Double) : Segment

    data object Close : Segment

    /** A list no caller can change (not a MutableList, so a cast cannot reach the storage). Also used by NftPicturePlan. */
    internal class ReadOnlyList<T>(items: List<T>) : AbstractList<T>() {
        private val items: Array<Any?> = items.toTypedArray<Any?>()

        override val size: Int get() = items.size

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): T = items[index] as T
    }

    /** Why an input was refused: reason is one word (element, attribute, value, number, ...), the same as the desktop's. */
    internal class Refused(val reason: String, detail: String) : Exception("$reason: $detail")

    /** The drawing, or null when anything in the input is outside the subset (show a placeholder). Never throws. */
    fun parse(svg: String): Drawing? = try {
        read(svg)
    } catch(e: Exception) {
        //A failure other than Refused would be a bug; the tests run read() and fail on one, the app gets a placeholder
        null
    }

    internal fun read(svg: String?): Drawing {
        if(svg == null || svg.length > MAX_CHARS) {
            throw Refused("length", "missing or longer than $MAX_CHARS characters")
        }
        return Reader(svg).document()
    }

    // ---------------- The XML reader ----------------

    private class State(val fill: Int, val stroke: Int, val strokeWidth: Double, val fillOpacity: Double, val strokeOpacity: Double,
                        val opacity: Double, val composed: DoubleArray, val monospace: Boolean, val fontSize: Double, val bold: Boolean,
                        val anchor: Anchor) {
        fun style(): Style = Style(paint(fill, fillOpacity), paint(stroke, strokeOpacity), strokeWidth, opacity, Matrix.of(composed))

        private fun paint(rgb: Int, opacity: Double): Paint? =
            if(rgb == NONE) null else Paint(((opacity * 255).roundToIntAsJava() shl 24) or rgb)
    }

    private class Frame(val name: String, val state: State, holdsText: Boolean, val x: Double, val y: Double) {
        val text: StringBuilder? = if(holdsText) StringBuilder() else null
    }

    private class Reader(private val s: String) {
        private val n = s.length
        private var pos = 0
        private var elements = 0
        private var rootDone = false
        private var width = 0.0
        private var height = 0.0
        private val stack = ArrayList<Frame>()
        private val shapes = ArrayList<Shape>()

        private fun peekFrame(): Frame? = if(stack.isEmpty()) null else stack[stack.size - 1]

        fun document(): Drawing {
            checkCharacters()
            if(s.startsWith("<?xml") && n > 5 && isSpace(s[5])) {
                declaration()
            }
            while(pos < n) {
                if(s[pos] != '<') {
                    characterData()
                } else if(s.startsWith("<!--", pos)) {
                    comment()
                } else if(s.startsWith("<![CDATA[", pos)) {
                    throw Refused("cdata", "CDATA section")
                } else if(s.startsWith("<!", pos)) {
                    throw Refused("doctype", "DOCTYPE or other declaration")
                } else if(s.startsWith("<?", pos)) {
                    throw Refused("pi", "processing instruction")
                } else if(s.startsWith("</", pos)) {
                    endTag()
                } else if(rootDone) {
                    throw Refused("content", "an element after the root")
                } else {
                    startTag()
                }
            }
            if(!rootDone) {
                throw if(stack.isEmpty()) Refused("content", "no root element") else Refused("syntax", "unclosed element")
            }
            return Drawing(width, height, shapes)
        }

        private fun checkCharacters() {
            var i = 0
            while(i < n) {
                val c = s[i].code
                if(c < 0x20) {
                    if(c != 0x09 && c != 0x0A && c != 0x0D) {
                        throw Refused("char", "control character")
                    }
                } else if(c in 0xD800..0xDBFF) {
                    if(i + 1 >= n || s[i + 1].code !in 0xDC00..0xDFFF) {
                        throw Refused("char", "unpaired surrogate")
                    }
                    i++
                } else if(c in 0xDC00..0xDFFF || c == 0xFFFE || c == 0xFFFF) {
                    throw Refused("char", "not an XML character")
                }
                i++
            }
        }

        private fun declaration() {
            val end = s.indexOf("?>")
            if(end < 0) {
                throw Refused("syntax", "unterminated XML declaration")
            }
            val fields = arrayOf("version", "encoding", "standalone")
            var i = 5
            var field = 0
            while(true) {
                val spaces = i
                while(i < end && isSpace(s[i])) {
                    i++
                }
                if(i == end) {
                    break
                }
                var found = -1
                var f = field
                while(f < fields.size && i > spaces) {
                    if(s.startsWith(fields[f] + "=", i)) {
                        found = f
                        break
                    }
                    f++
                }
                if(found < 0 || (field == 0 && found != 0)) {
                    throw Refused("pi", "malformed XML declaration")
                }
                i += fields[found].length + 1
                val quote = if(i < end) s[i] else 0.toChar()
                val close = if(quote == '"' || quote == '\'') s.indexOf(quote, i + 1) else -1
                if(close < 0 || close > end || close == i + 1) {
                    throw Refused("pi", "malformed XML declaration")
                }
                for(k in i + 1 until close) {
                    val c = s[k]
                    if(!isAsciiLetterOrDigit(c) && c != '.' && c != '_' && c != '-') {
                        throw Refused("pi", "malformed XML declaration")
                    }
                }
                if(found == 1 && asciiLower(s.substring(i + 1, close)) != "utf-8") {
                    throw Refused("pi", "an encoding other than UTF-8")
                }
                i = close + 1
                field = found + 1
            }
            if(field == 0) {
                throw Refused("pi", "XML declaration without a version")
            }
            pos = end + 2
        }

        private fun comment() {
            val dashes = s.indexOf("--", pos + 4)
            if(dashes < 0 || !s.startsWith("-->", dashes)) {
                throw Refused("syntax", "unterminated comment or \"--\" inside one")
            }
            pos = dashes + 3
        }

        private fun characterData() {
            var end = s.indexOf('<', pos)
            if(end < 0) {
                end = n
            }
            val top = peekFrame()
            val text = top?.text
            if(text == null) {
                for(i in pos until end) {
                    if(!isSpace(s[i])) {
                        throw Refused("content", "text outside a text element")
                    }
                }
                pos = end
                return
            }
            while(pos < end) {
                val c = s[pos]
                if(c == '>' && pos >= 2 && s[pos - 1] == ']' && s[pos - 2] == ']') {
                    throw Refused("syntax", "\"]]>\" in text")
                }
                if(c == '&') {
                    pos = entity(pos, text)
                } else {
                    text.append(c)
                    pos++
                }
            }
        }

        /** Decodes the reference starting with the '&' at index at into out, and returns the index after its ';'. */
        private fun entity(at: Int, out: StringBuilder): Int {
            var semicolon = -1
            var i = at + 1
            while(i < n && i < at + 12) {
                if(s[i] == ';') {
                    semicolon = i
                    break
                }
                i++
            }
            if(semicolon < 0) {
                throw Refused("entity", "unterminated or unknown reference")
            }
            when(val name = s.substring(at + 1, semicolon)) {
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "amp" -> out.append('&')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> TextRules.appendCodePoint(out, characterReference(name))
            }
            return semicolon + 1
        }

        private fun characterReference(name: String): Int {
            val hex = name.startsWith("#x")
            val digits = if(hex) name.substring(2) else if(name.startsWith("#")) name.substring(1) else ""
            if(digits.isEmpty() || digits.length > (if(hex) 6 else 7)) {
                throw Refused("entity", "unknown entity")
            }
            var code = 0
            for(ch in digits) {
                val digit = if(hex) hexDigit(ch) else if(ch in '0'..'9') ch - '0' else -1
                if(digit < 0) {
                    throw Refused("entity", "malformed character reference")
                }
                code = code * (if(hex) 16 else 10) + digit
            }
            val legal = code == 0x9 || code == 0xA || code == 0xD || (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD) || (code in 0x10000..0x10FFFF)
            if(!legal) {
                throw Refused("entity", "character reference to an illegal character")
            }
            return code
        }

        private fun name(): String {
            val start = pos
            if(pos < n && (isAsciiLetter(s[pos]) || s[pos] == '_')) {
                pos++
                while(pos < n && (isAsciiLetterOrDigit(s[pos]) || ".-_:".indexOf(s[pos]) >= 0)) {
                    pos++
                }
            }
            if(pos == start || pos - start > MAX_NAME_CHARS) {
                throw Refused("syntax", "malformed name")
            }
            return s.substring(start, pos)
        }

        private fun skipSpaces() {
            while(pos < n && isSpace(s[pos])) {
                pos++
            }
        }

        private fun startTag() {
            pos++
            val name = name()
            val parent = peekFrame()
            if(name.indexOf(':') >= 0) {
                throw Refused("namespace", "prefixed element $name")
            }
            if(name !in ATTRIBUTES) {
                throw Refused("element", name)
            }
            val allowed = if(parent == null) {
                name == "svg"
            } else if(parent.name == "svg" || parent.name == "g") {
                name != "svg"
            } else if(parent.name in SHAPES) {
                name == "title" || name == "desc"
            } else {
                false
            }
            if(!allowed) {
                throw Refused("element", name + (if(parent == null) " as the root" else " inside " + parent.name))
            }
            if(++elements > MAX_ELEMENTS) {
                throw Refused("elements", "more than $MAX_ELEMENTS elements")
            }
            if(stack.size >= MAX_DEPTH) {
                throw Refused("nesting", "nested deeper than $MAX_DEPTH")
            }

            //Insertion-ordered, as the desktop's LinkedHashMap
            val attributes = LinkedHashMap<String, String>()
            val selfClosing: Boolean
            while(true) {
                val before = pos
                skipSpaces()
                if(pos >= n) {
                    throw Refused("syntax", "unterminated tag")
                }
                val c = s[pos]
                if(c == '>') {
                    pos++
                    selfClosing = false
                    break
                }
                if(c == '/') {
                    if(pos + 1 < n && s[pos + 1] == '>') {
                        pos += 2
                        selfClosing = true
                        break
                    }
                    throw Refused("syntax", "malformed tag end")
                }
                if(pos == before) {
                    throw Refused("syntax", "no space before an attribute")
                }
                val attribute = name()
                checkAttributeName(name, attribute, parent == null)
                skipSpaces()
                if(pos >= n || s[pos] != '=') {
                    throw Refused("syntax", "attribute without a value")
                }
                pos++
                skipSpaces()
                val value = attributeValue()
                if(attributes.put(attribute, value) != null) {
                    throw Refused("syntax", "duplicate attribute $attribute")
                }
            }

            element(name, attributes, parent)
            if(selfClosing) {
                close()
            }
        }

        private fun checkAttributeName(element: String, attribute: String, root: Boolean) {
            if(attribute == "xmlns" || attribute.startsWith("xmlns:")) {
                if(!root || !(attribute == "xmlns" || attribute == "xmlns:xlink")) {
                    throw Refused("namespace", attribute)
                }
                return
            }
            if(attribute.indexOf(':') >= 0) {
                throw Refused("namespace", attribute)
            }
            if(attribute !in ATTRIBUTES.getValue(element)) {
                throw Refused("attribute", "$attribute on $element")
            }
        }

        private fun attributeValue(): String {
            if(pos >= n || (s[pos] != '"' && s[pos] != '\'')) {
                throw Refused("syntax", "unquoted attribute value")
            }
            val quote = s[pos++]
            val value = StringBuilder()
            while(true) {
                if(pos >= n) {
                    throw Refused("syntax", "unterminated attribute value")
                }
                val c = s[pos]
                if(c == quote) {
                    pos++
                    return value.toString()
                } else if(c == '<') {
                    throw Refused("syntax", "'<' in an attribute value")
                } else if(c == '&') {
                    pos = entity(pos, value)
                } else {
                    value.append(if(isSpace(c)) ' ' else c)
                    pos++
                }
            }
        }

        private fun endTag() {
            pos += 2
            val name = name()
            skipSpaces()
            if(pos >= n || s[pos] != '>') {
                throw Refused("syntax", "malformed end tag")
            }
            pos++
            val top = peekFrame()
            if(top == null || top.name != name) {
                throw Refused("syntax", "mismatched end tag $name")
            }
            close()
        }

        private fun element(name: String, attributes: Map<String, String>, parent: Frame?) {
            //Every decoded value, read or ignored, is checked before anything else looks at it
            for(value in attributes.values) {
                val lower = asciiLower(value)
                if(lower.contains("url(") || lower.contains("javascript:")) {
                    throw Refused("value", "a reference or a script in an attribute value")
                }
            }
            for((key, value) in attributes) {
                if(key == "xmlns" && value != SVG_NAMESPACE || key == "xmlns:xlink" && value != XLINK_NAMESPACE) {
                    throw Refused("namespace", "$key=$value")
                }
            }
            if(parent == null && "xmlns" !in attributes) {
                throw Refused("namespace", "the root does not declare the SVG namespace")
            }

            val state = inherit(parent?.state ?: root(attributes), attributes)
            val style = state.style()
            var x = 0.0
            var y = 0.0
            when(name) {
                "rect" -> {
                    val w = length(attributes, "width", true)
                    val h = length(attributes, "height", true)
                    val rx: Double? = if("rx" in attributes) length(attributes, "rx", true) else null
                    val ry: Double? = if("ry" in attributes) length(attributes, "ry", true) else null
                    val rxUsed = rx ?: ry ?: 0.0
                    val ryUsed = ry ?: rx ?: 0.0
                    val rectX = length(attributes, "x", false)
                    val rectY = length(attributes, "y", false)
                    shapes.add(Rect(rectX, rectY, w, h, minAsJava(rxUsed, w / 2), minAsJava(ryUsed, h / 2), style))
                }
                "circle" -> {
                    val cx = length(attributes, "cx", false)
                    val cy = length(attributes, "cy", false)
                    shapes.add(Circle(cx, cy, length(attributes, "r", true), style))
                }
                "ellipse" -> {
                    val cx = length(attributes, "cx", false)
                    val cy = length(attributes, "cy", false)
                    val rx = length(attributes, "rx", true)
                    shapes.add(Ellipse(cx, cy, rx, length(attributes, "ry", true), style))
                }
                "line" -> {
                    val x1 = length(attributes, "x1", false)
                    val y1 = length(attributes, "y1", false)
                    val x2 = length(attributes, "x2", false)
                    shapes.add(Line(x1, y1, x2, length(attributes, "y2", false), style))
                }
                "polyline", "polygon" -> {
                    val points = attributes["points"] ?: throw Refused("points", "no points")
                    shapes.add(Polyline(points(points), name == "polygon", style))
                }
                "path" -> {
                    val d = attributes["d"] ?: throw Refused("path", "no path data")
                    shapes.add(PathShape(path(d), style))
                }
                "text" -> {
                    x = length(attributes, "x", false)
                    y = length(attributes, "y", false)
                }
                else -> {}
            }
            val holdsText = name == "text" || name == "title" || name == "desc"
            stack.add(Frame(name, state, holdsText, x, y))
        }

        private fun close() {
            val frame = stack.removeAt(stack.size - 1)
            if(frame.name == "text") {
                val state = frame.state
                shapes.add(Text(frame.x, frame.y, state.fontSize, state.anchor, state.bold, state.monospace, clean(frame.text!!), state.style()))
            }
            if(stack.isEmpty()) {
                rootDone = true
            }
        }

        /** The root's size, and the state its attributes start from. */
        private fun root(attributes: Map<String, String>): State {
            val w: Double? = attributes["width"]?.let { side(it) }
            val h: Double? = attributes["height"]?.let { side(it) }
            var composed = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val viewBox = attributes["viewBox"]
            if(viewBox != null) {
                val scan = Scan(viewBox)
                scan.spaces()
                val box = DoubleArray(4)
                for(i in 0 until 4) {
                    if(i > 0) {
                        scan.separator()
                    }
                    box[i] = scan.number("size")
                }
                scan.spaces()
                if(!scan.end() || !(box[2] >= MIN_SIDE && box[2] <= MAX_SIDE && box[3] >= MIN_SIDE && box[3] <= MAX_SIDE)) {
                    throw Refused("size", "viewBox")
                }
                width = box[2]
                height = box[3]
                if(box[0] != 0.0 || box[1] != 0.0) {
                    composed = doubleArrayOf(1.0, 0.0, 0.0, 1.0, -box[0], -box[1])
                }
            } else if(w != null && h != null) {
                width = w
                height = h
            } else {
                throw Refused("size", "no viewBox, width and height")
            }
            return State(0x000000, NONE, 1.0, 1.0, 1.0, 1.0, composed, false, 16.0, false, Anchor.START)
        }

        private fun side(value: String): Double {
            val side = length(value, "size")
            if(!(side >= MIN_SIDE && side <= MAX_SIDE)) {
                throw Refused("size", "width or height out of range")
            }
            return side
        }
    }

    // ---------------- Attribute values ----------------

    private fun attributes(paint: Boolean, font: Boolean, vararg own: String): Set<String> {
        val names = ArrayList<String>(own.asList())
        names.add("id")
        if(paint) {
            names.addAll(listOf("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity"))
        }
        if(font) {
            names.addAll(listOf("font-family", "font-size", "font-weight", "text-anchor"))
        }
        return names.toSet()
    }

    private fun inherit(parent: State, attributes: Map<String, String>): State {
        var fill = parent.fill
        var stroke = parent.stroke
        var strokeWidth = parent.strokeWidth
        var fillOpacity = parent.fillOpacity
        var strokeOpacity = parent.strokeOpacity
        var opacity = parent.opacity
        var composed = parent.composed
        var monospace = parent.monospace
        var fontSize = parent.fontSize
        var bold = parent.bold
        var anchor = parent.anchor
        for((key, value) in attributes) {
            when(key) {
                "fill" -> fill = colour(value)
                "stroke" -> stroke = colour(value)
                "stroke-width" -> strokeWidth = nonNegative(length(value, "number"))
                "fill-opacity" -> fillOpacity = unit(value)
                "stroke-opacity" -> strokeOpacity = unit(value)
                "opacity" -> opacity = parent.opacity * unit(value)
                "transform" -> {
                    //All of the attribute's transforms are read first, then composed one by one, each product bounded
                    val own = transforms(value)
                    for(transform in own) {
                        composed = compose(composed, transform)
                    }
                }
                "font-family" -> monospace = family(value)
                "font-size" -> {
                    fontSize = length(value, "number")
                    if(!(fontSize > 0 && fontSize <= MAX_FONT_SIZE)) {
                        throw Refused("font", "font size $value")
                    }
                }
                "font-weight" -> bold = weight(value)
                "text-anchor" -> anchor = anchor(value)
                "shape-rendering" -> {
                    if(asciiLower(trim(value)) !in SHAPE_RENDERING) {
                        throw Refused("attribute", "shape-rendering $value")
                    }
                }
                "version" -> {
                    if(trim(value) !in VERSIONS) {
                        throw Refused("attribute", "version $value")
                    }
                }
                else -> {
                    //Geometry is read by the element; id, xmlns and xmlns:xlink are ignored once checked
                }
            }
        }
        return State(fill, stroke, strokeWidth, fillOpacity, strokeOpacity, opacity, composed, monospace, fontSize, bold, anchor)
    }

    /** p followed by t (p x t), each entry evaluated left to right; every entry must stay finite and within MAX_COMPOSED. */
    private fun compose(p: DoubleArray, t: DoubleArray): DoubleArray {
        val m = doubleArrayOf(
            p[0] * t[0] + p[2] * t[1],
            p[1] * t[0] + p[3] * t[1],
            p[0] * t[2] + p[2] * t[3],
            p[1] * t[2] + p[3] * t[3],
            p[0] * t[4] + p[2] * t[5] + p[4],
            p[1] * t[4] + p[3] * t[5] + p[5])
        for(entry in m) {
            if(!(kotlin.math.abs(entry) <= MAX_COMPOSED)) {
                throw Refused("transform", "the transforms multiply beyond $MAX_COMPOSED")
            }
        }
        return m
    }

    /** A geometry attribute (0 when absent): a number, optionally in px. */
    private fun length(attributes: Map<String, String>, name: String, nonNegative: Boolean): Double {
        val value = attributes[name] ?: return 0.0
        val length = length(value, "number")
        return if(nonNegative) nonNegative(length) else length
    }

    private fun length(value: String, reason: String): Double {
        val scan = Scan(value)
        scan.spaces()
        val number = scan.number(reason)
        if(scan.v.startsWith("px", scan.i)) {
            scan.i += 2
        }
        scan.spaces()
        if(!scan.end()) {
            throw Refused(reason, "not a plain length: $value")
        }
        return number
    }

    private fun nonNegative(value: Double): Double {
        if(value < 0) {
            throw Refused("number", "negative")
        }
        return value
    }

    /** An opacity: a plain number, clamped to 0..1. */
    private fun unit(value: String): Double {
        val scan = Scan(value)
        scan.spaces()
        val number = scan.number("number")
        scan.spaces()
        if(!scan.end()) {
            throw Refused("number", "not a plain number: $value")
        }
        return maxAsJava(0.0, minAsJava(1.0, number))
    }

    private fun colour(value: String): Int {
        val v = asciiLower(trim(value))
        if(v == "none") {
            return NONE
        }
        if(v.startsWith("#") && (v.length == 4 || v.length == 7)) {
            var rgb = 0
            for(i in 1 until v.length) {
                val digit = hexDigit(v[i])
                if(digit < 0) {
                    throw Refused("colour", value)
                }
                rgb = if(v.length == 4) (rgb shl 8) or (digit shl 4) or digit else (rgb shl 4) or digit
            }
            return rgb
        }
        if(v.startsWith("rgb(") && v.endsWith(")")) {
            val inner = v.substring(4, v.length - 1)
            var rgb = 0
            var start = 0
            for(part in 0 until 3) {
                val comma = inner.indexOf(',', start)
                if((part < 2) != (comma >= 0)) {
                    throw Refused("colour", value)
                }
                val channel = trim(inner.substring(start, if(part < 2) comma else inner.length))
                if(channel.isEmpty() || channel.length > 3) {
                    throw Refused("colour", value)
                }
                var level = 0
                for(c in channel) {
                    if(c < '0' || c > '9') {
                        throw Refused("colour", value)
                    }
                    level = level * 10 + (c - '0')
                }
                if(level > 255) {
                    throw Refused("colour", value)
                }
                rgb = (rgb shl 8) or level
                start = comma + 1
            }
            return rgb
        }
        return COLOUR_NAMES[v] ?: throw Refused("colour", value)
    }

    private fun transforms(value: String): List<DoubleArray> {
        val scan = Scan(value)
        val transforms = ArrayList<DoubleArray>()
        scan.spaces()
        while(!scan.end()) {
            if(transforms.size == MAX_TRANSFORMS) {
                throw Refused("transform", "more than $MAX_TRANSFORMS transforms")
            }
            val start = scan.i
            while(!scan.end() && isAsciiLetter(scan.peek())) {
                scan.i++
            }
            val function = scan.v.substring(start, scan.i)
            scan.spaces()
            if(scan.end() || scan.peek() != '(') {
                throw Refused("transform", "malformed transform")
            }
            scan.i++
            scan.spaces()
            val arguments = DoubleArray(6)
            var count = 0
            var afterComma = false
            while(true) {
                if(scan.end()) {
                    throw Refused("transform", "unclosed transform")
                }
                if(scan.peek() == ')' && !afterComma) {
                    scan.i++
                    break
                }
                if(count == arguments.size) {
                    throw Refused("transform", "too many arguments")
                }
                arguments[count++] = scan.number("transform")
                afterComma = scan.separator()
            }
            transforms.add(matrix(function, arguments, count))
            if(scan.separator() && scan.end()) {
                throw Refused("transform", "trailing comma")
            }
        }
        return transforms
    }

    private fun matrix(function: String, a: DoubleArray, count: Int): DoubleArray {
        when(function) {
            "translate" -> if(count == 1 || count == 2) {
                return doubleArrayOf(1.0, 0.0, 0.0, 1.0, a[0], if(count == 2) a[1] else 0.0)
            }
            "scale" -> if(count == 1 || count == 2) {
                return doubleArrayOf(a[0], 0.0, 0.0, if(count == 2) a[1] else a[0], 0.0, 0.0)
            }
            "rotate" -> if(count == 1 || count == 3) {
                //The truncated remainder (the sign of the dividend), the same as the desktop's double %
                var turn = a[0] % 360
                if(turn < 0) {
                    turn += 360
                }
                val cos: Double
                val sin: Double
                if(turn == 0.0) {
                    cos = 1.0
                    sin = 0.0
                } else if(turn == 90.0) {
                    cos = 0.0
                    sin = 1.0
                } else if(turn == 180.0) {
                    cos = -1.0
                    sin = 0.0
                } else if(turn == 270.0) {
                    cos = 0.0
                    sin = -1.0
                } else {
                    //StrictMath on the desktop; the platform's here (the last bit can differ, hidden by six decimals)
                    val radians = turn / 180.0 * kotlin.math.PI
                    cos = kotlin.math.cos(radians)
                    sin = kotlin.math.sin(radians)
                }
                val cx = if(count == 3) a[1] else 0.0
                val cy = if(count == 3) a[2] else 0.0
                return doubleArrayOf(cos, sin, -sin, cos, cx - cos * cx + sin * cy, cy - sin * cx - cos * cy)
            }
            "matrix" -> if(count == 6) {
                return a.copyOf()
            }
            else -> throw Refused("transform", "unknown transform $function")
        }
        throw Refused("transform", "wrong number of arguments to $function")
    }

    private fun points(value: String): DoubleArray {
        val scan = Scan(value)
        var numbers = DoubleArray(16)
        var count = 0
        var afterComma = false
        scan.spaces()
        while(!scan.end()) {
            if(count == 2 * MAX_POINTS) {
                throw Refused("points", "more than $MAX_POINTS points")
            }
            if(count == numbers.size) {
                numbers = numbers.copyOf(kotlin.math.min(numbers.size * 2, 2 * MAX_POINTS))
            }
            numbers[count++] = scan.number("points")
            afterComma = scan.separator()
        }
        if(afterComma || count == 0 || count % 2 != 0) {
            throw Refused("points", "not a list of points")
        }
        return numbers.copyOf(count)
    }

    private fun path(d: String): List<Segment> {
        val scan = Scan(d)
        scan.spaces()
        if(scan.end() || (scan.peek() != 'M' && scan.peek() != 'm')) {
            throw Refused("path", "path data must start with a moveto")
        }
        val segments = ArrayList<Segment>()
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var controlX = 0.0
        var controlY = 0.0
        var previous = 0.toChar()
        var command = 0.toChar()
        var afterComma = false
        while(true) {
            if(scan.end()) {
                if(afterComma) {
                    throw Refused("path", "trailing comma")
                }
                return segments
            }
            val c = scan.peek()
            if("MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0) {
                if(afterComma) {
                    throw Refused("path", "comma before a command")
                }
                command = c
                scan.i++
                scan.spaces()
                if(c == 'Z' || c == 'z') {
                    add(segments, Close)
                    x = startX
                    y = startY
                    previous = 'Z'
                    continue
                }
            } else if(command == 0.toChar() || command == 'Z' || command == 'z' || !scan.numberNext()) {
                throw Refused("path", "unexpected '$c' in path data")
            } else if(command == 'M') {
                command = 'L'
            } else if(command == 'm') {
                command = 'l'
            }

            val relative = command >= 'a'
            val dx = if(relative) x else 0.0
            val dy = if(relative) y else 0.0
            when(asciiUpper(command)) {
                'M' -> {
                    x = coordinate(dx + scan.number("path"))
                    y = coordinate(dy + scan.argument())
                    startX = x
                    startY = y
                    add(segments, MoveTo(x, y))
                }
                'L' -> {
                    x = coordinate(dx + scan.number("path"))
                    y = coordinate(dy + scan.argument())
                    add(segments, LineTo(x, y))
                }
                'H' -> {
                    x = coordinate(dx + scan.number("path"))
                    add(segments, LineTo(x, y))
                }
                'V' -> {
                    y = coordinate(dy + scan.number("path"))
                    add(segments, LineTo(x, y))
                }
                'C' -> {
                    val x1 = coordinate(dx + scan.number("path"))
                    val y1 = coordinate(dy + scan.argument())
                    controlX = coordinate(dx + scan.argument())
                    controlY = coordinate(dy + scan.argument())
                    x = coordinate(dx + scan.argument())
                    y = coordinate(dy + scan.argument())
                    add(segments, CubicTo(x1, y1, controlX, controlY, x, y))
                }
                'S' -> {
                    val x1 = coordinate(if(previous == 'C') 2 * x - controlX else x)
                    val y1 = coordinate(if(previous == 'C') 2 * y - controlY else y)
                    controlX = coordinate(dx + scan.number("path"))
                    controlY = coordinate(dy + scan.argument())
                    x = coordinate(dx + scan.argument())
                    y = coordinate(dy + scan.argument())
                    add(segments, CubicTo(x1, y1, controlX, controlY, x, y))
                }
                'Q' -> {
                    controlX = coordinate(dx + scan.number("path"))
                    controlY = coordinate(dy + scan.argument())
                    x = coordinate(dx + scan.argument())
                    y = coordinate(dy + scan.argument())
                    add(segments, QuadTo(controlX, controlY, x, y))
                }
                'T' -> {
                    controlX = coordinate(if(previous == 'Q') 2 * x - controlX else x)
                    controlY = coordinate(if(previous == 'Q') 2 * y - controlY else y)
                    x = coordinate(dx + scan.number("path"))
                    y = coordinate(dy + scan.argument())
                    add(segments, QuadTo(controlX, controlY, x, y))
                }
                else -> {
                    //'A'
                    val rx = scan.number("path")
                    val ry = scan.argument()
                    if(rx < 0 || ry < 0) {
                        throw Refused("path", "negative arc radius")
                    }
                    val rotation = scan.argument()
                    val largeArc = scan.flag()
                    val sweep = scan.flag()
                    x = coordinate(dx + scan.argument())
                    y = coordinate(dy + scan.argument())
                    add(segments, ArcTo(rx, ry, rotation, largeArc, sweep, x, y))
                }
            }
            val upper = asciiUpper(command)
            previous = if(upper == 'C' || upper == 'S') 'C' else if(upper == 'Q' || upper == 'T') 'Q' else upper
            afterComma = scan.separator()
        }
    }

    private fun add(segments: MutableList<Segment>, segment: Segment) {
        if(segments.size == MAX_PATH_SEGMENTS) {
            throw Refused("path", "more than $MAX_PATH_SEGMENTS segments")
        }
        segments.add(segment)
    }

    private fun coordinate(value: Double): Double {
        if(!(kotlin.math.abs(value) <= MAX_MAGNITUDE)) {
            throw Refused("number", "a coordinate out of range")
        }
        return value
    }

    private fun family(value: String): Boolean {
        if(value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) {
            throw Refused("font", "quoted font family")
        }
        var monospace = false
        var start = 0
        var index = 0
        while(start <= value.length) {
            val comma = value.indexOf(',', start)
            val end = if(comma < 0) value.length else comma
            //Trimmed, with each run of whitespace inside it read as one space
            val name = StringBuilder()
            var gap = false
            for(i in start until end) {
                val c = value[i]
                if(isSpace(c)) {
                    gap = name.isNotEmpty()
                } else {
                    if(gap) {
                        name.append(' ')
                        gap = false
                    }
                    name.append(c)
                }
            }
            val family = asciiLower(name.toString())
            if(family !in MONOSPACE_FAMILIES && family !in OTHER_FAMILIES) {
                throw Refused("font", "font family $family")
            }
            if(index == 0) {
                monospace = family in MONOSPACE_FAMILIES
            }
            start = end + 1
            index++
        }
        return monospace
    }

    private fun weight(value: String): Boolean {
        val v = asciiLower(trim(value))
        if(v == "normal") {
            return false
        }
        if(v == "bold") {
            return true
        }
        if(v.isNotEmpty() && v.length <= 4) {
            var weight = 0
            for(c in v) {
                if(c < '0' || c > '9') {
                    throw Refused("font", "font weight $value")
                }
                weight = weight * 10 + (c - '0')
            }
            if(weight in 1..1000) {
                return weight >= 600
            }
        }
        throw Refused("font", "font weight $value")
    }

    private fun anchor(value: String): Anchor = when(asciiLower(trim(value))) {
        "start" -> Anchor.START
        "middle" -> Anchor.MIDDLE
        "end" -> Anchor.END
        else -> throw Refused("font", "text anchor $value")
    }

    /**
     * Text content: tab, line feed, carriage return and space become one space between words and none at the ends, every
     * code point in [TextRules]' fixed table is removed, and more than 200 code points left refuses (reason "text").
     * Unlike TextRules' own rules a lone surrogate is kept here, as on the desktop: the reader refuses one before this.
     */
    internal fun clean(raw: CharSequence): String {
        val text = raw.toString()
        val clean = StringBuilder()
        var space = false
        var count = 0
        var i = 0
        while(i < text.length) {
            val c = TextRules.codePointOf(text, i)
            i += TextRules.charCount(c)
            if(c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) {
                space = clean.isNotEmpty()
                continue
            }
            if(TextRules.stripped(c)) {
                continue
            }
            if(space) {
                clean.append(' ')
                count++
                space = false
            }
            TextRules.appendCodePoint(clean, c)
            if(++count > MAX_TEXT) {
                throw Refused("text", "more than $MAX_TEXT characters of text")
            }
        }
        return clean.toString()
    }

    // ---------------- Small helpers ----------------

    /** A cursor over one attribute value. */
    private class Scan(val v: String) {
        var i = 0

        fun end(): Boolean = i >= v.length

        fun peek(): Char = v[i]

        fun spaces() {
            while(i < v.length && isSpace(v[i])) {
                i++
            }
        }

        /** Skips whitespace and at most one comma; true when a comma was skipped. */
        fun separator(): Boolean {
            spaces()
            if(i < v.length && v[i] == ',') {
                i++
                spaces()
                return true
            }
            return false
        }

        fun numberNext(): Boolean {
            if(end()) {
                return false
            }
            val c = peek()
            return (c in '0'..'9') || c == '.' || c == '+' || c == '-'
        }

        /** The next path argument after a separator. */
        fun argument(): Double {
            separator()
            return number("path")
        }

        fun flag(): Boolean {
            separator()
            if(!end() && (peek() == '0' || peek() == '1')) {
                return v[i++] == '1'
            }
            throw Refused("path", "an arc flag must be 0 or 1")
        }

        /** A number; refuses with missing when none starts here, and with "number" when one is malformed or out of range. */
        fun number(missing: String): Double {
            val start = i
            if(!numberNext()) {
                throw Refused(missing, "expected a number")
            }
            if(peek() == '+' || peek() == '-') {
                i++
            }
            var digits = 0
            while(!end() && peek() in '0'..'9') {
                i++
                digits++
            }
            if(!end() && peek() == '.') {
                i++
                while(!end() && peek() in '0'..'9') {
                    i++
                    digits++
                }
            }
            if(digits == 0) {
                throw Refused("number", "a sign or a point without digits")
            }
            if(!end() && (peek() == 'e' || peek() == 'E')) {
                var e = i + 1
                if(e < v.length && (v[e] == '+' || v[e] == '-')) {
                    e++
                }
                val exponentStart = e
                while(e < v.length && v[e] in '0'..'9') {
                    e++
                }
                if(e - exponentStart > 3) {
                    throw Refused("number", "an exponent of more than 3 digits")
                }
                if(e > exponentStart) {
                    i = e
                }
            }
            if(i - start > MAX_NUMBER_CHARS) {
                throw Refused("number", "a number longer than $MAX_NUMBER_CHARS characters")
            }
            //The token is ASCII sign, digits, point and exponent only, which every platform's correctly rounded
            //conversion reads to the same double as the desktop's Double.parseDouble
            val value = v.substring(start, i).toDouble()
            if(!(kotlin.math.abs(value) <= MAX_MAGNITUDE)) {
                throw Refused("number", "out of range")
            }
            return value
        }
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isAsciiLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

    private fun isAsciiLetterOrDigit(c: Char): Boolean = isAsciiLetter(c) || (c in '0'..'9')

    private fun asciiUpper(c: Char): Char = if(c in 'a'..'z') c - 32 else c

    private fun hexDigit(c: Char): Int = when(c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Space, tab, line feed and carriage return removed from both ends (nothing else, unlike a Unicode-aware trim). */
    private fun trim(value: String): String {
        var start = 0
        var end = value.length
        while(start < end && isSpace(value[start])) {
            start++
        }
        while(end > start && isSpace(value[end - 1])) {
            end--
        }
        return value.substring(start, end)
    }

    /** A to Z lowered, nothing else (a Unicode-aware lowering would turn the Kelvin sign into k). */
    private fun asciiLower(value: String): String {
        val lower = StringBuilder(value.length)
        for(c in value) {
            lower.append(if(c in 'A'..'Z') c + 32 else c)
        }
        return lower.toString()
    }

    /**
     * The desktop's (int)Math.round(double): the exact floor(x + 1/2), ties up, done on the bits as the JDK does it (a
     * floating x + 0.5 would round 0.49999999999999994 up). NaN is 0; beyond the long range it saturates.
     */
    internal fun javaRound(x: Double): Long {
        val bits = x.toRawBits()
        val biasedExponent = (bits and 0x7FF0000000000000L) shr 52
        val shift = (53 - 2 + 1023) - biasedExponent
        if((shift and -64L) == 0L) {
            var r = (bits and 0x000FFFFFFFFFFFFFL) or 0x0010000000000000L
            if(bits < 0) {
                r = -r
            }
            return ((r shr shift.toInt()) + 1) shr 1
        }
        return x.toLong()
    }

    private fun Double.roundToIntAsJava(): Int = javaRound(this).toInt()

    private fun minAsJava(a: Double, b: Double): Double = kotlin.math.min(a, b)

    private fun maxAsJava(a: Double, b: Double): Double = kotlin.math.max(a, b)

    // ---------------- The stable text form ----------------

    /** The drawing in the stable text form, one line per entry, as the desktop prints it. */
    fun describe(drawing: Drawing): List<String> {
        val lines = ArrayList<String>()
        lines.add("size " + number(drawing.width) + " " + number(drawing.height))
        for(shape in drawing.shapes) {
            val line = StringBuilder()
            when(shape) {
                is Rect -> line.append("rect x=").append(number(shape.x)).append(" y=").append(number(shape.y)).append(" w=").append(number(shape.width))
                    .append(" h=").append(number(shape.height)).append(" rx=").append(number(shape.rx)).append(" ry=").append(number(shape.ry))
                is Circle -> line.append("circle cx=").append(number(shape.cx)).append(" cy=").append(number(shape.cy)).append(" r=").append(number(shape.r))
                is Ellipse -> line.append("ellipse cx=").append(number(shape.cx)).append(" cy=").append(number(shape.cy)).append(" rx=")
                    .append(number(shape.rx)).append(" ry=").append(number(shape.ry))
                is Line -> line.append("line x1=").append(number(shape.x1)).append(" y1=").append(number(shape.y1)).append(" x2=").append(number(shape.x2))
                    .append(" y2=").append(number(shape.y2))
                is Polyline -> {
                    line.append(if(shape.closed) "polygon points=[" else "polyline points=[")
                    for(i in 0 until shape.pointCount) {
                        line.append(if(i == 0) "" else ";").append(number(shape.x(i))).append(' ').append(number(shape.y(i)))
                    }
                    line.append(']')
                }
                is PathShape -> {
                    line.append("path d=[")
                    for(i in shape.segments.indices) {
                        line.append(if(i == 0) "" else ";").append(segment(shape.segments[i]))
                    }
                    line.append(']')
                }
                is Text -> line.append("text x=").append(number(shape.x)).append(" y=").append(number(shape.y)).append(" size=").append(number(shape.size))
                    .append(" anchor=").append(when(shape.anchor) { Anchor.START -> "start"; Anchor.MIDDLE -> "middle"; Anchor.END -> "end" })
                    .append(" bold=").append(shape.bold).append(" mono=").append(shape.monospace)
            }
            line.append(" fill=").append(paint(shape.fill)).append(" stroke=").append(paint(shape.stroke))
                .append(" sw=").append(number(shape.strokeWidth)).append(" op=").append(number(shape.opacity)).append(" m=[")
            val matrix = shape.composedMatrix.toArray()
            for(k in 0 until 6) {
                line.append(if(k == 0) "" else " ").append(number(matrix[k]))
            }
            line.append(']')
            if(shape is Text) {
                line.append(" content=").append(shape.content)
            }
            lines.add(line.toString())
        }
        return lines
    }

    private fun segment(segment: Segment): String = when(segment) {
        is MoveTo -> "M " + number(segment.x) + " " + number(segment.y)
        is LineTo -> "L " + number(segment.x) + " " + number(segment.y)
        is CubicTo -> "C " + number(segment.x1) + " " + number(segment.y1) + " " + number(segment.x2) + " " + number(segment.y2) + " " +
            number(segment.x) + " " + number(segment.y)
        is QuadTo -> "Q " + number(segment.x1) + " " + number(segment.y1) + " " + number(segment.x) + " " + number(segment.y)
        is ArcTo -> "A " + number(segment.rx) + " " + number(segment.ry) + " " + number(segment.rotation) + " " + (if(segment.largeArc) "1" else "0") +
            " " + (if(segment.sweep) "1" else "0") + " " + number(segment.x) + " " + number(segment.y)
        Close -> "Z"
    }

    private fun paint(paint: Paint?): String {
        if(paint == null) {
            return "none"
        }
        val digits = "0123456789ABCDEF"
        val hex = StringBuilder("#")
        var shift = 28
        while(shift >= 0) {
            hex.append(digits[(paint.argb ushr shift) and 0xF])
            shift -= 4
        }
        return hex.toString()
    }

    /**
     * A number in the stable form: the exact binary value of the double rounded half-even to six decimals, trailing zeros
     * removed, "0" for zero (so -0 and tiny values print "0"), no exponent and no locale, as the desktop's BigDecimal
     * printing gives. Done here with exact integer arithmetic: v = m * 2^e, so for e >= 0 it is the integer m * 2^e, and
     * for e < 0 it is m * 5^k / 10^k with k = -e, whose decimal digits are exact; then one rounding at the sixth place.
     */
    internal fun number(value: Double): String {
        if(!value.isFinite()) {
            //Not in any model; never thrown for
            return value.toString()
        }
        val bits = value.toRawBits()
        val negative = bits < 0
        val biased = ((bits ushr 52) and 0x7FF).toInt()
        val fraction = bits and 0xFFFFFFFFFFFFFL
        if(biased == 0 && fraction == 0L) {
            return "0"
        }
        val mantissa = if(biased == 0) fraction else fraction or (1L shl 52)
        val exponent = if(biased == 0) -1074 else biased - 1075
        val digits: String
        val scale: Int
        if(exponent >= 0) {
            digits = Decimal.of(mantissa).timesPowerOfTwo(exponent).toString()
            scale = 0
        } else {
            val k = -exponent
            //Below 2^53 / 2^81 the value is under 0.0000005 and rounds to zero
            if(k > 80) {
                return "0"
            }
            digits = Decimal.of(mantissa).timesPowerOfFive(k).toString()
            scale = k
        }
        //Integer and fraction digits, the fraction padded to six places or rounded once to six
        val padded = if(digits.length <= scale) "0".repeat(scale - digits.length + 1) + digits else digits
        val integerDigits = padded.length - scale
        val kept: CharArray
        if(scale <= 6) {
            kept = (padded + "0".repeat(6 - scale)).toCharArray()
        } else {
            kept = padded.substring(0, integerDigits + 6).toCharArray()
            val first = padded[integerDigits + 6]
            var roundUp = first > '5'
            if(first == '5') {
                var rest = false
                for(i in integerDigits + 7 until padded.length) {
                    if(padded[i] != '0') {
                        rest = true
                        break
                    }
                }
                roundUp = rest || (kept[kept.size - 1] - '0') % 2 == 1
            }
            if(roundUp) {
                var i = kept.size - 1
                while(i >= 0 && kept[i] == '9') {
                    kept[i] = '0'
                    i--
                }
                if(i < 0) {
                    return render(negative, "1" + kept.concatToString())
                }
                kept[i] = kept[i] + 1
            }
        }
        return render(negative, kept.concatToString())
    }

    /** units holds value * 10^6 as decimal digits (at least seven): the sign, the integer, and the fraction without its trailing zeros. */
    private fun render(negative: Boolean, units: String): String {
        var nonZero = false
        for(c in units) {
            if(c != '0') {
                nonZero = true
                break
            }
        }
        if(!nonZero) {
            return "0"
        }
        var integer = units.substring(0, units.length - 6)
        var start = 0
        while(start < integer.length - 1 && integer[start] == '0') {
            start++
        }
        integer = integer.substring(start)
        var fraction = units.substring(units.length - 6)
        var end = fraction.length
        while(end > 0 && fraction[end - 1] == '0') {
            end--
        }
        fraction = fraction.substring(0, end)
        return (if(negative) "-" else "") + integer + (if(fraction.isEmpty()) "" else ".$fraction")
    }

    /** A small unsigned integer in base 10^9 limbs, little-endian: only what the number printer needs. */
    private class Decimal private constructor(private var limbs: IntArray, private var size: Int) {
        private fun times(factor: Long): Decimal {
            //factor * limb stays below 2^63: factor is at most 2^30 or 5^13
            var carry = 0L
            for(i in 0 until size) {
                val product = limbs[i] * factor + carry
                limbs[i] = (product % BASE).toInt()
                carry = product / BASE
            }
            while(carry > 0) {
                if(size == limbs.size) {
                    limbs = limbs.copyOf(limbs.size * 2)
                }
                limbs[size++] = (carry % BASE).toInt()
                carry /= BASE
            }
            return this
        }

        fun timesPowerOfTwo(power: Int): Decimal {
            var left = power
            while(left > 0) {
                val step = kotlin.math.min(left, 30)
                times(1L shl step)
                left -= step
            }
            return this
        }

        fun timesPowerOfFive(power: Int): Decimal {
            var left = power
            while(left > 0) {
                val step = kotlin.math.min(left, 13)
                var factor = 1L
                repeat(step) { factor *= 5 }
                times(factor)
                left -= step
            }
            return this
        }

        override fun toString(): String {
            if(size == 0) {
                return "0"
            }
            val out = StringBuilder()
            out.append(limbs[size - 1])
            for(i in size - 2 downTo 0) {
                val limb = limbs[i].toString()
                for(pad in limb.length until 9) {
                    out.append('0')
                }
                out.append(limb)
            }
            return out.toString()
        }

        companion object {
            const val BASE = 1_000_000_000L

            fun of(value: Long): Decimal {
                val limbs = IntArray(8)
                var size = 0
                var rest = value
                while(rest > 0) {
                    limbs[size++] = (rest % BASE).toInt()
                    rest /= BASE
                }
                return Decimal(limbs, size)
            }
        }
    }
}
