package com.sparrowwallet.mobile.crypto.evm

/**
 * An NFT picture laid out for drawing: port of the pure stage of the desktop's NftPicture.prepare, rule for rule, with
 * the same numbers. It places every shape (the fit to the canvas composed with the shape's matrix), turns every shape
 * into moves, lines and cubic curves (arcs by the SVG implementation notes, F.6.5 and F.6.6), skips what SVG itself does
 * not render, keeps far geometry within reach of the canvas (curves split or straightened, strokes cut to the canvas's
 * diagonal), and refuses the whole picture (null, the caller shows "No picture") when a value is not finite, a font
 * would be over 4,096 pixels, or the picture needs more than [MAX_COMMANDS] commands. Pure: no drawing library.
 *
 * The drawing contract for the Android layer (the desktop's canvas replay, in Android terms):
 *   - Create a transparent ARGB bitmap of [Plan.size] x [Plan.size] pixels, off the main thread. Any exception while
 *     drawing means "No picture", never a partial bitmap.
 *   - clipRect([Plan.clip]) under the identity matrix (the clip is in pixels).
 *   - For each of [Plan.items] in order: save; set the canvas matrix from the item's [Item.affine] (a, b, c, d, e, f) as
 *     the 3x3 [a c e; b d f; 0 0 1], skipping the item if the determinant a d - b c is 0 or not finite. Paint
 *     anti-aliased, colour = the item's 0xAARRGGBB paint with alpha = round(A x [Item.opacity]); never an offscreen layer.
 *   - A [PathItem]: MOVE = moveTo, LINE = lineTo, CUBIC = cubicTo, CLOSE = close, non-zero winding. Fill
 *     [PathItem.fillPath] with [PathItem.fill] if it has a fill; stroke [PathItem.strokePath] with [PathItem.stroke] if it
 *     has a stroke, width = [PathItem.strokeWidth] in shape units (the matrix scales it, as SVG strokes are), butt caps,
 *     miter joins, miter limit 4, and no stroke at all when that width as a float is 0.
 *   - A [TextItem]: monospace or sans-serif typeface ([TextItem.monospace]), bold as given ([TextItem.bold]), alignment
 *     from [TextItem.anchor] (start left, middle centre, end right); translate to ([TextItem.x], [TextItem.y]), scale by
 *     [TextItem.size] / [TextItem.fontPixels], text size = [TextItem.fontPixels], draw [TextItem.content] at (0, 0), which
 *     is the baseline; fill first with [TextItem.fill], then stroke with [TextItem.stroke] at [TextItem.strokeWidth] if set.
 *   - Restore.
 * The pixel figures ([PathItem.strokePixels], [TextItem.strokePixels], [TextItem.pixelX], [TextItem.pixelY]) are the
 * same values placed, for a renderer that wants to skip or clamp by them; drawing under the affine is the faithful way.
 */
object NftPicturePlan {
    const val TILE_SIZE = 120.0
    const val DETAIL_SIZE = 240.0

    /** The largest canvas side drawn, in pixels (a detail picture on a 2x screen fits). */
    const val MAX_PIXELS = 1024.0

    /** The most path commands one picture may hand to the canvas, after arcs and far curves are converted. */
    const val MAX_COMMANDS = 50_000

    /**
     * The canvas is at most 1,024 pixels, so nothing beyond this many pixels from it can show. A curve lying wholly
     * beyond this box is drawn as the straight line between its ends (the same pixels inside the box).
     */
    const val CLIP_LIMIT = 1e7

    /** Every curve handed to the canvas lies within this many pixels: one reaching further is split until it does. */
    const val CURVE_LIMIT = 2e7
    private const val MAX_SPLITS = 64

    /** A font beyond this many pixels refuses the picture. */
    const val MAX_FONT_PIXELS = 4096.0

    //Bezier handle length for a quarter circle of radius 1
    private val KAPPA = 4.0 / 3.0 * (kotlin.math.sqrt(2.0) - 1)

    //As the desktop's Math.toRadians: degrees times this constant
    private const val DEGREES_TO_RADIANS = 0.017453292519943295

    enum class Op { MOVE, LINE, CUBIC, CLOSE }

