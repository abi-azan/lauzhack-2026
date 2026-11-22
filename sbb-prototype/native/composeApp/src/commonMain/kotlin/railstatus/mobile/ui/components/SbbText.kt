package railstatus.mobile.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import railstatus.mobile.ui.theme.LocalContentColor
import railstatus.mobile.ui.theme.LocalTextStyle

/**
 * Text in the SBB font. Defaults: [LocalTextStyle] (16 sp light body inside [railstatus.mobile.ui.theme.SbbTheme])
 * and [LocalContentColor]. Use `SbbTheme.type.*` for the other styles.
 */
@Composable
fun SbbText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    weight: FontWeight? = null,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.resolve(color, weight, align),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        onTextLayout = onTextLayout,
    )
}

@Composable
fun SbbText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    weight: FontWeight? = null,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.resolve(color, weight, align),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

@Composable
private fun TextStyle.resolve(color: Color, weight: FontWeight?, align: TextAlign?): TextStyle {
    val c = if (color.isSpecified) color else if (this.color.isSpecified) this.color else LocalContentColor.current
    return copy(color = c, fontWeight = weight ?: fontWeight, textAlign = align ?: textAlign)
}
