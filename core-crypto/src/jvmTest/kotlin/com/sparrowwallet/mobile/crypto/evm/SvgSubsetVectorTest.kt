package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.evm.EvmVectors.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The SVG subset, held to the vectors drongo's SvgSubsetTest reads (vectors/evm/svg, copied byte for byte): every
 * accept vector describes as expected.json says, every refuse vector is refused with the word refusals.json gives.
 * Ports the rest of SvgSubsetTest that applies to a model without a per-transform list, plus the clean vectors' svg
 * fields and a comparison of the number printer with BigDecimal.
 */
class SvgSubsetVectorTest {
    private val dir = File("../vectors/evm/svg")
    private val ns = "xmlns=\"http://www.w3.org/2000/svg\""
    private val emoji = String(Character.toChars(0x1F600))

    /** As the desktop test reads a vector: the bytes decoded as UTF-8, malformed input replaced. */
    private fun resource(name: String): String = String(File(dir, name).readBytes(), Charsets.UTF_8)

    private fun vectorNames(folder: String): List<String> = File(dir, folder).listFiles()!!.map { it.name }.sorted()

    private fun allVectors(): List<String> = vectorNames("accept").map { resource("accept/$it") } + vectorNames("refuse").map { resource("refuse/$it") }

    private fun json(name: String): JsonObject = Json.parseToJsonElement(resource(name)).jsonObject

    /** The reason a refusal gives, or null when the input is accepted; any other exception fails the test. */
    private fun refusal(svg: String?): String? = try {
        SvgSubset.read(svg)
        null
    } catch(e: SvgSubset.Refused) {
        e.reason
    }

    private fun check(svg: String) {
        try {
            assertSane(SvgSubset.read(svg))
        } catch(e: SvgSubset.Refused) {
            assertNotNull(e.reason)
        }
    }

    private fun drawing(svg: String): SvgSubset.Drawing = try {
        SvgSubset.read(svg)
    } catch(e: SvgSubset.Refused) {
        throw AssertionError("refused (${e.message})")
    }

    private fun described(svg: String): List<String> = SvgSubset.describe(drawing(svg))

    private fun within(value: Double, low: Double, high: Double, what: String) {
        assertTrue(value.isFinite() && value >= low && value <= high, "$what $value")
    }

    private fun coordinate(value: Double) = within(value, -SvgSubset.MAX_MAGNITUDE, SvgSubset.MAX_MAGNITUDE, "coordinate")