    /** One path command: MOVE (x y), LINE (x y), CUBIC (x1 y1 x2 y2 x y) or CLOSE, in the shape's own coordinates. */
    class Cmd internal constructor(val op: Op, private val coords: DoubleArray) {
        val size: Int get() = coords.size

        operator fun get(index: Int): Double = coords[index]

        override fun toString(): String = "$op${coords.contentToString()}"
    }

    sealed interface Item {
        /** Shape coordinates to canvas pixels, {a, b, c, d, e, f} in SVG order. */
        val affine: SvgSubset.Matrix

        /** The shape's opacity (its own times its groups'), applied as a global alpha on top of the paint's alpha. */
        val opacity: Double
    }

    /**
     * A filled and/or stroked path; a null path or paint is not drawn. The lists are read-only; when filled and stroked
     * along the same commands, the two lists are one.
     */
    class PathItem internal constructor(override val affine: SvgSubset.Matrix, override val opacity: Double, val fill: Int?, fillPath: List<Cmd>?,
                                        val stroke: Int?, val strokeWidth: Double, strokePath: List<Cmd>?) : Item {
        val fillPath: List<Cmd>? = fillPath?.let { SvgSubset.ReadOnlyList(it) }
        val strokePath: List<Cmd>? = if(strokePath != null && strokePath === fillPath) this.fillPath else strokePath?.let { SvgSubset.ReadOnlyList(it) }

        /** The stroke's width in pixels along the affine's longer axis, at most the canvas's diagonal; 0 when not stroked. */
        val strokePixels: Double = if(strokePath == null) 0.0 else strokeWidth * stretch(affine)
    }

    class TextItem internal constructor(override val affine: SvgSubset.Matrix, override val opacity: Double, val fill: Int?, val stroke: Int?,
                                        val strokeWidth: Double, val x: Double, val y: Double, val size: Double, val anchor: SvgSubset.Anchor,
                                        val bold: Boolean, val monospace: Boolean, val content: String, val pixelX: Double, val pixelY: Double,
                                        val fontPixels: Double) : Item {
        /** The stroke's width in pixels along the affine's longer axis, clamped as a path's is; 0 when not stroked. */
        val strokePixels: Double = if(stroke == null) 0.0 else strokeWidth * stretch(affine)
    }

    /** A rectangle in canvas pixels: the drawing's box after the fit. */
    data class Clip(val x: Double, val y: Double, val width: Double, val height: Double)

    /** A canvas of size x size pixels, the clip rectangle in pixels, and what to draw in order. */
    class Plan internal constructor(val size: Double, val clip: Clip, items: List<Item>) {
        val items: List<Item> = SvgSubset.ReadOnlyList(items)

        /** The path commands traced to draw it (a path filled and stroked is traced twice), a text counting one; at least 1. */
        val cost: Int = run {
            var commands = 0
            for(item in this.items) {
                if(item is PathItem) {
                    commands += (item.fillPath?.size ?: 0) + (item.strokePath?.size ?: 0)
                } else {
                    commands++
                }
            }
            kotlin.math.max(1, commands)
        }
    }

    /** The canvas side in pixels for a picture of this size (clamped to 16..512) on a screen of this scale. */
    fun pixels(size: Double, scale: Double): Double {
        val side = kotlin.math.max(16.0, kotlin.math.min(512.0, size))
        val factor = if(scale.isFinite()) kotlin.math.max(1.0, scale) else 1.0
        return kotlin.math.min(MAX_PIXELS, kotlin.math.ceil(side * factor))
    }

    /**
     * The drawing laid out on a square canvas of size pixels, or null when any value is not finite, a font would be over
     * 4,096 pixels, or the picture needs more than maxCommands path commands. Far geometry is not refused: a curve
     * reaching far off the canvas is split until its pieces are near enough or wholly beyond [CLIP_LIMIT], where they
     * become straight lines; text anchored beyond it is left out; a stroke wider than the canvas's diagonal is drawn at
     * that width. Never throws.
     */
    fun prepare(drawing: SvgSubset.Drawing, size: Double): Plan? = prepare(drawing, size, MAX_COMMANDS)

