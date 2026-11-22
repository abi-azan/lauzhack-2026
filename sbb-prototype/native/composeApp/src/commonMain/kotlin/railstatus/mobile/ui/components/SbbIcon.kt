package railstatus.mobile.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import railstatus.mobile.ui.theme.LocalContentColor

/**
 * SBB icon by name, from native/assets/icons (compiled in at build time).
 * Monochrome icons are drawn in [tint] (default: the current content colour); coloured ones keep their colours.
 *
 * @param size square size; ignored when [height] is set
 * @param height height, the width following the icon's aspect ratio (line badges, status icons)
 */
@Composable
fun SbbIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    height: Dp? = null,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val icon = remember(name) { SbbIcons.get(name) } ?: return
    val h = height ?: size
    val w = if (height != null) h * icon.document.aspectRatio else size
    val semantics = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Spacer(modifier.then(semantics).size(w, h).drawBehind { icon.draw(this, tint) })
}

/** True if the icon set has [name]. */
fun hasSbbIcon(name: String): Boolean = name in IconData.svgs

/** The SBB icon set, compiled in from native/assets/icons at build time (IconData); parsed on first use. */
object SbbIcons {
    private val cache = HashMap<String, SvgRenderer>()

    fun get(name: String): SvgRenderer? =
        cache[name] ?: IconData.svgs[name]?.let { SvgRenderer.parse(it) }?.also { cache[name] = it }

    /** All icon names in the set. */
    val names: List<String> get() = IconData.svgs.keys.sorted()
}

/** Draws an [SvgDocument] with Compose. Paths are built once and reused. */
class SvgRenderer(val document: SvgDocument) {
    private class Compiled(val path: Path, val shape: SvgShape, val clips: List<Path>)

    private val compiled: List<Compiled> by lazy {
        document.shapes.map { s -> Compiled(s.toPath(), s, s.clips.map { group -> group.map { it.toPath() }.union() }) }
    }

    /** Draws the icon scaled to fill [size] (default: the draw scope), with its viewBox origin at [topLeft]. */
    fun draw(scope: DrawScope, tint: Color, topLeft: Offset = Offset.Zero, size: Size = scope.size) = with(scope) {
        val sx = size.width / document.viewWidth
        val sy = size.height / document.viewHeight
        withTransform({
            translate(topLeft.x, topLeft.y)
            scale(sx, sy, pivot = Offset.Zero)
            translate(-document.viewX, -document.viewY)
        }) {
            for (c in compiled) drawClipped(c.clips, 0) { drawShape(c, tint) }
        }
    }

    private fun DrawScope.drawClipped(clips: List<Path>, i: Int, block: DrawScope.() -> Unit) {
        if (i == clips.size) block() else clipPath(clips[i]) { drawClipped(clips, i + 1, block) }
    }

    private fun DrawScope.drawShape(c: Compiled, tint: Color) {
        c.shape.fill.color(tint)?.let { drawPath(c.path, it) }
        c.shape.stroke.color(tint)?.let {
            drawPath(
                c.path, it,
                style = Stroke(
                    width = c.shape.strokeWidth,
                    join = if (c.shape.roundJoin) StrokeJoin.Round else StrokeJoin.Miter,
                    cap = if (c.shape.roundCap) StrokeCap.Round else StrokeCap.Butt,
                ),
            )
        }
    }

    private fun SvgPaint.color(tint: Color): Color? = when (this) {
        SvgPaint.None -> null
        SvgPaint.Current -> tint
        is SvgPaint.Fixed -> Color(argb.toInt())
    }

    companion object {
        fun parse(svg: String) = SvgRenderer(SvgDocument.parse(svg))

        private fun List<Path>.union(): Path = if (size == 1) first() else Path().also { p -> forEach { p.addPath(it) } }

        private fun SvgShape.toPath(): Path {
            val p = when (val g = geometry) {
                is SvgGeometry.PathData -> PathParser().parsePathString(g.d).toPath()
                is SvgGeometry.Rect -> Path().apply { addRoundRect(RoundRect(g.x, g.y, g.x + g.w, g.y + g.h, CornerRadius(g.rx, g.ry))) }
                is SvgGeometry.Ellipse -> Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(g.cx - g.rx, g.cy - g.ry, g.cx + g.rx, g.cy + g.ry))
                }
            }
            p.fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            if (!transform.isIdentity) p.transform(transform.toMatrix())
            return p
        }

        private fun SvgTransform.toMatrix(): Matrix = Matrix().apply {
            values[Matrix.ScaleX] = a; values[Matrix.SkewY] = b
            values[Matrix.SkewX] = c; values[Matrix.ScaleY] = d
            values[Matrix.TranslateX] = e; values[Matrix.TranslateY] = f
        }
    }
}

/** Raw SVG source of an icon (tests, debugging). */
object IconDataAccess {
    fun svg(name: String): String? = IconData.svgs[name]
}
