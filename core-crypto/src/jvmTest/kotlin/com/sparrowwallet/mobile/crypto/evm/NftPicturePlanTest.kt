package com.sparrowwallet.mobile.crypto.evm

import com.sparrowwallet.mobile.crypto.evm.NftPicturePlan.Op
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picture's prepare step, held to the desktop's NftPictureTest (its pure cases, with the same expected numbers):
 * there are no vectors for it (ruling R4), so the cases are ported by hand.
 */
class NftPicturePlanTest {
    private val svgHead = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">"

    private fun drawing(svg: String): SvgSubset.Drawing = assertNotNull(SvgSubset.parse(svg), "not in the subset: $svg")

    private fun assertClose(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-3)

    private fun prepare(svg: String, size: Double): NftPicturePlan.Plan = assertNotNull(NftPicturePlan.prepare(drawing(svg), size))

    @Test
    fun aQuarterCircleArcIsOneCubic() {
        val cubics = NftPicturePlan.arcToBeziers(10.0, 0.0, 10.0, 10.0, 0.0, false, true, 0.0, 10.0)
        assertEquals(1, cubics.size)
        val k = 4.0 / 3.0 * tan(Math.PI / 8) * 10
        val c = cubics.first()
        assertClose(10.0, c[0])
        assertClose(k, c[1])
        assertClose(k, c[2])
        assertClose(10.0, c[3])
        assertEquals(0.0, c[4])
        assertEquals(10.0, c[5])
        //As the unit quarter circle has it: (1, 0.5523) and (0.5523, 1), here times 10
        assertClose(5.523, c[1])

        //The other sweep turns the other way round the other centre, (10, 10)
        val other = NftPicturePlan.arcToBeziers(10.0, 0.0, 10.0, 10.0, 0.0, false, false, 0.0, 10.0).first()
        assertClose(10 - k, other[0])
        assertClose(0.0, other[1])
        assertClose(0.0, other[2])
        assertClose(10 - k, other[3])
        assertEquals(0.0, other[4])
        assertEquals(10.0, other[5])

        //The large arc is the remaining three quarters of the circle round (10, 10)
        val large = NftPicturePlan.arcToBeziers(10.0, 0.0, 10.0, 10.0, 0.0, true, true, 0.0, 10.0)
        assertEquals(3, large.size)
        assertClose(20.0, large.first()[4])
        assertClose(10.0, large.first()[5])
        assertEquals(0.0, large.last()[4])
        assertEquals(10.0, large.last()[5])
    }