    /** As [prepare] with another command cap, for tests. Never throws. */
    internal fun prepare(drawing: SvgSubset.Drawing, size: Double, maxCommands: Int): Plan? = try {
        prepareOrNull(drawing, size, maxCommands)
    } catch(e: Exception) {
        null
    }

    private class Budget(var left: Int)

    /** The work of [prepare] without its catch, so a fuzz test fails on any exception. */
    internal fun prepareOrNull(drawing: SvgSubset.Drawing, size: Double, maxCommands: Int): Plan? {
        val fit = fit(drawing.width, drawing.height, size)
        if(!finite(fit)) {
            return null
        }
        val clip = doubleArrayOf(fit[4], fit[5], drawing.width * fit[0], drawing.height * fit[3])
        if(!finite(clip)) {
            return null
        }
        val diagonal = size * kotlin.math.sqrt(2.0)
        val budget = Budget(maxCommands)
        val items = ArrayList<Item>()
        for(shape in drawing.shapes) {
            val affine = multiply(fit, shape.composedMatrix.toArray())
            if(!finite(affine) || !stretch(affine).isFinite() || !shape.opacity.isFinite()) {
                return null
            }
            if(shape is SvgSubset.Text) {
                //As for every shape: a value that is not finite refuses the picture, and far away is left out
                val anchor = apply(affine, shape.x, shape.y)
                val fontPixels = shape.size * stretch(affine)
                if(!finite(anchor) || !fontPixels.isFinite()) {
                    return null
                }
                if(kotlin.math.abs(anchor[0]) > CLIP_LIMIT || kotlin.math.abs(anchor[1]) > CLIP_LIMIT) {
                    //At most 200 characters of at most 4,096 pixels: it cannot reach the canvas
                    continue
                }
                val item = text(shape, affine, diagonal, anchor, fontPixels) ?: continue
                if(fontPixels > MAX_FONT_PIXELS || --budget.left < 0) {
                    return null
                }
                items.add(item)
                continue
            }
            val item = path(shape, affine, diagonal) ?: continue
            val fillPath = if(item.fillPath == null) null else bound(item.fillPath, affine, budget)
            val strokePath = if(item.strokePath == null) null else if(item.strokePath === item.fillPath) fillPath else bound(item.strokePath, affine, budget)
            if((item.fillPath != null && fillPath == null) || (item.strokePath != null && strokePath == null)) {
                return null
            }
            if(strokePath != null && strokePath === fillPath) {
                //The same commands are traced twice, once to fill and once to stroke
                budget.left -= strokePath.size
                if(budget.left < 0) {
                    return null
                }
            }
            items.add(PathItem(item.affine, item.opacity, item.fill, fillPath, item.stroke, item.strokeWidth, strokePath))
        }
        return Plan(size, Clip(clip[0], clip[1], clip[2], clip[3]), items)
    }

    /**
     * The commands with every curve near the canvas: one whose control points (which bound it) all lie within
     * [CURVE_LIMIT] pixels is kept; one wholly beyond [CLIP_LIMIT] becomes a line to its end, which covers the same
     * pixels inside that box; any other is split in half and each half handled the same way. Null when a placed point is
     * not finite or the commands outgrow the budget.
     */
    private fun bound(cmds: List<Cmd>, m: DoubleArray, budget: Budget): List<Cmd>? {
        val out = ArrayList<Cmd>(cmds.size)
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        for(cmd in cmds) {
            var i = 0
            while(i + 1 < cmd.size) {
                if(!finite(apply(m, cmd[i], cmd[i + 1]))) {
                    return null
                }
                i += 2
            }
            when(cmd.op) {
                Op.MOVE -> {
                    out.add(cmd)
                    cx = cmd[0]
                    sx = cx
                    cy = cmd[1]
                    sy = cy
                }
                Op.LINE -> {
                    out.add(cmd)
                    cx = cmd[0]
                    cy = cmd[1]
                }
                Op.CUBIC -> {
                    if(!curve(out, m, doubleArrayOf(cx, cy, cmd[0], cmd[1], cmd[2], cmd[3], cmd[4], cmd[5]), budget.left)) {
                        return null
                    }
                    cx = cmd[4]
                    cy = cmd[5]
                }
                Op.CLOSE -> {
                    out.add(cmd)
                    cx = sx
                    cy = sy
                }
            }
            if(out.size > budget.left) {
                return null
            }
        }
        budget.left -= out.size
        return out
    }

