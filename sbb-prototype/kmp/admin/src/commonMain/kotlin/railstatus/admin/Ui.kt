package railstatus.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Building blocks in SBB Mobile's measured look (native/.../ui/components and ui/chrome), sized for a desktop.

val Pill = RoundedCornerShape(percent = 50)
/** Card corner radius: 16 dp, as in the app. */
val CardShape = RoundedCornerShape(16.dp)
val FieldShape = RoundedCornerShape(8.dp)

/** SBB's pressed state (an overlay, no ripple), plus a hover tint and the hand cursor on desktop. */
fun Modifier.press(shape: Shape? = null, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val overlay = when {
        !enabled -> Color.Transparent
        pressed -> C.pressed
        hovered -> C.hover
        else -> Color.Transparent
    }
    this
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .hoverable(source, enabled)
        .clickable(source, indication = null, enabled = enabled, onClick = onClick)
        .then(if (shape != null) Modifier.background(overlay, shape) else Modifier.background(overlay))
}

/** A white card on the milk page, 16 dp corners. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = C.card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(color), content = content)
}

/** A 1.1 dp hairline in the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = C.line) {
    Box(modifier.fillMaxWidth().height(1.1.dp).background(color))
}

/** Section label inside a card, as on the passenger's incident page: 12 bold caps in grey. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = C.meta) {
    Text(text.uppercase(), modifier, style = T.captionBold.copy(letterSpacing = T.captionBold.fontSize * 0.04f), color = color, maxLines = 1)
}

/** Primary button (measured in the app): 44.2 dp red pill, 16 light white label. Disabled: #555 with grey ink. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.2.dp)
            .defaultMinSize(minWidth = 120.dp)
            .clip(Pill)
            .background(if (enabled) C.red else C.disabled)
            .press(Pill, enabled, onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.body, color = if (enabled) C.white else C.disabledInk, maxLines = 1) }
}

/**
 * Secondary (outlined) pill (measured): 32 dp, 1.1 dp outline, 14 light, 19 dp side padding. [tall]: 44.2 dp to sit
 * next to a primary button, with the 16 sp label.
 */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    border: Color = C.outline,
    ink: Color = C.ink,
    icon: Icon? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(if (tall) 44.2.dp else 32.dp)
            .clip(Pill)
            .border(1.1.dp, border, Pill)
            .press(Pill, onClick = onClick)
            .padding(horizontal = if (tall) 24.dp else 19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) SbbIcon(icon, 20.dp, tint = ink)
        Text(text, style = if (tall) T.body else T.pill, color = ink, maxLines = 1)
    }
}

/** Round icon button with the #8D8D8D ring (the app's sheet close / action buttons). */
@Composable
fun RoundButton(icon: Icon, size: Dp = 32.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(C.card).border(1.1.dp, C.ring, CircleShape).press(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SbbIcon(icon, size * 0.62f) }
}

/** A choice chip: 32 dp pill with the #8D8D8D ring; selected is black with white ink (the sheet segmented thumb). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(Pill)
            .background(if (selected) C.ink else C.card)
            .border(1.1.dp, if (selected) C.ink else C.ring, Pill)
            .press(Pill, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (selected) T.pill.copy(fontWeight = FontWeight.Bold) else T.pill, color = if (selected) C.white else C.ink, maxLines = 1)
    }
}

/** Looks of [Segmented], as in the app. */
enum class SegStyle {
    /** In the red header: red125 track, red thumb with a red150 ring, white labels. */
    Header,
    /** On a card or sheet: #D9D9D9 track, black thumb, white label when selected. */
    Sheet,
}

/**
 * The app's segmented control: a pill track, the selected tab a full-height pill with a 1.15 dp ring, the label bold
 * when selected and light otherwise. [counts]: an optional number after a label.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    style: SegStyle = SegStyle.Sheet,
    height: Dp = 36.dp,
    counts: List<Int?> = emptyList(),
    onSelect: (Int) -> Unit,
) {
    val track = if (style == SegStyle.Header) C.red125 else C.track
    val thumb = if (style == SegStyle.Header) C.red else C.ink
    val ring = if (style == SegStyle.Header) C.red150 else C.ink
    val off = if (style == SegStyle.Header) C.white else C.ink
    val label = if (height >= 40.dp) T.segment else T.pill
    Box(modifier.height(height)) {
        Box(Modifier.matchParentSize().padding(vertical = 0.75.dp).background(track, Pill))
        Row(Modifier.fillMaxHeight()) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Row(
                    Modifier
                        .fillMaxHeight()
                        .then(if (on) Modifier.background(thumb, Pill).border(1.15.dp, ring, Pill) else Modifier)
                        .clip(Pill)
                        .press(Pill) { if (!on) onSelect(i) }
                        .padding(horizontal = if (height >= 40.dp) 22.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(o, style = label.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Light), color = if (on) (if (style == SegStyle.Header) C.white else C.page) else off, maxLines = 1)  // on the ink thumb: the page colour, so it reads in dark mode too
                    counts.getOrNull(i)?.takeIf { it > 0 }?.let { n ->
                        val (bg, fg) = if (style == SegStyle.Header) C.white to C.red else C.countBg to C.white
                        CountBadge(n, bg, fg)
                    }
                }
            }
        }
    }
}

/** The app's count badge: a 20.2 dp disc, 12 bold digit (#686868 with white in light mode). */
@Composable
fun CountBadge(n: Int, bg: Color = C.countBg, fg: Color = C.white) {
    Box(
        Modifier.defaultMinSize(minWidth = 20.2.dp).height(20.2.dp).clip(Pill).background(bg).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text("$n", style = T.captionBold.copy(lineHeight = T.captionBold.fontSize), color = fg) }
}

