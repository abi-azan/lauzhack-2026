package railstatus.mobile.ui.components

/**
 * A tiny SVG reader for the SBB icon set (native/assets/icons, Apache-2.0). Pure Kotlin, so it is unit-testable;
 * [SvgRenderer] turns it into Compose paths. Supports what the icons and our tab glyphs use: svg/g/path/rect/
 * circle/ellipse, fill/stroke (with inheritance), fill-rule, stroke-width/linejoin/linecap, transform (translate,
 * scale, matrix), clip-path and mask (a mask is treated as a clip by its shapes, which is exact for the icons' white masks).
 */
class SvgNode(val name: String, val attrs: Map<String, String>, val children: List<SvgNode>) {
    operator fun get(key: String): String? = attrs[key]
}

/** A colour reference in an icon: `currentColor` (follows the tint), a fixed ARGB colour, or none. */
sealed interface SvgPaint {
    data object None : SvgPaint
    data object Current : SvgPaint
    data class Fixed(val argb: Long) : SvgPaint
}

sealed interface SvgGeometry {
    data class PathData(val d: String) : SvgGeometry
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, val ry: Float) : SvgGeometry
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : SvgGeometry
}

/** 2D affine transform [a c e; b d f] as in SVG's matrix(a b c d e f). */
data class SvgTransform(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    /** this × other (other applied first). */
    operator fun times(o: SvgTransform) = SvgTransform(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )
    val isIdentity get() = this == IDENTITY

    companion object {
        val IDENTITY = SvgTransform()

        fun parse(s: String?): SvgTransform {
            if (s.isNullOrBlank()) return IDENTITY
            var m = IDENTITY
            Regex("""(\w+)\s*\(([^)]*)\)""").findAll(s).forEach { match ->
                val v = match.groupValues[2].split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toFloat() }
                val t = when (match.groupValues[1]) {
                    "translate" -> SvgTransform(e = v[0], f = v.getOrElse(1) { 0f })
                    "scale" -> SvgTransform(a = v[0], d = v.getOrElse(1) { v[0] })
                    "matrix" -> SvgTransform(v[0], v[1], v[2], v[3], v[4], v[5])
                    "rotate" -> {
                        val r = v[0].toDouble() * kotlin.math.PI / 180.0
                        val cos = kotlin.math.cos(r).toFloat(); val sin = kotlin.math.sin(r).toFloat()
                        val rot = SvgTransform(cos, sin, -sin, cos)
                        if (v.size >= 3) SvgTransform(e = v[1], f = v[2]) * rot * SvgTransform(e = -v[1], f = -v[2]) else rot
                    }
                    else -> IDENTITY
                }
                m *= t
            }
            return m
        }
    }
}

/** One drawable shape, fully resolved: its geometry, paints, transform and the clips that apply to it. */
data class SvgShape(
    val geometry: SvgGeometry,
    val fill: SvgPaint,
    val evenOdd: Boolean,
    val stroke: SvgPaint,
    val strokeWidth: Float,
    val roundJoin: Boolean,
    val roundCap: Boolean,
    val transform: SvgTransform,
    /** Each clip is a set of shapes (their union); the shape is drawn inside all of them. */
    val clips: List<List<SvgShape>>,
)