    /**
     * One cubic {x0, y0, x1, y1, x2, y2, x3, y3} from the current point, kept, straightened or split (see bound), in the
     * desktop's order (first half before second half, depth first, at most [MAX_SPLITS] halvings). Done with an explicit
     * stack rather than recursion; false as soon as out holds more than limit commands, which the desktop would refuse
     * once the curve was done.
     */
    private fun curve(out: MutableList<Cmd>, m: DoubleArray, first: DoubleArray, limit: Int): Boolean {
        val pending = ArrayList<DoubleArray>()
        val depths = ArrayList<Int>()
        pending.add(first)
        depths.add(0)
        while(pending.isNotEmpty()) {
            val p = pending.removeAt(pending.size - 1)
            val depth = depths.removeAt(depths.size - 1)
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for(i in 0 until 8 step 2) {
                val d = apply(m, p[i], p[i + 1])
                minX = kotlin.math.min(minX, d[0])
                maxX = kotlin.math.max(maxX, d[0])
                minY = kotlin.math.min(minY, d[1])
                maxY = kotlin.math.max(maxY, d[1])
            }
            val near = minX >= -CURVE_LIMIT && maxX <= CURVE_LIMIT && minY >= -CURVE_LIMIT && maxY <= CURVE_LIMIT
            if(near) {
                out.add(Cmd(Op.CUBIC, doubleArrayOf(p[2], p[3], p[4], p[5], p[6], p[7])))
            } else if(maxX < -CLIP_LIMIT || minX > CLIP_LIMIT || maxY < -CLIP_LIMIT || minY > CLIP_LIMIT || depth >= MAX_SPLITS) {
                out.add(Cmd(Op.LINE, doubleArrayOf(p[6], p[7])))
            } else {
                //de Casteljau at t = 1/2; the second half is pushed first so the first is handled first
                val x01 = (p[0] + p[2]) / 2
                val y01 = (p[1] + p[3]) / 2
                val x12 = (p[2] + p[4]) / 2
                val y12 = (p[3] + p[5]) / 2
                val x23 = (p[4] + p[6]) / 2
                val y23 = (p[5] + p[7]) / 2
                val xa = (x01 + x12) / 2
                val ya = (y01 + y12) / 2
                val xb = (x12 + x23) / 2
                val yb = (y12 + y23) / 2
                val xm = (xa + xb) / 2
                val ym = (ya + yb) / 2
                pending.add(doubleArrayOf(xm, ym, xb, yb, x23, y23, p[6], p[7]))
                depths.add(depth + 1)
                pending.add(doubleArrayOf(p[0], p[1], x01, y01, xa, ya, xm, ym))
                depths.add(depth + 1)
            }
            if(out.size > limit) {
                return false
            }
        }
        return true
    }

