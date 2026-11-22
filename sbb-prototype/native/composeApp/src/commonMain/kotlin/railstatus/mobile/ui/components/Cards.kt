package railstatus.mobile.ui.components

import railstatus.mobile.data.DemoTraveller
import railstatus.mobile.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

/** Card shape: 16 dp corners. */
val CardShape = RoundedCornerShape(SbbDimens.Radius)

/**
 * A card (measured): card colour, 16 dp radius, inset 8 dp from the screen edges with 8 dp below it (the web's
 * `margin: 0 8px 8px`). Pass [onClick] for a tappable card (pressed overlay, no ripple).
 */
@Composable
fun SbbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CardShape,
    color: Color = SbbTheme.colors.card,
    gutter: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .then(if (gutter) Modifier.padding(start = SbbDimens.Gutter, end = SbbDimens.Gutter, bottom = 8.dp) else Modifier)
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.sbbClickable(shape, onClick = onClick) else Modifier)
            .then(modifier),
        content = content,
    )
}

/**
 * Grouped rows (Profile, settings), measured: one rounded block in the card colour; put [ListRow]s in it (the first
 * with `divider = false`). [squareTop]: the block sits right under the header (the web's first `.list-card`).
 * [inset]: 16 dp side margins and no gap below, as inside a sheet.
 */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    squareTop: Boolean = false,
    inset: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val r = SbbDimens.Radius
    val shape = if (squareTop) RoundedCornerShape(0.dp, 0.dp, r, r) else RoundedCornerShape(r)
    val side = if (inset) 16.dp else SbbDimens.Gutter
    Column(
        modifier
            .padding(start = side, end = side, bottom = if (inset) 0.dp else 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(SbbTheme.colors.card),
        content = content,
    )
}

/**
 * A row in a [ListCard] (measured): 48.4 dp plus, unless it is the first row, a 1.1 dp hairline on top across the
 * block (49.5 dp in all); 16 dp side padding, a 24 dp icon, 16 sp light text 8 dp after it (x 56 on screen), set
 * 1.4 dp lower than centred like Android's SBB text.
 */
@Composable
fun ListRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    divider: Boolean = true,
    height: Dp = if (divider) SbbDimens.ListRow else SbbDimens.ListRowFirst,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val line = SbbTheme.colors.line
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (onClick != null) Modifier.sbbClickable(onClick = onClick) else Modifier)
            .drawBehind { if (divider) drawRect(line, Offset.Zero, Size(size.width, hairlinePx())) }
            .padding(top = if (divider) SbbDimens.Hairline else 0.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) leading() else if (icon != null) SbbIcon(icon, size = 24.dp)
        SbbText(text, Modifier.weight(1f).offset(y = 1.4.dp), style = SbbTheme.type.body, maxLines = 1)
        trailing?.invoke(this)
    }
}

/**
 * Section title on the page (measured): 14 sp light at x 24, 8 dp above the next block. [strong]: the bold 16 sp
 * variant in a 30.1 dp row right on top of its card ("Products"). [top] assumes the block above ends with the
 * usual 8 dp card gap (CSS margins collapse to 16 dp there).
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, strong: Boolean = false, top: Dp = if (strong) 0.dp else 8.dp) {
    if (strong) {
        Box(modifier.padding(top = top).fillMaxWidth().height(30.1.dp).padding(start = 24.dp, end = 24.dp, bottom = 2.dp), contentAlignment = Alignment.CenterStart) {
            SbbText(text, style = SbbTheme.type.sectionStrong)
        }
    } else {
        Box(modifier.padding(top = top, bottom = 8.dp).fillMaxWidth().height(19.4.dp).padding(start = 24.dp, end = 24.dp, top = 1.4.dp), contentAlignment = Alignment.CenterStart) {
            SbbText(text, style = SbbTheme.type.section)
        }
    }
}

/**
 * Count badge (measured): a 20.2 dp disc, #B7B7B7 + black digit (dark) or #686868 + white digit (light), 12 sp bold,
 * the digit 0.4 dp above the centre.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = SbbTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 20.2.dp)
            .height(20.2.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.countBg)
            .padding(bottom = 0.8.dp),
        contentAlignment = Alignment.Center,
    ) { SbbText(count.toString(), style = SbbTheme.type.badge, color = c.countFg) }
}

/**
 * Empty-state box on an illustration (measured): black 50 % (dark) / white 50 % (light), 16 dp padding, a 16 sp bold
 * title, 16 dp gap, 16 sp body on 20.3 dp lines, centred.
 */
@Composable
fun NoteCard(title: String, body: String, modifier: Modifier = Modifier) {
    SbbCard(modifier, color = SbbTheme.colors.noteCard) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SbbText(title, style = SbbTheme.type.bodyBold.copy(lineHeight = 22.1.sp), align = TextAlign.Center)
            SbbText(
                body,
                Modifier.padding(top = 16.dp),
                style = SbbTheme.type.body.copy(lineHeight = 20.3.sp),
                align = TextAlign.Center,
            )
        }
    }
}

/** A 1.1 dp hairline in the line colour, full width, snapped to whole pixels like the browser's borders. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = SbbTheme.colors.line) {
    Box(modifier.fillMaxWidth().height(SbbDimens.Hairline).drawBehind { drawRect(color, Offset.Zero, Size(size.width, hairlinePx())) })
}

/** 1.1 dp rounded to whole pixels (3 px on the reference phone), so it never blurs over two rows. */
internal fun androidx.compose.ui.unit.Density.hairlinePx(): Float = kotlin.math.max(1f, kotlin.math.round(SbbDimens.Hairline.toPx()))

/**
 * The logged-in traveller's card at the top of Profile, Shop and Notifications ([DemoTraveller]): the person icon,
 * the name in 16 sp bold over the e-mail in a grey 14 sp line, [height] dp (Profile 76, Shop 63), 16 dp padding.
 * Profile passes the 32 dp circled [avatar] of SBB Mobile 12.60 (measured on a logged-in phone, 22 Nov); its Wallet
 * card sits under this one (see `ProfileScreen`).
 */
@Composable
fun AccountCard(height: Dp = 70.9.dp, avatar: String = "user-small", avatarSize: Dp = 24.dp) {
    SbbCard {
        Row(
            Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (avatarSize > 24.dp) 10.dp else 8.dp),
        ) {
            SbbIcon(avatar, size = avatarSize)
            Column(Modifier.weight(1f)) {
                SbbText(DemoTraveller.NAME, style = SbbTheme.type.bodyBold, maxLines = 1)
                SbbText(
                    DemoTraveller.EMAIL,
                    Modifier.padding(top = 2.dp),
                    style = SbbTheme.type.small.copy(lineHeight = SbbTheme.type.pill.lineHeight),
                    color = SbbTheme.colors.text2,
                    maxLines = 1,
                )
            }
        }
    }
}