/** A small solid label, e.g. "Not published · 0:42" on red or "Urgent". */
@Composable
fun Tag(text: String, bg: Color = C.red, fg: Color = C.white) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = T.smallBold, color = fg, maxLines = 1)
    }
}

/**
 * The red banner, as under the passenger's journey card (r05): #EB0000, an SBB pictogram with a white ring, bold
 * white text, a white chevron. Clickable when [onClick] is given.
 */
@Composable
fun RedBanner(icon: Icon, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(C.red)
            .then(if (onClick != null) Modifier.press(CardShape, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SbbIcon(icon, 20.dp, ring = true)
        content()
        if (onClick != null) SbbIcon(Icon.Chevron, 24.dp, tint = C.white)
    }
}

/** A labelled text field in SBB's form look: 14 light label above a white box with an aluminium outline (black when focused). */
@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, lines: Int = 1, onChange: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth()) {
        Text(label, style = T.small, color = C.meta)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            minLines = lines,
            textStyle = T.body,
            cursorBrush = SolidColor(C.ink),
            interactionSource = source,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(FieldShape)
                        .background(C.card)
                        .border(if (focused) 1.5.dp else 1.1.dp, if (focused) C.ink else C.edge, FieldShape)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) { inner() }
            },
        )
    }
}

/** A small numeric field (delay minutes). */
@Composable
fun NumberField(value: String, onChange: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        singleLine = true,
        textStyle = T.body,
        cursorBrush = SolidColor(C.ink),
        interactionSource = source,
        modifier = Modifier.width(56.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(36.dp).clip(FieldShape).background(C.card)
                    .border(if (focused) 1.5.dp else 1.1.dp, if (focused) C.ink else C.edge, FieldShape).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** A dropdown in the field look: label above, the value, a chevron; the options in a white menu. */
@Composable
fun Picker(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, placeholder: String = "Choose", onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Column(modifier) {
        Text(label, style = T.small, color = C.meta)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.onSizeChanged { width = with(density) { it.width.toDp() } }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(FieldShape)
                    .background(C.card)
                    .border(if (open) 1.5.dp else 1.1.dp, if (open) C.ink else C.edge, FieldShape)
                    .press(FieldShape) { open = true }
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.ifEmpty { placeholder }, Modifier.weight(1f), style = T.body, color = if (value.isEmpty()) C.text2 else C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SbbIcon(Icon.Chevron, 24.dp, modifier = Modifier.rotate(90f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = C.card, modifier = Modifier.width(width).heightIn(max = 420.dp)) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o, style = T.body) }, onClick = { onPick(i); open = false })
                }
            }
        }
    }
}

/**
 * A modal sheet in the app's look: the #444 80 % scrim, a milk sheet with 16 dp corners, a 64 dp head with the title
 * (18 light, centred) and the round close button, the [content], and a [footer] on white under a hairline.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sheet(
    title: String,
    width: Dp,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = C.scrim)) {
        Column(Modifier.width(width).heightIn(max = 860.dp).clip(CardShape).background(C.page)) {
            Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(title, style = T.title)
                Box(Modifier.align(Alignment.CenterEnd)) { RoundButton(Icon.Cross, onClick = onDismiss) }
            }
            Column(Modifier.weight(1f, fill = false).padding(horizontal = 16.dp), content = content)
            Spacer(Modifier.height(16.dp))
            Hairline()
            Row(
                Modifier.fillMaxWidth().background(C.card).padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = footer,
            )
        }
    }
}

/** A table's column head: 14 light grey, as on SBB's departure board. */
@Composable
fun ColumnHead(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = T.small, color = C.meta, maxLines = 1)
}

/** Label over value, the app's arrival-card idiom ("Arrival / 21:56"), sized for a facts row. */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = C.ink, valueStyle: TextStyle = T.bodyBold) {
    Column(modifier) {
        Text(label, style = T.small, color = C.meta, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = valueStyle, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
