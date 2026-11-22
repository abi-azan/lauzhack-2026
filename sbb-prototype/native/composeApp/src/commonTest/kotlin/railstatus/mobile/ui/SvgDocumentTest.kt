package railstatus.mobile.ui

import railstatus.mobile.ui.components.SbbIcons
import railstatus.mobile.ui.components.SvgDocument
import railstatus.mobile.ui.components.SvgGeometry
import railstatus.mobile.ui.components.SvgPaint
import railstatus.mobile.ui.components.SvgTransform
import railstatus.mobile.ui.components.hasSbbIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgDocumentTest {
    @Test fun monochromeIconFollowsTint() {
        val d = SvgDocument.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" fill="none" viewBox="0 0 24 24"><path fill="#000" fill-rule="evenodd" d="M0 0h1v1z" clip-rule="evenodd"/></svg>""")
        assertEquals(24f, d.viewWidth)
        assertEquals(1, d.shapes.size)
        assertEquals(SvgPaint.Current, d.shapes[0].fill)
        assertTrue(d.shapes[0].evenOdd)
    }

    @Test fun colouredIconKeepsColours() {
        val d = SvgDocument.parse("""<svg class="color-immutable" viewBox="0 0 16 16" fill="none"><rect width="16" height="16" fill="#EB0000" rx="2"/><path fill="#fff" d="M1 1h2v2z"/></svg>""")
        assertTrue(d.coloured)
        assertEquals(SvgPaint.Fixed(0xFFEB0000), d.shapes[0].fill)
        assertEquals(SvgGeometry.Rect(0f, 0f, 16f, 16f, 2f, 2f), d.shapes[0].geometry)
        assertEquals(SvgPaint.Fixed(0xFFFFFFFF), d.shapes[1].fill)
    }

    @Test fun masksAndClipPathsBecomeClips() {
        // The S-Bahn badge: a stroke masked by the badge outline, all inside a clip-path defined after use.
        val d = SvgDocument.parse(
            """<svg class="color-immutable" viewBox="0 0 59 20" fill="none"><g clip-path="url(#a)"><mask id="b" fill="#fff"><path d="M2 0h55v20H2z"/></mask>""" +
                """<path stroke="#000" stroke-width="2" d="M0 0h59v20H0z" mask="url(#b)"/></g><defs><clipPath id="a"><path fill="#fff" d="M0 0h59v20H0z"/></clipPath></defs></svg>""",
        )
        assertEquals(1, d.shapes.size)
        val s = d.shapes[0]
        assertEquals(SvgPaint.None, s.fill)
        assertEquals(SvgPaint.Fixed(0xFF000000), s.stroke)
        assertEquals(2f, s.strokeWidth)
        assertEquals(2, s.clips.size)
    }

    @Test fun transformsCompose() {
        val t = SvgTransform.parse("translate(12 15.9) scale(.8) translate(-12 -12)")
        assertEquals(0.8f, t.a); assertEquals(0.8f, t.d)
        assertTrue(kotlin.math.abs(t.e - (12 - 9.6f)) < 1e-4)
        assertTrue(kotlin.math.abs(t.f - (15.9f - 9.6f)) < 1e-4)
    }

    @Test fun everyWebIconParses() {
        assertTrue(SbbIcons.names.size >= 90, "icons compiled in from native/assets/icons")
        for (name in SbbIcons.names) {
            val d = SvgDocument.parse(railstatus.mobile.ui.components.IconDataAccess.svg(name)!!)
            assertTrue(d.shapes.isNotEmpty(), "$name has shapes")
        }
        assertTrue(hasSbbIcon("timetable-small"))
    }
}