    /** Every number in an accepted drawing is finite and within the limits the reader promises. */
    private fun assertSane(drawing: SvgSubset.Drawing) {
        within(drawing.width, SvgSubset.MIN_SIDE, SvgSubset.MAX_SIDE, "width")
        within(drawing.height, SvgSubset.MIN_SIDE, SvgSubset.MAX_SIDE, "height")
        assertTrue(drawing.shapes.size < SvgSubset.MAX_ELEMENTS)
        for(shape in drawing.shapes) {
            val matrix = shape.composedMatrix.toArray()
            assertEquals(6, matrix.size)
            for(entry in matrix) {
                within(entry, -SvgSubset.MAX_COMPOSED, SvgSubset.MAX_COMPOSED, "matrix entry")
            }
            within(shape.strokeWidth, 0.0, SvgSubset.MAX_MAGNITUDE, "stroke width")
            within(shape.opacity, 0.0, 1.0, "opacity")
            when(shape) {
                is SvgSubset.Rect -> {
                    coordinate(shape.x)
                    coordinate(shape.y)
                    within(shape.width, 0.0, SvgSubset.MAX_MAGNITUDE, "rect width")
                    within(shape.height, 0.0, SvgSubset.MAX_MAGNITUDE, "rect height")
                    within(shape.rx, 0.0, shape.width / 2, "rx")
                    within(shape.ry, 0.0, shape.height / 2, "ry")
                }
                is SvgSubset.Circle -> {
                    coordinate(shape.cx)
                    coordinate(shape.cy)
                    within(shape.r, 0.0, SvgSubset.MAX_MAGNITUDE, "r")
                }
                is SvgSubset.Ellipse -> {
                    coordinate(shape.cx)
                    coordinate(shape.cy)
                    within(shape.rx, 0.0, SvgSubset.MAX_MAGNITUDE, "rx")
                    within(shape.ry, 0.0, SvgSubset.MAX_MAGNITUDE, "ry")
                }
                is SvgSubset.Line -> {
                    coordinate(shape.x1)
                    coordinate(shape.y1)
                    coordinate(shape.x2)
                    coordinate(shape.y2)
                }
                is SvgSubset.Polyline -> {
                    val points = shape.points()
                    assertTrue(points.size >= 2 && points.size % 2 == 0 && points.size <= 2 * SvgSubset.MAX_POINTS)
                    points.forEach { coordinate(it) }
                }
                is SvgSubset.PathShape -> {
                    assertTrue(shape.segments.isNotEmpty() && shape.segments.size <= SvgSubset.MAX_PATH_SEGMENTS)
                    assertTrue(shape.segments[0] is SvgSubset.MoveTo)
                    for(segment in shape.segments) {
                        when(segment) {
                            is SvgSubset.MoveTo -> { coordinate(segment.x); coordinate(segment.y) }
                            is SvgSubset.LineTo -> { coordinate(segment.x); coordinate(segment.y) }
                            is SvgSubset.CubicTo -> listOf(segment.x1, segment.y1, segment.x2, segment.y2, segment.x, segment.y).forEach { coordinate(it) }
                            is SvgSubset.QuadTo -> listOf(segment.x1, segment.y1, segment.x, segment.y).forEach { coordinate(it) }
                            is SvgSubset.ArcTo -> {
                                within(segment.rx, 0.0, SvgSubset.MAX_MAGNITUDE, "arc rx")
                                within(segment.ry, 0.0, SvgSubset.MAX_MAGNITUDE, "arc ry")
                                coordinate(segment.rotation)
                                coordinate(segment.x)
                                coordinate(segment.y)
                            }
                            SvgSubset.Close -> {}
                        }
                    }
                }
                is SvgSubset.Text -> {
                    coordinate(shape.x)
                    coordinate(shape.y)
                    assertTrue(shape.size > 0 && shape.size <= SvgSubset.MAX_FONT_SIZE, "font size ${shape.size}")
                    val content = shape.content
                    assertTrue(content.codePointCount(0, content.length) <= SvgSubset.MAX_TEXT)
                    assertFalse(content.startsWith(" ") || content.endsWith(" ") || content.contains("  "), content)
                    assertTrue(content.codePoints().noneMatch { TextRules.stripped(it) || it == '\t'.code || it == '\n'.code || it == '\r'.code
                            || Character.getType(it) == Character.SURROGATE.toInt() }, content)
                }
            }
        }
    }

    @Test
    fun acceptVectorsGiveTheExpectedDrawing() {
        val expected = json("expected.json")
        val names = vectorNames("accept")
        assertEquals(expected.keys.toSortedSet(), names.toSortedSet(), "expected.json lists exactly the accept vectors")
        assertEquals(23, names.size)
        for(name in names) {
            val svg = resource("accept/$name")
            assertEquals(expected[name]!!.jsonArray.map { it.jsonPrimitive.content }, described(svg), name)
            assertNotNull(SvgSubset.parse(svg), name)
            assertSane(drawing(svg))
        }
    }

    @Test
    fun refuseVectorsAreAllRefused() {
        val reasons = json("refusals.json")
        val names = vectorNames("refuse")
        assertEquals(reasons.keys.toSortedSet(), names.toSortedSet(), "refusals.json lists exactly the refuse vectors")
        assertEquals(146, names.size)
        for(name in names) {
            val svg = resource("refuse/$name")
            assertEquals(reasons[name]!!.jsonPrimitive.content, refusal(svg), name)
            assertNull(SvgSubset.parse(svg), name)
        }
    }