    private fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray = doubleArrayOf(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])

    /** Scale to fit, centred: {s, 0, 0, s, dx, dy}. */
    internal fun fit(width: Double, height: Double, size: Double): DoubleArray {
        val scale = kotlin.math.min(size / width, size / height)
        return doubleArrayOf(scale, 0.0, 0.0, scale, (size - width * scale) / 2, (size - height * scale) / 2)
    }

    /** p followed by t, as SvgSubset composes: (p t)(x) = p(t(x)). */
    private fun multiply(p: DoubleArray, t: DoubleArray): DoubleArray = doubleArrayOf(p[0] * t[0] + p[2] * t[1], p[1] * t[0] + p[3] * t[1],
        p[0] * t[2] + p[2] * t[3], p[1] * t[2] + p[3] * t[3], p[0] * t[4] + p[2] * t[5] + p[4], p[1] * t[4] + p[3] * t[5] + p[5])

    /** How much the matrix can stretch a length: the longer of its two column vectors. */
    private fun stretch(m: DoubleArray): Double = kotlin.math.max(kotlin.math.hypot(m[0], m[1]), kotlin.math.hypot(m[2], m[3]))

    private fun stretch(m: SvgSubset.Matrix): Double = kotlin.math.max(kotlin.math.hypot(m.a, m.b), kotlin.math.hypot(m.c, m.d))

    /** A stroke width in the shape's units, cut so it is at most the canvas's diagonal once placed; 0 when it cannot show. */
    private fun strokeWidth(width: Double, m: DoubleArray, diagonal: Double): Double {
        val stretch = stretch(m)
        if(!(stretch > 0)) {
            return 0.0
        }
        return if(width * stretch > diagonal) diagonal / stretch else width
    }

    private fun finite(values: DoubleArray): Boolean {
        for(value in values) {
            if(!value.isFinite()) {
                return false
            }
        }
        return true
    }

    private fun text(text: SvgSubset.Text, affine: DoubleArray, diagonal: Double, anchor: DoubleArray, fontPixels: Double): TextItem? {
        val fill = text.fill?.argb
        val strokeWidth = if(text.strokeWidth > 0) strokeWidth(text.strokeWidth, affine, diagonal) else 0.0
        val stroke = if(strokeWidth > 0) text.stroke?.argb else null
        if((fill == null && stroke == null) || text.content.isEmpty() || !(text.size > 0)) {
            return null
        }
        return TextItem(SvgSubset.Matrix.of(affine), text.opacity, fill, stroke, strokeWidth, text.x, text.y, text.size, text.anchor, text.bold,
            text.monospace, text.content, anchor[0], anchor[1], fontPixels)
    }

    /** The shape as paths, or null when SVG would draw nothing (zero size, no paint, a stroke of width 0). */
    private fun path(shape: SvgSubset.Shape, affine: DoubleArray, diagonal: Double): PathItem? {
        var fill = shape.fill?.argb
        val strokeWidth = if(shape.strokeWidth > 0) strokeWidth(shape.strokeWidth, affine, diagonal) else 0.0
        val stroke = if(strokeWidth > 0) shape.stroke?.argb else null
        var outline: List<Cmd>?
        var fillPath: List<Cmd>?
        when(shape) {
            is SvgSubset.Rect -> {
                if(!(shape.width > 0) || !(shape.height > 0)) {
                    return null
                }
                outline = rect(shape.x, shape.y, shape.width, shape.height, shape.rx, shape.ry)
                fillPath = outline
            }
            is SvgSubset.Circle -> {
                if(!(shape.r > 0)) {
                    return null
                }
                outline = ellipse(shape.cx, shape.cy, shape.r, shape.r)
                fillPath = outline
            }
            is SvgSubset.Ellipse -> {
                if(!(shape.rx > 0) || !(shape.ry > 0)) {
                    return null
                }
                outline = ellipse(shape.cx, shape.cy, shape.rx, shape.ry)
                fillPath = outline
            }
            is SvgSubset.Line -> {
                outline = listOf(Cmd(Op.MOVE, doubleArrayOf(shape.x1, shape.y1)), Cmd(Op.LINE, doubleArrayOf(shape.x2, shape.y2)))
                //A line is never filled
                fillPath = null
                fill = null
            }
            is SvgSubset.Polyline -> {
                val open = ArrayList<Cmd>()
                for(i in 0 until shape.pointCount) {
                    open.add(Cmd(if(i == 0) Op.MOVE else Op.LINE, doubleArrayOf(shape.x(i), shape.y(i))))
                }
                val closed = ArrayList<Cmd>(open)
                closed.add(Cmd(Op.CLOSE, DoubleArray(0)))
                //Filled as if closed, stroked open unless it is a polygon
                fillPath = closed
                outline = if(shape.closed) closed else open
            }
            is SvgSubset.PathShape -> {
                outline = path(shape.segments)
                fillPath = outline
            }
            is SvgSubset.Text -> return null
        }
        if(fill == null) {
            fillPath = null
        }
        if(stroke == null) {
            outline = null
        }
        if(fillPath == null && outline == null) {
            return null
        }
        return PathItem(SvgSubset.Matrix.of(affine), shape.opacity, fill, fillPath, stroke, strokeWidth, outline)
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double, rx: Double, ry: Double): List<Cmd> {
        val cmds = ArrayList<Cmd>()
        if(!(rx > 0) || !(ry > 0)) {
            cmds.add(Cmd(Op.MOVE, doubleArrayOf(x, y)))
            cmds.add(Cmd(Op.LINE, doubleArrayOf(x + w, y)))
            cmds.add(Cmd(Op.LINE, doubleArrayOf(x + w, y + h)))
            cmds.add(Cmd(Op.LINE, doubleArrayOf(x, y + h)))
            cmds.add(Cmd(Op.CLOSE, DoubleArray(0)))
            return cmds
        }
        val kx = KAPPA * rx
        val ky = KAPPA * ry
        cmds.add(Cmd(Op.MOVE, doubleArrayOf(x + rx, y)))
        cmds.add(Cmd(Op.LINE, doubleArrayOf(x + w - rx, y)))
        cmds.add(Cmd(Op.CUBIC, doubleArrayOf(x + w - rx + kx, y, x + w, y + ry - ky, x + w, y + ry)))
        cmds.add(Cmd(Op.LINE, doubleArrayOf(x + w, y + h - ry)))
        cmds.add(Cmd(Op.CUBIC, doubleArrayOf(x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h)))
        cmds.add(Cmd(Op.LINE, doubleArrayOf(x + rx, y + h)))
        cmds.add(Cmd(Op.CUBIC, doubleArrayOf(x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry)))
        cmds.add(Cmd(Op.LINE, doubleArrayOf(x, y + ry)))
        cmds.add(Cmd(Op.CUBIC, doubleArrayOf(x, y + ry - ky, x + rx - kx, y, x + rx, y)))
        cmds.add(Cmd(Op.CLOSE, DoubleArray(0)))
        return cmds
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<Cmd> {
        val kx = KAPPA * rx
        val ky = KAPPA * ry
        return listOf(
            Cmd(Op.MOVE, doubleArrayOf(cx + rx, cy)),
            Cmd(Op.CUBIC, doubleArrayOf(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)),
            Cmd(Op.CUBIC, doubleArrayOf(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)),
            Cmd(Op.CUBIC, doubleArrayOf(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)),
            Cmd(Op.CUBIC, doubleArrayOf(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)),
            Cmd(Op.CLOSE, DoubleArray(0)))
    }

    /** Path segments as moves, lines and cubics: quadratics raised exactly, arcs converted. */
    private fun path(segments: List<SvgSubset.Segment>): List<Cmd> {
        val cmds = ArrayList<Cmd>(segments.size)
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        for(segment in segments) {
            when(segment) {
                is SvgSubset.MoveTo -> {
                    cmds.add(Cmd(Op.MOVE, doubleArrayOf(segment.x, segment.y)))
                    cx = segment.x
                    sx = cx
                    cy = segment.y
                    sy = cy
                }
                is SvgSubset.LineTo -> {
                    cmds.add(Cmd(Op.LINE, doubleArrayOf(segment.x, segment.y)))
                    cx = segment.x
                    cy = segment.y
                }
                is SvgSubset.CubicTo -> {
                    cmds.add(Cmd(Op.CUBIC, doubleArrayOf(segment.x1, segment.y1, segment.x2, segment.y2, segment.x, segment.y)))
                    cx = segment.x
                    cy = segment.y
                }
                is SvgSubset.QuadTo -> {
                    cmds.add(Cmd(Op.CUBIC, doubleArrayOf(cx + 2.0 / 3.0 * (segment.x1 - cx), cy + 2.0 / 3.0 * (segment.y1 - cy),
                        segment.x + 2.0 / 3.0 * (segment.x1 - segment.x), segment.y + 2.0 / 3.0 * (segment.y1 - segment.y), segment.x, segment.y)))
                    cx = segment.x
                    cy = segment.y
                }
                is SvgSubset.ArcTo -> {
                    for(cubic in arcToBeziers(cx, cy, segment.rx, segment.ry, segment.rotation, segment.largeArc, segment.sweep, segment.x, segment.y)) {
                        cmds.add(Cmd(Op.CUBIC, cubic))
                    }
                    cx = segment.x
                    cy = segment.y
                }
                SvgSubset.Close -> {
                    cmds.add(Cmd(Op.CLOSE, DoubleArray(0)))
                    //The next segment starts where the closed subpath did
                    cx = sx
                    cy = sy
                }
            }
        }
        return cmds
    }

    /** Degrees to radians as the desktop's Math.toRadians computes it (one multiplication by the rounded constant). */
    internal fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

    /**
     * An SVG elliptical arc from (x0, y0) to (x, y) as cubic Beziers {x1, y1, x2, y2, x, y}, by the SVG implementation
     * notes (F.6.5, F.6.6): the same end points draw nothing; a zero radius is a straight line (one cubic with its
     * handles on the end points); radii too small to reach the end point are scaled up; the flags pick the centre and
     * the direction. Each cubic spans at most a quarter turn, and the last ends exactly at (x, y).
     */
    internal fun arcToBeziers(x0: Double, y0: Double, rxIn: Double, ryIn: Double, rotation: Double, largeArc: Boolean, sweep: Boolean, x: Double,
                              y: Double): List<DoubleArray> {
        val cubics = ArrayList<DoubleArray>()
        if(x0 == x && y0 == y) {
            return cubics
        }
        var rx = kotlin.math.abs(rxIn)
        var ry = kotlin.math.abs(ryIn)
        if(rx == 0.0 || ry == 0.0) {
            cubics.add(doubleArrayOf(x0, y0, x, y, x, y))
            return cubics
        }
        val phi = toRadians(rotation % 360)
        val cos = kotlin.math.cos(phi)
        val sin = kotlin.math.sin(phi)
        val dx = (x0 - x) / 2
        val dy = (y0 - y) / 2
        val x1p = cos * dx + sin * dy
        val y1p = -sin * dx + cos * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if(lambda > 1) {
            val grow = kotlin.math.sqrt(lambda)
            rx *= grow
            ry *= grow
        }
        val rx2 = rx * rx
        val ry2 = ry * ry
        val numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
        val denominator = rx2 * y1p * y1p + ry2 * x1p * x1p
        var coefficient = if(denominator == 0.0) 0.0 else kotlin.math.sqrt(kotlin.math.max(0.0, numerator / denominator))
        if(largeArc == sweep) {
            coefficient = -coefficient
        }
        val cxp = coefficient * rx * y1p / ry
        val cyp = -coefficient * ry * x1p / rx
        val centreX = cos * cxp - sin * cyp + (x0 + x) / 2
        val centreY = sin * cxp + cos * cyp + (y0 + y) / 2
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val start = kotlin.math.atan2(uy, ux)
        var extent = kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if(!sweep && extent > 0) {
            extent -= 2 * kotlin.math.PI
        } else if(sweep && extent < 0) {
            extent += 2 * kotlin.math.PI
        }
        val count = kotlin.math.max(1.0, kotlin.math.min(4.0, kotlin.math.ceil(kotlin.math.abs(extent) / (kotlin.math.PI / 2) - 1e-9))).toInt()
        val delta = extent / count
        val t = 4.0 / 3.0 * kotlin.math.tan(delta / 4)
        for(i in 0 until count) {
            val a1 = start + i * delta
            val a2 = a1 + delta
            val p1 = ellipsePoint(centreX, centreY, rx, ry, cos, sin, a1)
            val p2 = ellipsePoint(centreX, centreY, rx, ry, cos, sin, a2)
            val d1 = ellipseTangent(rx, ry, cos, sin, a1)
            val d2 = ellipseTangent(rx, ry, cos, sin, a2)
            val last = i == count - 1
            cubics.add(doubleArrayOf(p1[0] + t * d1[0], p1[1] + t * d1[1], p2[0] - t * d2[0], p2[1] - t * d2[1], if(last) x else p2[0],
                if(last) y else p2[1]))
        }
        return cubics
    }

    private fun ellipsePoint(cx: Double, cy: Double, rx: Double, ry: Double, cos: Double, sin: Double, angle: Double): DoubleArray {
        val ex = rx * kotlin.math.cos(angle)
        val ey = ry * kotlin.math.sin(angle)
        return doubleArrayOf(cos * ex - sin * ey + cx, sin * ex + cos * ey + cy)
    }

    private fun ellipseTangent(rx: Double, ry: Double, cos: Double, sin: Double, angle: Double): DoubleArray {
        val dx = -rx * kotlin.math.sin(angle)
        val dy = ry * kotlin.math.cos(angle)
        return doubleArrayOf(cos * dx - sin * dy, sin * dx + cos * dy)
    }
}