/** A parsed icon: its viewBox and its shapes in paint order. */
class SvgDocument(
    val viewX: Float, val viewY: Float, val viewWidth: Float, val viewHeight: Float,
    /** Coloured icons (status, occupancy, product logos) keep their colours; others draw black as the tint. */
    val coloured: Boolean,
    val shapes: List<SvgShape>,
) {
    val aspectRatio: Float get() = viewWidth / viewHeight

    companion object {
        /** Parses an SVG. Black fills become `currentColor` unless the icon is coloured (same rule as the web's Icon.tsx). */
        fun parse(src: String): SvgDocument {
            val root = parseXml(src)
            require(root.name == "svg") { "not an svg" }
            val vb = root["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.map { it.toFloat() }
                ?: listOf(0f, 0f, root["width"]?.toFloatOrNull() ?: 24f, root["height"]?.toFloatOrNull() ?: 24f)
            val coloured = root["class"]?.contains("color-immutable") == true ||
                Regex("""fill="#(?!000"|000000")[0-9a-fA-F]{3,6}"""").containsMatchIn(src)
            val defs = HashMap<String, SvgNode>()
            fun collect(n: SvgNode) { n["id"]?.let { defs[it] = n }; n.children.forEach(::collect) }
            collect(root)
            val out = ArrayList<SvgShape>()
            Resolver(defs, coloured).walk(root, Inherited(), SvgTransform.IDENTITY, emptyList(), out, inDefs = false)
            return SvgDocument(vb[0], vb[1], vb[2], vb[3], coloured, out)
        }
    }
}

private data class Inherited(
    val fill: String = "#000",
    val fillRule: String = "nonzero",
    val stroke: String = "none",
    val strokeWidth: Float = 1f,
    val lineJoin: String = "miter",
    val lineCap: String = "butt",
)

private class Resolver(val defs: Map<String, SvgNode>, val coloured: Boolean) {
    fun paint(v: String): SvgPaint {
        val s = v.trim()
        if (s == "none" || s.isEmpty()) return SvgPaint.None
        if (s == "currentColor") return SvgPaint.Current
        val hex = s.removePrefix("#")
        if (s.startsWith("#")) {
            val full = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                else -> return SvgPaint.Current
            }
            if (!coloured && (full.equals("000000", true))) return SvgPaint.Current
            return SvgPaint.Fixed(0xFF000000 or full.toLong(16))
        }
        return when (s.lowercase()) {
            "white" -> SvgPaint.Fixed(0xFFFFFFFF)
            "black" -> if (coloured) SvgPaint.Fixed(0xFF000000) else SvgPaint.Current
            else -> SvgPaint.Current
        }
    }

    private fun ref(v: String?): SvgNode? = v?.let { Regex("""url\(#([^)]+)\)""").find(it)?.groupValues?.get(1) }?.let(defs::get)

    private fun clipShapes(def: SvgNode, base: SvgTransform): List<SvgShape> {
        val out = ArrayList<SvgShape>()
        // Clip/mask content: geometry matters, paint does not (white masks = full coverage).
        def.children.forEach { walk(it, Inherited(fill = "#fff"), base, emptyList(), out, inDefs = false, forClip = true) }
        return out
    }

    fun walk(n: SvgNode, parent: Inherited, parentT: SvgTransform, parentClips: List<List<SvgShape>>, out: MutableList<SvgShape>, inDefs: Boolean, forClip: Boolean = false) {
        if (!forClip && (n.name == "defs" || n.name == "clipPath" || n.name == "mask" || n.name == "title" || n.name == "desc")) return
        if (inDefs) return
        val inh = Inherited(
            fill = n["fill"] ?: style(n, "fill") ?: parent.fill,
            fillRule = n["fill-rule"] ?: style(n, "fill-rule") ?: parent.fillRule,
            stroke = n["stroke"] ?: style(n, "stroke") ?: parent.stroke,
            strokeWidth = (n["stroke-width"] ?: style(n, "stroke-width"))?.removeSuffix("px")?.toFloatOrNull() ?: parent.strokeWidth,
            lineJoin = n["stroke-linejoin"] ?: parent.lineJoin,
            lineCap = n["stroke-linecap"] ?: parent.lineCap,
        )
        val t = if (n.name == "svg") parentT else parentT * SvgTransform.parse(n["transform"])
        var clips = parentClips
        ref(n["clip-path"])?.let { clips = clips + listOf(clipShapes(it, t)) }
        ref(n["mask"])?.let { clips = clips + listOf(clipShapes(it, t)) }
        val geometry: SvgGeometry? = when (n.name) {
            "path" -> n["d"]?.let { SvgGeometry.PathData(it) }
            "rect" -> {
                val w = n.f("width"); val h = n.f("height")
                val rx = n["rx"]?.toFloatOrNull(); val ry = n["ry"]?.toFloatOrNull()
                SvgGeometry.Rect(n.f("x"), n.f("y"), w, h, rx ?: ry ?: 0f, ry ?: rx ?: 0f)
            }
            "circle" -> n.f("r").let { SvgGeometry.Ellipse(n.f("cx"), n.f("cy"), it, it) }
            "ellipse" -> SvgGeometry.Ellipse(n.f("cx"), n.f("cy"), n.f("rx"), n.f("ry"))
            else -> null
        }
        if (geometry != null) {
            out += SvgShape(
                geometry = geometry,
                fill = if (forClip) SvgPaint.Current else paint(inh.fill),
                evenOdd = inh.fillRule == "evenodd",
                stroke = if (forClip) SvgPaint.None else paint(inh.stroke),
                strokeWidth = inh.strokeWidth,
                roundJoin = inh.lineJoin == "round",
                roundCap = inh.lineCap == "round",
                transform = t,
                clips = clips,
            )
        }
        n.children.forEach { walk(it, inh, t, clips, out, inDefs = false, forClip = forClip) }
    }

    private fun style(n: SvgNode, key: String): String? =
        n["style"]?.split(';')?.map { it.split(':', limit = 2) }?.firstOrNull { it.size == 2 && it[0].trim() == key }?.get(1)?.trim()

    private fun SvgNode.f(key: String): Float = this[key]?.removeSuffix("px")?.toFloatOrNull() ?: 0f
}

/** Minimal XML parser for icon files: elements and attributes; text, comments and prologs are skipped. */
internal fun parseXml(src: String): SvgNode {
    class Builder(val name: String, val attrs: Map<String, String>) { val children = ArrayList<SvgNode>() }
    val stack = ArrayDeque<Builder>()
    var root: SvgNode? = null
    val attrRe = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    var i = 0
    while (i < src.length) {
        val lt = src.indexOf('<', i)
        if (lt < 0) break
        when {
            src.startsWith("<!--", lt) -> { i = src.indexOf("-->", lt).let { if (it < 0) src.length else it + 3 }; continue }
            src.startsWith("<?", lt) || src.startsWith("<!", lt) -> { i = src.indexOf('>', lt) + 1; continue }
        }
        val gt = src.indexOf('>', lt)
        require(gt > 0) { "unterminated tag" }
        val body = src.substring(lt + 1, gt)
        i = gt + 1
        if (body.startsWith("/")) {
            val b = stack.removeLast()
            val node = SvgNode(b.name, b.attrs, b.children)
            if (stack.isEmpty()) root = node else stack.last().children += node
            continue
        }
        val selfClosing = body.endsWith("/")
        val inner = if (selfClosing) body.dropLast(1) else body
        val name = inner.trim().split(Regex("\\s+"), limit = 2)[0]
        val attrs = attrRe.findAll(inner).associate { m -> m.groupValues[1] to (m.groups[3]?.value ?: m.groups[4]?.value ?: "") }
        if (selfClosing) {
            val node = SvgNode(name, attrs, emptyList())
            if (stack.isEmpty()) root = node else stack.last().children += node
        } else stack.addLast(Builder(name, attrs))
    }
    return root ?: error("empty svg")
}
