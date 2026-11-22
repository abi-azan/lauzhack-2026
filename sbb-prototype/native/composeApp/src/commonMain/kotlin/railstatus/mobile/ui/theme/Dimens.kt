package railstatus.mobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Sizes from the web app's app.css, where 1 CSS px = 1 dp (measured on a Pixel 10a: 1080 px wide, density 2.625).
 */
object SbbDimens {
    /** Toolbar row under the status bar. */
    val Toolbar = 56.dp
    /** The red runs on 24 dp under the first card (the card overlaps it). */
    val Band = 24.dp
    /** Tab bar view height without the gesture-nav inset: 12 dp see-through overhang + 79.6 dp bar. */
    val TabBar = 91.6.dp
    /** The see-through overhang above the tab bar's flat edge. */
    val TabBarOverhang = 12.dp
    /**
     * How far screens run on under the tab bar view, so they show through its notch. A screen's viewport ends this
     * far below the tab bar's top; pad scrolling content by [ContentBottom] to clear the bar.
     */
    val Under = 70.dp
    /** Bottom padding of a scrolling screen's content: 24 dp + [Under]. */
    val ContentBottom = 94.dp
    /** Card corner radius. */
    val Radius = 16.dp
    /** Card inset from the screen edge. */
    val Gutter = 8.dp
    /** Content inset inside a card (so 24 dp from the screen). */
    val CardPadding = 16.dp
    /** Hairline width (3 px on the reference phone). */
    val Hairline = 1.1.dp
    val SegmentedHeight = 44.2.dp
    val PrimaryButtonHeight = 44.2.dp
    val PillButtonHeight = 32.dp
    val RoundButton = 46.dp
    val RoundButtonSmall = 40.dp
    /** Round action buttons on cards / maps (with the #8D8D8D ring). */
    val RoundButtonAction = 43.8.dp
    val ListRow = 49.5.dp
    val ListRowFirst = 48.4.dp
    val SheetHeader = 64.dp
    /** A modal sheet stops this far below the top of the screen. */
    val SheetTopGap = 32.7.dp
}