    @Test
    fun aZeroRadiusArcIsAStraightLine() {
        val cubics = NftPicturePlan.arcToBeziers(0.0, 0.0, 0.0, 10.0, 0.0, true, true, 20.0, 10.0)
        assertEquals(1, cubics.size)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 20.0, 10.0, 20.0, 10.0), cubics.first())
        assertTrue(NftPicturePlan.arcToBeziers(5.0, 5.0, 10.0, 10.0, 0.0, false, true, 5.0, 5.0).isEmpty(), "same end points draw nothing")
    }

    @Test
    fun radiiTooSmallAreScaledUp() {
        //Radius 1 cannot span 10: it becomes 5, a half circle through (5, -5) or (5, 5)
        val cubics = NftPicturePlan.arcToBeziers(0.0, 0.0, 1.0, 1.0, 0.0, false, true, 10.0, 0.0)
        assertEquals(2, cubics.size)
        assertClose(5.0, cubics.first()[4])
        assertClose(5.0, abs(cubics.first()[5]))
        assertEquals(10.0, cubics[1][4])
        assertEquals(0.0, cubics[1][5])
    }

    @Test
    fun everyFlagCombinationPicksItsCentreAndDirection() {
        //From (10, 0) to (0, 10) with radius 10: the centres are (0, 0) and (10, 10)
        val centres = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 10.0))
        //large, sweep -> centre index, number of quarter turns, the first piece's end point
        data class Case(val large: Boolean, val sweep: Boolean, val centre: Int, val count: Int, val firstEnd: DoubleArray)
        val cases = listOf(Case(false, true, 0, 1, doubleArrayOf(0.0, 10.0)), Case(false, false, 1, 1, doubleArrayOf(0.0, 10.0)),
            Case(true, true, 1, 3, doubleArrayOf(20.0, 10.0)), Case(true, false, 0, 3, doubleArrayOf(0.0, -10.0)))
        for(case in cases) {
            val centre = centres[case.centre]
            val cubics = NftPicturePlan.arcToBeziers(10.0, 0.0, 10.0, 10.0, 0.0, case.large, case.sweep, 0.0, 10.0)
            val label = "large=${case.large} sweep=${case.sweep}"
            assertEquals(case.count, cubics.size, label)
            assertClose(case.firstEnd[0], cubics.first()[4])
            assertClose(case.firstEnd[1], cubics.first()[5])
            for(cubic in cubics) {
                assertEquals(10.0, hypot(cubic[4] - centre[0], cubic[5] - centre[1]), 1e-9, label)
            }
            assertEquals(0.0, cubics.last()[4])
            assertEquals(10.0, cubics.last()[5])
        }
    }

    @Test
    fun degreesToRadiansAsTheDesktop() {
        //The desktop's Math.toRadians, which the arc's rotation goes through
        val random = Random(17)
        repeat(100_000) {
            val degrees = (random.nextDouble() - 0.5) * 2_000_000
            assertEquals(Math.toRadians(degrees % 360), NftPicturePlan.toRadians(degrees % 360))
        }
    }

    @Test
    fun theFitCentresAndScales() {
        val plan = prepare("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 32\"><rect width=\"64\" height=\"32\"/></svg>", 128.0)
        assertEquals(NftPicturePlan.Clip(0.0, 32.0, 128.0, 64.0), plan.clip)
        val rect = plan.items.first() as NftPicturePlan.PathItem
        assertEquals(SvgSubset.Matrix(2.0, 0.0, 0.0, 2.0, 0.0, 32.0), rect.affine)
    }

    @Test
    fun theVaultBadgePreparesWithoutError() {
        val svg = String(File("../vectors/evm/svg/accept/vault-badge-world-1.svg").readBytes(), Charsets.UTF_8)
        val plan = assertNotNull(NftPicturePlan.prepare(drawing(svg), NftPicturePlan.TILE_SIZE))
        assertEquals(7, plan.items.size)
        assertEquals(NftPicturePlan.Clip(0.0, 0.0, NftPicturePlan.TILE_SIZE, NftPicturePlan.TILE_SIZE), plan.clip)
        assertFiniteAndInside(plan)
        val text = plan.items.last() as NftPicturePlan.TextItem
        assertEquals("WORLD 1", text.content)
        assertEquals(SvgSubset.Anchor.MIDDLE, text.anchor)
        assertTrue(text.monospace)
        assertTrue(text.bold)
        assertEquals(0xFFFFFFFF.toInt(), text.fill)
        //Placed in pixels: the fit is 120 / 64
        assertEquals(text.x * 120 / 64, text.pixelX, 1e-9)
        assertEquals(9 * 120 / 64.0, text.fontPixels, 1e-9)
        val background = plan.items.first() as NftPicturePlan.PathItem
        assertEquals(0xFF0E1E38.toInt(), background.fill)
        assertNull(background.stroke)
    }

    @Test
    fun everyAcceptVectorPrepares() {
        val dir = File("../vectors/evm/svg/accept")
        val files = dir.listFiles()!!.filter { it.name.endsWith(".svg") }.sortedBy { it.name }
        assertEquals(23, files.size)
        for(file in files) {
            val drawing = drawing(String(file.readBytes(), Charsets.UTF_8))
            //The limit vectors sit at the model's edges, where the renderer may refuse; every other one is drawn
            val extreme = file.name.startsWith("limit-") || file.name.startsWith("transform-")
            for(size in doubleArrayOf(NftPicturePlan.TILE_SIZE, NftPicturePlan.DETAIL_SIZE, NftPicturePlan.MAX_PIXELS)) {
                val plan = NftPicturePlan.prepare(drawing, size)
                assertTrue(extreme || plan != null, file.name)
                plan?.let { assertFinite(it) }
            }
        }
    }

    @Test
    fun eachShapeIsPlacedByItsComposedMatrix() {
        val svg = "$svgHead<g transform=\"scale(2)\"><g transform=\"translate(3 4)\"><rect width=\"10\" height=\"10\" transform=\"translate(1 1)\"/></g></g></svg>"
        //At 64 pixels the fit is the identity, so the placement is the composed matrix itself
        val plan = prepare(svg, 64.0)
        val affine = plan.items.first().affine.toArray()
        val expected = doubleArrayOf(2.0, 0.0, 0.0, 2.0, 8.0, 10.0)
        for(k in 0 until 6) {
            assertEquals(expected[k], affine[k], 1e-9)
        }
        assertEquals(drawing(svg).shapes.first().composedMatrix, plan.items.first().affine)
    }

    @Test
    fun farAwayTextIsClippedLikeAnyShapeAndOnlyANearAbsurdFontRefuses() {
        //Far beyond the canvas a huge font cannot show: the text is left out, as far geometry is, and the picture stays
        val far = "$svgHead<rect width=\"8\" height=\"8\"/><text x=\"1000000\" font-size=\"4096\" transform=\"scale(100)\">A</text></svg>"
        assertEquals(1, prepare(far, NftPicturePlan.TILE_SIZE).items.size)
    }

    @Test
    fun eachPictureCostsItsCommands() {
        val pixels = NftPicturePlan.pixels(NftPicturePlan.TILE_SIZE, 1.0)
        //Move, three lines and close, filled only
        assertEquals(5, prepare("$svgHead<rect width=\"8\" height=\"8\"/></svg>", pixels).cost)
        //Traced twice, to fill and to stroke
        assertEquals(10, prepare("$svgHead<rect width=\"8\" height=\"8\" stroke=\"red\"/></svg>", pixels).cost)
        //A text counts one; nothing drawn still costs one
        assertEquals(1, prepare("$svgHead<text>A</text></svg>", pixels).cost)
        assertEquals(1, prepare("$svgHead</svg>", pixels).cost)
    }

    @Test
    fun aHugeFontRefusesThePicture() {
        val hugeText = "$svgHead<text font-size=\"4096\" transform=\"scale(100)\">A</text></svg>"
        assertNull(NftPicturePlan.prepare(drawing(hugeText), NftPicturePlan.TILE_SIZE))
    }

    @Test
    fun aHugeBackgroundIsDrawnNotRefused() {
        //Far beyond the 24-unit box, as a background rectangle or a huge circle whose edge crosses it
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><rect x=\"-500000\" y=\"-500000\" width=\"1000000\" height=\"1000000\" fill=\"red\"/>" +
            "<circle cx=\"12\" cy=\"1000000\" r=\"999988\" fill=\"blue\" stroke=\"black\"/><rect width=\"10\" height=\"10\" transform=\"scale(1000)\" fill=\"lime\"/>" +
            "<text x=\"1000000\" y=\"12\">far away</text></svg>"
        assertNotNull(NftPicturePlan.prepare(drawing(svg), NftPicturePlan.TILE_SIZE))
        val plan = prepare(svg, NftPicturePlan.MAX_PIXELS)
        assertFinite(plan)
        //The far text cannot reach the canvas and is left out
        assertEquals(3, plan.items.size)
        var commands = 0
        for(item in plan.items) {
            val path = item as NftPicturePlan.PathItem
            val m = path.affine
            val all = path.fillPath!! + (path.strokePath ?: emptyList())
            commands += all.size
            for(cmd in all) {
                if(cmd.op != Op.CUBIC) {
                    continue
                }
                //Every curve handed to the canvas lies within a bounded box around it
                for(i in 0 until 6 step 2) {
                    assertTrue(abs(m.a * cmd[i] + m.c * cmd[i + 1] + m.e) <= NftPicturePlan.CURVE_LIMIT)
                    assertTrue(abs(m.b * cmd[i] + m.d * cmd[i + 1] + m.f) <= NftPicturePlan.CURVE_LIMIT)
                }
            }
        }
        assertTrue(commands < 2000, "commands: $commands")
    }

    @Test
    fun aPictureOverTheCommandCapIsNoPicture() {
        val svg = StringBuilder("$svgHead<path d=\"M0 0")
        repeat(300) { svg.append(" l1 1") }
        svg.append("\"/></svg>")
        val drawing = drawing(svg.toString())
        assertEquals(50_000, NftPicturePlan.MAX_COMMANDS)
        assertNotNull(NftPicturePlan.prepare(drawing, 64.0, 1000))
        assertNull(NftPicturePlan.prepare(drawing, 64.0, 300))
        //Exactly at the cap: 301 commands
        assertNotNull(NftPicturePlan.prepare(drawing, 64.0, 301))
        assertNull(NftPicturePlan.prepare(drawing, 64.0, 300))
    }

    @Test
    fun aHugeStrokeIsClampedToTheCanvasDiagonal() {
        val svg = "$svgHead<line x1=\"1\" y1=\"1\" x2=\"5\" y2=\"5\" stroke=\"red\" stroke-width=\"1000000\"/><line x2=\"5\" stroke=\"red\" stroke-width=\"2\"/></svg>"
        val items = prepare(svg, NftPicturePlan.TILE_SIZE).items
        val huge = items[0] as NftPicturePlan.PathItem
        val scale = NftPicturePlan.TILE_SIZE / 64
        assertEquals(NftPicturePlan.TILE_SIZE * sqrt(2.0), huge.strokeWidth * scale, 1e-6)
        assertEquals(NftPicturePlan.TILE_SIZE * sqrt(2.0), huge.strokePixels, 1e-6)
        assertEquals(2.0, (items[1] as NftPicturePlan.PathItem).strokeWidth)
        assertEquals(2.0 * scale, (items[1] as NftPicturePlan.PathItem).strokePixels, 1e-9)
    }

    @Test
    fun pixelsForTheTileAndDetailSizes() {
        assertEquals(240.0, NftPicturePlan.pixels(NftPicturePlan.TILE_SIZE, 2.0))
        assertEquals(NftPicturePlan.MAX_PIXELS, NftPicturePlan.pixels(NftPicturePlan.DETAIL_SIZE, 8.0))
        assertEquals(120.0, NftPicturePlan.pixels(NftPicturePlan.TILE_SIZE, Double.NaN))
        assertEquals(16.0, NftPicturePlan.pixels(1.0, 0.5))
    }

    @Test
    fun whatSvgDoesNotRenderIsSkipped() {
        val svg = "$svgHead<rect width=\"0\" height=\"10\" stroke=\"red\"/><circle r=\"0\" stroke=\"red\"/><ellipse rx=\"5\" ry=\"0\" stroke=\"red\"/>" +
            "<line x2=\"10\" y2=\"10\" stroke=\"red\" stroke-width=\"0\"/><rect width=\"5\" height=\"5\" fill=\"none\"/></svg>"
        assertTrue(prepare(svg, NftPicturePlan.TILE_SIZE).items.isEmpty())
    }

    @Test
    fun aLineIsNeverFilledAndAPolylineIsFilledClosedButStrokedOpen() {
        val svg = "$svgHead<line x2=\"10\" y2=\"10\" stroke=\"red\"/><polyline points=\"0,0 10,0 10,10\" stroke=\"blue\"/></svg>"
        val items = prepare(svg, 64.0).items
        assertEquals(2, items.size)
        val line = items[0] as NftPicturePlan.PathItem
        assertNull(line.fill)
        assertNull(line.fillPath)
        assertNotNull(line.stroke)
        val polyline = items[1] as NftPicturePlan.PathItem
        assertEquals(Op.CLOSE, polyline.fillPath!!.last().op)
        assertNotEquals(Op.CLOSE, polyline.strokePath!!.last().op)
    }

    @Test
    fun everyPathCommandBecomesMovesLinesAndCubics() {
        val svg = "$svgHead<path d=\"M1 1 L5 5 H10 V12 C1 2 3 4 5 6 S7 8 9 10 Q1 1 2 2 T4 4 A5 5 0 0 1 20 20 A0 3 0 1 1 30 30 Z L40 40\"/></svg>"
        val path = prepare(svg, 64.0).items.first() as NftPicturePlan.PathItem
        //After Z the current point is the subpath's start again, so the final line starts from (1, 1)
        assertEquals(Op.LINE, path.fillPath!!.last().op)
        //Quadratics raised exactly: Q from (9, 10) with control (1, 1) to (2, 2), then T reflecting it to (3, 3) on to (4, 4)
        val cubics = path.fillPath!!.filter { it.op == Op.CUBIC }
        assertEquals(listOf(9 + 2.0 / 3.0 * (1 - 9), 10 + 2.0 / 3.0 * (1 - 10), 2 + 2.0 / 3.0 * (1 - 2), 2 + 2.0 / 3.0 * (1 - 2), 2.0, 2.0),
            (0 until 6).map { cubics[2][it] })
        assertEquals(listOf(2 + 2.0 / 3.0 * (3 - 2), 2 + 2.0 / 3.0 * (3 - 2), 4 + 2.0 / 3.0 * (3 - 4), 4 + 2.0 / 3.0 * (3 - 4), 4.0, 4.0),
            (0 until 6).map { cubics[3][it] })
    }

    @Test
    fun paintAndOpacityAreCarried() {
        val svg = "$svgHead<g opacity=\"0.5\"><rect width=\"8\" height=\"8\" fill=\"#102030\" fill-opacity=\"0.25\" stroke=\"red\" stroke-width=\"3\"/></g></svg>"
        val rect = prepare(svg, 64.0).items.first() as NftPicturePlan.PathItem
        assertEquals(0x40102030, rect.fill)
        assertEquals(0xFFFF0000.toInt(), rect.stroke)
        assertEquals(0.5, rect.opacity)
        assertEquals(3.0, rect.strokeWidth)
        //Filled and stroked along the same commands
        assertTrue(rect.fillPath === rect.strokePath)
    }

    @Test
    fun nothingThrows() {
        //Every accept vector, and seeded random edits of them, at several sizes: a plan or null, never an exception
        val started = System.nanoTime()
        val dir = File("../vectors/evm/svg/accept")
        val vectors = dir.listFiles()!!.sortedBy { it.name }.map { String(it.readBytes(), Charsets.UTF_8) }
        val random = Random(20261112)
        val alphabet = "0123456789.-e, MmLlCcAaZzQqTtSsHhVv()"
        var prepared = 0
        for(vector in vectors) {
            repeat(if(vector.length <= 5_000) 600 else 5) {
                val edited = StringBuilder(vector)
                repeat(1 + random.nextInt(3)) {
                    val at = random.nextInt(edited.length + 1)
                    val c = alphabet[random.nextInt(alphabet.length)]
                    if(random.nextBoolean()) edited.insert(at, c) else if(at < edited.length) edited.setCharAt(at, c)
                }
                val drawing = SvgSubset.parse(edited.toString()) ?: return@repeat
                for(size in doubleArrayOf(16.0, NftPicturePlan.TILE_SIZE, NftPicturePlan.MAX_PIXELS)) {
                    NftPicturePlan.prepare(drawing, size)?.let { assertFinite(it); prepared++ }
                }
            }
        }
        //Odd sizes
        val badge = drawing(vectors.last { it.contains("WORLD 1") })
        for(size in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e300, Double.MIN_VALUE)) {
            NftPicturePlan.prepare(badge, size)?.let { assertFinite(it) }
        }
        assertTrue(prepared > 1_000, "$prepared")
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(seconds < 120, "took $seconds s")
    }

    private fun assertFinite(plan: NftPicturePlan.Plan) {
        for(item in plan.items) {
            item.affine.toArray().forEach { assertTrue(it.isFinite()) }
            if(item is NftPicturePlan.PathItem) {
                for(cmd in (item.fillPath ?: emptyList()) + (item.strokePath ?: emptyList())) {
                    for(i in 0 until cmd.size) {
                        assertTrue(cmd[i].isFinite())
                    }
                }
                assertTrue(item.strokeWidth.isFinite() && item.strokePixels.isFinite())
            } else if(item is NftPicturePlan.TextItem) {
                assertTrue(item.pixelX.isFinite() && item.pixelY.isFinite() && item.fontPixels.isFinite() && item.fontPixels <= NftPicturePlan.MAX_FONT_PIXELS)
            }
        }
    }

    //The badge stays inside its 64 x 64 box, so everything maps inside the tile
    private fun assertFiniteAndInside(plan: NftPicturePlan.Plan) {
        assertFinite(plan)
        for(item in plan.items) {
            if(item is NftPicturePlan.PathItem) {
                val m = item.affine
                for(cmd in item.fillPath!!) {
                    for(i in 0 until cmd.size - 1 step 2) {
                        val x = m.a * cmd[i] + m.c * cmd[i + 1] + m.e
                        val y = m.b * cmd[i] + m.d * cmd[i + 1] + m.f
                        assertTrue(x >= -1e-9 && x <= NftPicturePlan.TILE_SIZE + 1e-9 && y >= -1e-9 && y <= NftPicturePlan.TILE_SIZE + 1e-9)
                    }
                }
            }
        }
    }
}
