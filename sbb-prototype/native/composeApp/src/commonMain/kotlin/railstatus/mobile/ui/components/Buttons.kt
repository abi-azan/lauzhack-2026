package railstatus.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import railstatus.mobile.ui.theme.SbbDimens
import railstatus.mobile.ui.theme.SbbTheme

private val Pill = RoundedCornerShape(percent = 50)

/**
 * Primary button (measured): 44.2 dp full pill, red in both themes, 16 sp light white label. Full width of its
 * parent: put it in a row with 8 dp side padding (or 16 dp in sheets), or in a [CtaDock].
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val bg = if (enabled) SbbTheme.colors.red else Color(0xFF555555)
    val fg = if (enabled) Color.White else Color(0xFFAAAAAA)
    Box(
        modifier
            .fillMaxWidth()
            .height(SbbDimens.PrimaryButtonHeight)
            .clip(Pill)
            .background(bg)
            .sbbClickable(Pill, overlay = Color(0x1F000000), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SbbText(text, style = SbbTheme.type.button, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A floating primary button area at the bottom of a screen (measured: 8 dp inset, its bottom 2.3 dp above the tab
 * bar's view). Place it as the last child of the screen's Box, aligned to the bottom.
 */
@Composable
fun BoxScope.CtaDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = SbbDimens.Under)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.3.dp),
    ) { content() }
}

/**
 * Secondary (outlined) button (measured): 32 dp full pill, 1.1 dp outline (white in dark, black in light),
 * 14 sp light label, 19 dp side padding, optional 20 dp leading icon 8 dp before the label.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    height: Dp = SbbDimens.PillButtonHeight,
    horizontalPadding: Dp = 19.dp,
    border: Color = SbbTheme.colors.outline,
    background: Color = Color.Transparent,
    textStyle: androidx.compose.ui.text.TextStyle = SbbTheme.type.pill,
) {
    Row(
        modifier
            .height(height)
            .clip(Pill)
            .background(background)
            .border(BorderStroke(1.1.dp, border), Pill)
            .sbbClickable(Pill, onClick = onClick)
            .padding(PaddingValues(horizontal = horizontalPadding)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SbbIcon(icon, size = 20.dp)
        SbbText(text, style = textStyle, maxLines = 1)
    }
}

/** Looks of [RoundButton]: the measured variants in SBB Mobile. */
enum class RoundButtonStyle {
    /** 46 dp, 1.5 dp ring in the button-border colour, page fill. */
    Plain,
    /** 40 dp version of [Plain]. */
    Small,
    /** 43.8 dp, 1.1 dp #8D8D8D ring, card fill (connection header, map buttons). */
    Action,
    /** 32 dp, 1.1 dp #8D8D8D ring, card fill, 20 dp icon (sheet close button). */
    SheetClose,
    /** 32 dp, 1.1 dp outline ring, transparent (service-card link button). */
    Outline,
}

/** Round icon button. [contentDescription] is read by TalkBack. */
@Composable
fun RoundButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RoundButtonStyle = RoundButtonStyle.Plain,
    iconSize: Dp? = null,
) {
    val c = SbbTheme.colors
    val (size, ring, fill) = when (style) {
        RoundButtonStyle.Plain -> Triple(SbbDimens.RoundButton, BorderStroke(1.5.dp, c.btnBorder), c.bg)
        RoundButtonStyle.Small -> Triple(SbbDimens.RoundButtonSmall, BorderStroke(1.5.dp, c.btnBorder), c.bg)
        RoundButtonStyle.Action -> Triple(SbbDimens.RoundButtonAction, BorderStroke(1.1.dp, c.ring), c.card)
        RoundButtonStyle.SheetClose -> Triple(32.dp, BorderStroke(1.1.dp, c.ring), c.card)
        RoundButtonStyle.Outline -> Triple(32.dp, BorderStroke(1.1.dp, c.outline), Color.Transparent)
    }
    val glyph = iconSize ?: if (style == RoundButtonStyle.SheetClose || style == RoundButtonStyle.Outline) 20.dp else 24.dp
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(ring, CircleShape)
            .sbbClickable(CircleShape, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { SbbIcon(icon, size = glyph) }
}