    @Test
    fun nothingThrows() {
        val started = System.nanoTime()
        val vectors = allVectors()

        //Every prefix of every vector up to 20,000 characters; for longer ones the first and last 1,000 and every 997th
        for(vector in vectors) {
            val length = vector.length
            for(i in 0..length) {
                if(length <= 20_000 || i < 1_000 || i > length - 1_000 || i % 997 == 0) {
                    check(vector.substring(0, i))
                }
            }
        }

        //Hostile strings that are not vectors
        val lone = 0xD800.toChar().toString()
        val loneLow = 0xDC00.toChar().toString()
        val open = "<svg $ns viewBox=\"0 0 1 1\">"
        val hostile = listOf("", "\u0000", "<svg>\u0000</svg>", lone, "$open$loneLow</svg>",
            "$open<text>$lone</text></svg>", "<".repeat(1_000_000), "<".repeat(100_000), "&".repeat(100_000),
            open + "<g>".repeat(30_000), open + "<path d=\"M" + "9".repeat(99_000) + "\"/></svg>",
            open + "<text>&#" + "9".repeat(50_000) + ";</text></svg>", open + "<text>&" + "a".repeat(50_000) + ";</text></svg>",
            open + "<path d=\"M0 0" + " 1e".repeat(20_000) + "\"/></svg>", "<svg " + "a=\"\" ".repeat(10_000) + "/>",
            open + "<!--" + "-".repeat(50_000) + "></svg>", open + "<rect transform=\"" + "rotate(".repeat(10_000) + "\"/></svg>",
            "<" + "a".repeat(99_000) + "/>", open + "<rect " + "x".repeat(99_000) + "=\"1\"/></svg>",
            open + "<text>" + "]]".repeat(49_000) + "</text></svg>", open + "<text>" + "a<!---->".repeat(12_000) + "</text></svg>",
            open + "<polyline points=\"" + "1,".repeat(49_000) + "\"/></svg>")
        for(text in hostile + listOf<String?>(null)) {
            refusal(text)
            if(text != null) {
                SvgSubset.parse(text)
            }
        }

        //The single worst inputs each read in well under 2 seconds
        val large = hostile.filter { it.length >= 50_000 } + vectors.filter { it.length >= 50_000 }
        assertTrue(large.size >= 10)
        for(text in large) {
            val start = System.nanoTime()
            check(text)
            val millis = (System.nanoTime() - start) / 1_000_000
            assertTrue(millis < 2_000, "a ${text.length} character input took $millis ms")
        }

        //Random edits of every vector, with characters that matter to the reader; what is accepted must be sane
        val random = Random(20261111)
        val alphabet = "<>/=\"'&#;!?-[]:x0123456789.eE+-, \t\nMmLlZzAaHhVvCcSsQqTt()\u0000" + lone + loneLow + "‮"
        for(vector in vectors) {
            if(vector.isEmpty()) {
                continue
            }
            val rounds = if(vector.length <= 5_000) 300 else 10
            repeat(rounds) {
                val edited = StringBuilder(vector)
                for(edit in 0 until 1 + random.nextInt(3)) {
                    val at = random.nextInt(edited.length + 1)
                    val c = alphabet[random.nextInt(alphabet.length)]
                    when(random.nextInt(3)) {
                        0 -> edited.insert(at, c)
                        1 -> if(at < edited.length) edited.deleteCharAt(at)
                        else -> if(at < edited.length) edited.setCharAt(at, c)
                    }
                }
                check(edited.toString())
            }
        }

        val seconds = (System.nanoTime() - started) / 1_000_000_000L
        assertTrue(seconds < 120, "nothingThrows took $seconds s")
    }

    @Test
    fun theVaultBadgeDraws() {
        //The accept vector is exactly the picture the contract returns for World 1, as recorded from the chain
        val uri = File("../vectors/evm/nft/vault-badge-world-1-token-uri.txt").readText(Charsets.US_ASCII).trim()
        val metadata = assertNotNull(NftMetadata.parse(uri))
        val svg = (metadata.image as NftMetadata.Image.Svg).source
        assertEquals(resource("accept/vault-badge-world-1.svg"), svg)
        assertTrue(svg.startsWith("<svg $ns "), "the badge declares the SVG namespace")

        val drawing = assertNotNull(SvgSubset.parse(svg))
        assertEquals(64.0, drawing.width)
        assertEquals(64.0, drawing.height)
        assertEquals(7, drawing.shapes.size)
        assertEquals(3, drawing.shapes.count { it is SvgSubset.Rect })
        assertEquals(2, drawing.shapes.count { it is SvgSubset.Circle })
        val path = drawing.shapes[5] as SvgSubset.PathShape
        assertEquals(16, path.segments.size)
        assertEquals(SvgSubset.MoveTo(30.0, 16.0), path.segments[0])
        assertEquals(SvgSubset.Close, path.segments[15])
        val text = drawing.shapes[6] as SvgSubset.Text
        assertEquals("WORLD 1", text.content)
        assertEquals(SvgSubset.Anchor.MIDDLE, text.anchor)
        assertTrue(text.bold)
        assertTrue(text.monospace)
        assertEquals(9.0, text.size)
        assertEquals(SvgSubset.Paint(0xFFFFFFFF.toInt()), text.fill)
        assertNull(text.stroke)
        assertEquals(SvgSubset.Paint(0xFF0E1E38.toInt()), drawing.shapes[0].fill)
        for(shape in drawing.shapes) {
            assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), shape.composedMatrix.toArray().toList())
        }

        //Every World draws the same way
        assertEquals("WORLD 4", (assertNotNull(SvgSubset.parse(svg.replace("WORLD 1", "WORLD 4"))).shapes[6] as SvgSubset.Text).content)
    }

    @Test
    fun theModelCannotBeChanged() {
        val drawing = drawing("<svg $ns viewBox=\"0 0 10 10\"><g transform=\"translate(1 2)\"><polygon points=\"1 2 3 4\"/></g></svg>")
        val polygon = drawing.shapes[0] as SvgSubset.Polyline
        polygon.points()[0] = 99.0
        polygon.composedMatrix.toArray()[4] = 99.0
        assertEquals(1.0, polygon.points()[0])
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 1.0, 2.0), polygon.composedMatrix.toArray().toList())
        assertTrue(runCatching { (drawing.shapes as MutableList<SvgSubset.Shape>).clear() }.isFailure, "the shape list is read-only")
    }

    @Test
    fun composedMatricesAreFiniteAndBounded() {
        for(name in vectorNames("accept")) {
            assertSane(drawing(resource("accept/$name")))
        }

        //The composed matrix is the documented product, outermost first: here recomputed from the same transforms
        val open = "<svg $ns viewBox=\"-3 4 10 10\">"
        val nested = open + "<g transform=\"rotate(33 5 5) scale(1.5 0.5) translate(-2 7) matrix(0.5 1 -1 0.5 3 4)\">".repeat(8) +
            "<rect width=\"1\" height=\"1\" transform=\"rotate(-71)\"/><circle r=\"1\"/>" + "</g>".repeat(8) + "</svg>"
        val nestedDrawing = drawing(nested)
        assertSane(nestedDrawing)
        val c33 = StrictMath.cos(33 / 180.0 * Math.PI)
        val s33 = StrictMath.sin(33 / 180.0 * Math.PI)
        val group = listOf(doubleArrayOf(c33, s33, -s33, c33, 5 - c33 * 5 + s33 * 5, 5 - s33 * 5 - c33 * 5), doubleArrayOf(1.5, 0.0, 0.0, 0.5, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, -2.0, 7.0), doubleArrayOf(0.5, 1.0, -1.0, 0.5, 3.0, 4.0))
        val c289 = StrictMath.cos(289 / 180.0 * Math.PI)
        val s289 = StrictMath.sin(289 / 180.0 * Math.PI)
        var product = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 3.0, -4.0)
        repeat(8) { group.forEach { product = compose(product, it) } }
        assertEquals(product.toList(), nestedDrawing.shapes[1].composedMatrix.toArray().toList())
        product = compose(product, doubleArrayOf(c289, s289, -s289, c289, 0.0, 0.0))
        //kotlin.math may differ from StrictMath in the last bit, which the stable form's six decimals hide
        val rect = nestedDrawing.shapes[0].composedMatrix.toArray()
        for(k in 0 until 6) {
            assertEquals(product[k], rect[k], 1e-9 * (1 + kotlin.math.abs(product[k])))
            assertEquals(SvgSubset.number(product[k]), SvgSubset.number(rect[k]))
        }

        //Nested cases: accepted ones stay within the bound, the rest refuse as a transform or a number
        val box = "<svg $ns viewBox=\"-3 4 10 10\">"
        val rect1 = "<rect width=\"1\" height=\"1\"/>"
        val cases = listOf(
            box + ("<g transform=\"" + "translate(1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
            box + ("<g transform=\"" + "rotate(7 1000000 -1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
            box + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(2) + ("<g transform=\"" + "scale(0.000001) ".repeat(16) + "\">").repeat(2) +
                rect1 + "</g>".repeat(4) + "</svg>",
            box + ("<g transform=\"" + "scale(0.000001) ".repeat(16) + "\">").repeat(18) + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(12) +
                rect1 + "</g>".repeat(30) + "</svg>",
            box + ("<g transform=\"" + "matrix(1000000 1000000 1000000 1000000 1000000 1000000) ".repeat(2) + "\">").repeat(2) + rect1 + "</g>".repeat(2) + "</svg>",
            box + ("<g transform=\"" + "matrix(0 1 -1 0 1000000 -1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
            box + "<g transform=\"" + "scale(-31622.7766) ".repeat(2) + "\">" + rect1 + "</g></svg>")
        var accepted = 0
        for(svg in cases) {
            val reason = refusal(svg)
            if(reason == null) {
                assertSane(drawing(svg))
                accepted++
            } else {
                assertTrue(reason == "transform" || reason == "number", reason)
            }
        }
        assertTrue(accepted >= 3, "accepted $accepted")

        //The exact bound, the viewBox translation included
        assertEquals(listOf(1000.0, 0.0, 0.0, 1000.0, 1_000_000_000.0, 0.0),
            drawing(resource("accept/limit-composed-1e9.svg")).shapes[0].composedMatrix.toArray().toList())
        assertEquals("transform", refusal(resource("refuse/transform-composed-just-over.svg")))
    }

    private fun compose(p: DoubleArray, t: DoubleArray): DoubleArray = doubleArrayOf(p[0] * t[0] + p[2] * t[1], p[1] * t[0] + p[3] * t[1],
        p[0] * t[2] + p[2] * t[3], p[1] * t[2] + p[3] * t[3], p[0] * t[4] + p[2] * t[5] + p[4], p[1] * t[4] + p[3] * t[5] + p[5])

    @Test
    fun limitsAreExact() {
        val open = "<svg $ns viewBox=\"0 0 10 10\">"
        val close = "</svg>"

        //Elements, the root counted
        assertNull(refusal(open + "<g/>".repeat(1_999) + close))
        assertEquals("elements", refusal(open + "<g/>".repeat(2_000) + close))

        //Nesting, the root and the innermost element counted
        assertNull(refusal(open + "<g>".repeat(30) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(30) + close))
        assertEquals("nesting", refusal(open + "<g>".repeat(31) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(31) + close))

        //Size, at least 1 and at most 4,096 per side
        assertNull(refusal("<svg $ns viewBox=\"0 0 4096 4096\"/>"))
        assertNull(refusal("<svg $ns width=\"4096px\" height=\"4096\"/>"))
        assertEquals("size", refusal("<svg $ns viewBox=\"0 0 4096.001 1\"/>"))
        assertEquals("size", refusal("<svg $ns width=\"1\" height=\"4096.001\"/>"))
        assertNull(refusal("<svg $ns viewBox=\"0 0 1 1\"/>"))
        assertNull(refusal("<svg $ns width=\"1\" height=\"1\"/>"))
        assertEquals("size", refusal("<svg $ns viewBox=\"0 0 0.999999 1\"/>"))
        assertEquals("size", refusal("<svg $ns viewBox=\"0 0 1 1e-320\"/>"))
        assertEquals("size", refusal("<svg $ns width=\"0.999999\" height=\"1\"/>"))
        assertEquals("size", refusal("<svg $ns viewBox=\"0 0 1 1\" width=\"0.5\" height=\"1\"/>"))

        //The root must declare the SVG namespace; the declaration may name only UTF-8
        assertEquals("namespace", refusal("<svg viewBox=\"0 0 1 1\"/>"))
        assertNull(refusal("<?xml version=\"1.0\"?><svg $ns viewBox=\"0 0 1 1\"/>"))
        assertNull(refusal("<?xml version=\"1.0\" encoding=\"utf-8\"?><svg $ns viewBox=\"0 0 1 1\"/>"))
        assertNull(refusal("<?xml version=\"1.0\" encoding='UTF-8' standalone=\"yes\"?><svg $ns viewBox=\"0 0 1 1\"/>"))
        for(encoding in listOf("UTF8", "utf-16", "ISO-8859-1", "US-ASCII", "UTF-8-x")) {
            assertEquals("pi", refusal("<?xml version=\"1.0\" encoding=\"$encoding\"?><svg $ns viewBox=\"0 0 1 1\"/>"), encoding)
        }

        //Font size above 0 and at most 4,096
        assertNull(refusal("$open<text font-size=\"4096\">a</text>$close"))
        assertNull(refusal("$open<text font-size=\"0.001\">a</text>$close"))
        assertEquals("font", refusal("$open<text font-size=\"4096.001\">a</text>$close"))
        assertEquals("font", refusal("$open<text font-size=\"0\">a</text>$close"))
        assertEquals("font", refusal("$open<g font-size=\"-0\"/>$close"))

        //Path segments, the moveto counted
        assertNull(refusal(open + "<path d=\"M0 0" + "h0".repeat(9_999) + "\"/>" + close))
        assertEquals("path", refusal(open + "<path d=\"M0 0" + "h0".repeat(10_000) + "\"/>" + close))

        //Points
        assertNull(refusal(open + "<polyline points=\"" + "1 2 ".repeat(10_000) + "\"/>" + close))
        assertEquals("points", refusal(open + "<polyline points=\"" + "1 2 ".repeat(10_001) + "\"/>" + close))

        //Transforms on one element
        assertNull(refusal(open + "<g transform=\"" + "scale(1) ".repeat(16) + "\"/>" + close))
        assertEquals("transform", refusal(open + "<g transform=\"" + "scale(1) ".repeat(17) + "\"/>" + close))

        //The product of a shape's transforms, its ancestors' and the viewBox translation included
        assertNull(refusal("$open<g transform=\"scale(1000) scale(1000)\"><rect transform=\"scale(1000)\" width=\"1\" height=\"1\"/></g>$close"))
        assertEquals("transform", refusal("$open<g transform=\"scale(1000) scale(1000)\"><rect transform=\"scale(1000.001)\" width=\"1\" height=\"1\"/></g>$close"))
        assertNull(refusal("<svg $ns viewBox=\"-1000 0 1 1\"><g transform=\"scale(1000)\"><rect transform=\"translate(999999)\"/></g></svg>"))
        assertEquals("transform", refusal("<svg $ns viewBox=\"-1000 0 1 1\"><g transform=\"scale(1000)\"><rect transform=\"translate(1000000)\"/></g></svg>"))
        assertEquals("transform", refusal(open + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(30) + "<rect width=\"1\" height=\"1\"/>" +
            "</g>".repeat(30) + close))

        //Many shapes under a long chain of groups
        val chained = open + ("<g transform=\"" + "scale(1) ".repeat(16) + "\">").repeat(30) + "<line/>".repeat(1_969) + "</g>".repeat(30) + close
        assertEquals(1_969, assertNotNull(SvgSubset.parse(chained)).shapes.size)

        //Number magnitude
        assertNull(refusal("$open<rect x=\"1000000\" y=\"-1e6\" width=\"1\" height=\"1\"/>$close"))
        assertEquals("number", refusal("$open<rect x=\"1000000.000001\" width=\"1\" height=\"1\"/>$close"))
        assertEquals("number", refusal("$open<rect y=\"-1000001\" width=\"1\" height=\"1\"/>$close"))

        //Text length in code points, after sanitising: an emoji is one character, a stripped zero-width space none
        assertNull(refusal(open + "<text>" + emoji.repeat(200) + "​</text>" + close))
        assertEquals("text", refusal(open + "<text>" + emoji.repeat(201) + "</text>" + close))
        val lines = described(open + "<text>" + "x".repeat(199) + emoji + "</text>" + close)
        assertTrue(lines[1].endsWith("content=" + "x".repeat(199) + emoji), lines[1])

        //Characters, counted before anything else
        val head = "<svg $ns viewBox=\"0 0 1 1\"><!-- "
        val tail = " --></svg>"
        val fits = head + "a".repeat(SvgSubset.MAX_CHARS - head.length - tail.length) + tail
        assertEquals(100_000, fits.length)
        assertNull(refusal(fits))
        assertEquals("length", refusal(head + "a".repeat(SvgSubset.MAX_CHARS + 1 - head.length - tail.length) + tail))
        assertEquals("length", refusal(null))
    }

    @Test
    fun numbersAreReadAsTheDesktopReadsThem() {
        //Double.parseDouble on the validated token: sign, digits, fraction, an exponent of at most 3 digits
        val open = "<svg $ns viewBox=\"0 0 10 10\">"
        fun x(value: String) = described("$open<rect x=\"$value\"/></svg>")[1].substringAfter("x=").substringBefore(' ')
        assertEquals("5", x("5."))
        assertEquals("0.5", x(".5"))
        assertEquals("5", x("+5"))
        assertEquals("0", x("1e-400"))
        assertEquals("12.5", x("1.25E1"))
        assertEquals("0.1", x("0.1px"))
        assertEquals("0.3", x("0.30000000000000004"))
        for(bad in listOf("NaN", "Infinity", "0x10", "1e0001", "1,5", "1em", "5%", "--1", ".", "+", "1e", "1.2.3")) {
            assertEquals("number", refusal("$open<rect x=\"$bad\"/></svg>"), bad)
        }
    }

    @Test
    fun numbersPrintOneWay() {
        assertEquals("0", SvgSubset.number(0.0))
        assertEquals("0", SvgSubset.number(-0.0))
        assertEquals("0", SvgSubset.number(-1e-17))
        assertEquals("0.3", SvgSubset.number(0.1 + 0.2))
        assertEquals("100", SvgSubset.number(100.0))
        assertEquals("-1000000", SvgSubset.number(-1e6))
        assertEquals("1000000000", SvgSubset.number(1e9))
        assertEquals("0.000001", SvgSubset.number(1e-6))
        assertEquals("0", SvgSubset.number(4e-7))
        assertEquals("0.707107", SvgSubset.number(Math.sqrt(0.5)))
        assertEquals("12.5", SvgSubset.number(12.5))
    }

    /** The printer against new BigDecimal(v).setScale(6, HALF_EVEN).stripTrailingZeros().toPlainString(), "0" for zero. */
    @Test
    fun numbersPrintAsBigDecimalDoes() {
        val random = Random(20261112)
        val values = ArrayList<Double>()
        //Random bit patterns of magnitude up to 1e9, subnormals and tiny values included
        while(values.size < 120_000) {
            val v = java.lang.Double.longBitsToDouble(random.nextLong())
            if(v.isFinite() && kotlin.math.abs(v) <= 1e9) values.add(v)
        }
        //Random mantissas at every binary exponent from 2^-40 to 2^30
        repeat(120_000) {
            val exponent = -40 + random.nextInt(71)
            values.add((if(random.nextBoolean()) -1 else 1) * Math.scalb(1 + random.nextDouble(), exponent))
        }
        //Exact ties at the seventh decimal: odd multiples of 1/128, and their neighbours
        repeat(60_000) {
            val q = (random.nextLong() and 0x1FFFFFFFFFL) or 1L
            val v = (if(random.nextBoolean()) -1 else 1) * q / 128.0
            values.add(v)
            values.add(Math.nextUp(v))
            values.add(Math.nextDown(v))
        }
        //Decimal literals ending in 5 at the seventh place, which a shortest-representation rounding gets wrong
        repeat(60_000) {
            val text = "${random.nextInt(1_000_000)}.${"%06d".format(random.nextInt(1_000_000))}5"
            values.add(text.toDouble())
            values.add(-text.toDouble())
        }
        values.addAll(listOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, 1e-300, 5e-7, -5e-7, Math.nextUp(5e-7),
            Math.nextDown(5e-7), 1.5e-6, 2.5e-6, 0.0000005, 1e9, -1e9, Math.nextDown(1e9), 1e15, 1e300, Double.MAX_VALUE, -Double.MAX_VALUE))
        assertTrue(values.size > 400_000, "${values.size}")
        for(v in values) {
            val rounded = BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN)
            val expected = if(rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
            assertEquals(expected, SvgSubset.number(v), "bits ${java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(v))}")
        }
    }

    @Test
    fun alphaRoundsAsTheDesktop() {
        val random = Random(255)
        val values = ArrayList<Double>()
        repeat(200_000) { values.add(random.nextDouble() * 256) }
        for(k in 0..255) {
            val tie = k + 0.5
            values.addAll(listOf(tie, Math.nextUp(tie), Math.nextDown(tie), k.toDouble(), k * 255 / 255.0))
        }
        values.addAll(listOf(0.49999999999999994, -0.5, -2.5, Double.NaN, 1e300, -1e300, 0.3 * 255))
        for(v in values) {
            assertEquals(Math.round(v), SvgSubset.javaRound(v), "$v")
        }
    }

    /** Not a parity check (the desktop's StrictMath and kotlin.math may differ in the last bit): how often they do here. */
    @Test
    fun platformTrigAgainstStrictMath() {
        val random = Random(360)
        var cosDiffers = 0
        var sinDiffers = 0
        val samples = 1_000_000
        repeat(samples) {
            val radians = random.nextDouble() * 360 / 180.0 * Math.PI
            if(kotlin.math.cos(radians) != StrictMath.cos(radians)) cosDiffers++
            if(kotlin.math.sin(radians) != StrictMath.sin(radians)) sinDiffers++
        }
        println("kotlin.math against StrictMath over $samples angles: cos differs $cosDiffers, sin differs $sinDiffers")
    }

    @Test
    fun textIsSanitised() {
        val open = "<svg $ns viewBox=\"0 0 10 10\"><text>"
        val close = "</text></svg>"
        val hostile = "‮ A​⁦B C\u0085\u009BD\r\n\tE&#x202D;&#10;F &#160; "
        val lines = described(open + hostile + close)
        assertTrue(lines[1].endsWith("content=ABCD E F  "), lines[1])
        val content = (assertNotNull(SvgSubset.parse(open + hostile + close)).shapes[0] as SvgSubset.Text).content
        assertEquals("ABCD E F  ", content)
    }

    /** The clean vectors' svg fields (left by the metadata task): SvgSubset's own text rule. */
    @Test
    fun cleanVectorsAsSvgText() {
        val clean = EvmVectors.obj("clean")
        val codePoints = clean["codePoints"]!!.jsonArray.map { it.jsonObject }
        assertEquals(97, codePoints.size)
        for(case in codePoints) {
            val c = case["codePoint"]!!.jsonPrimitive.int
            val text = "a" + String(Character.toChars(c)) + "b"
            assertEquals(case.str("svg"), SvgSubset.clean(text), "U+%04X".format(c))
        }
        val texts = clean["texts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(36, texts.size)
        var refused = 0
        for(case in texts) {
            val text = EvmVectors.input(case, "input")
            val expected = case["svg"]!!.jsonObject
            val actual = try {
                SvgSubset.clean(text)
            } catch(e: SvgSubset.Refused) {
                assertEquals("text", e.reason)
                null
            }
            if("refused" in expected) {
                assertNull(actual, case.str("label"))
                refused++
            } else {
                assertEquals(expected.str("value"), actual, case.str("label"))
            }
        }
        assertEquals(5, refused)
        assertEquals(EvmVectors.obj("constants")["svgMaxText"]!!.jsonPrimitive.int, SvgSubset.MAX_TEXT)
    }

    @Test
    fun noRegexOrRecursionHelpersInTheReader() {
        for(name in listOf("SvgSubset.kt")) {
            val text = File("src/commonMain/kotlin/com/sparrowwallet/mobile/crypto/evm/$name").readText()
            for(banned in listOf("Regex", "toRegex", ".split(", ".matches(", ".replace(", "Thread", "lowercase", "uppercase", ".toDoubleOrNull(")) {
                assertFalse(banned in text, "$name uses $banned")
            }
        }
    }
}
