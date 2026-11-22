package railstatus.mobile.screens.shop

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import railstatus.mobile.nav.Destination
import railstatus.mobile.resources.Res
import railstatus.mobile.resources.seg_quick_access
import railstatus.mobile.resources.seg_services
import railstatus.mobile.resources.shop_cd_open_link
import railstatus.mobile.resources.shop_day_passes
import railstatus.mobile.resources.shop_p_bike
import railstatus.mobile.resources.shop_p_children
import railstatus.mobile.resources.shop_p_class_upgrade
import railstatus.mobile.resources.shop_p_friends_youth
import railstatus.mobile.resources.shop_p_half_fare
import railstatus.mobile.resources.shop_p_saver
import railstatus.mobile.resources.shop_products
import railstatus.mobile.resources.shop_s_catering
import railstatus.mobile.resources.shop_s_catering_text
import railstatus.mobile.resources.shop_s_lost_found
import railstatus.mobile.resources.shop_s_lost_found_text
import railstatus.mobile.resources.shop_s_luggage
import railstatus.mobile.resources.shop_s_luggage_text
import railstatus.mobile.resources.shop_s_stations
import railstatus.mobile.resources.shop_s_stations_text
import railstatus.mobile.resources.shop_s_traffic
import railstatus.mobile.resources.shop_s_traffic_text
import railstatus.mobile.resources.shop_services_title
import railstatus.mobile.resources.tab_shop
import railstatus.mobile.ui.components.AccountCard
import railstatus.mobile.ui.chrome.SbbScreen
import railstatus.mobile.ui.chrome.ScreenColumn
import railstatus.mobile.ui.chrome.Segmented
import railstatus.mobile.ui.chrome.SegmentedPage
import railstatus.mobile.ui.components.CountBadge
import railstatus.mobile.ui.components.RoundButton
import railstatus.mobile.ui.components.RoundButtonStyle
import railstatus.mobile.ui.components.SbbCard
import railstatus.mobile.ui.components.SbbIcon
import railstatus.mobile.ui.components.SbbText
import railstatus.mobile.ui.components.SectionHeader
import railstatus.mobile.ui.components.sbbClickable
import railstatus.mobile.ui.theme.SbbTheme

/** Shop & Services tab root: Quick access | Services. Web: `#/shop`. */
data object ShopDestination : Destination {
    @Composable
    override fun Content() = ShopScreen()
}

private val PRODUCTS: List<Pair<String, StringResource>> = listOf(
    "user-group-small" to Res.string.shop_p_friends_youth,
    "ticket-percent-small" to Res.string.shop_p_saver,
    "half-fare-card-small" to Res.string.shop_p_half_fare,
    "document-text-small" to Res.string.shop_p_class_upgrade,
    "user-small" to Res.string.shop_p_children,
    "bicycle-small" to Res.string.shop_p_bike,
)

/** SBB lists 8 day passes (the badge); the card shows the first 6. */
private const val DAY_PASS_COUNT = 8

private class Service(val icon: String, val title: StringResource, val text: StringResource)

private val SERVICES = listOf(
    Service("train-station-small", Res.string.shop_s_stations, Res.string.shop_s_stations_text),
    Service("train-small", Res.string.shop_s_traffic, Res.string.shop_s_traffic_text),
    Service("magnifying-glass-small", Res.string.shop_s_lost_found, Res.string.shop_s_lost_found_text),
    Service("hand-with-service-bell-small", Res.string.shop_s_catering, Res.string.shop_s_catering_text),
    Service("suitcase-small", Res.string.shop_s_luggage, Res.string.shop_s_luggage_text),
)

@Composable
fun ShopScreen() {
    var page by rememberSaveable { mutableIntStateOf(0) }
    SbbScreen(
        title = stringResource(Res.string.tab_shop),
        headerContent = { Segmented(listOf(stringResource(Res.string.seg_quick_access), stringResource(Res.string.seg_services)), page, { page = it }) },
    ) {
        SegmentedPage(page) { if (page == 0) QuickAccess() else Services() }
    }
}

@Composable
private fun QuickAccess() {
    ScreenColumn {
        AccountCard(height = 63.dp)
        // Measured on the phone: the bold title row, then 8 dp to the card.
        SectionHeader(stringResource(Res.string.shop_products), strong = true)
        Spacer(Modifier.height(8.dp))
        SbbCard {
            CardHead(stringResource(Res.string.shop_day_passes)) { CountBadge(DAY_PASS_COUNT) }
            Column(Modifier.padding(bottom = 8.dp)) {
                PRODUCTS.forEach { (icon, label) -> ProductRow(icon, stringResource(label)) }
            }
        }
    }
}

/**
 * A card's bold heading (web `.card-head`), measured on the phone: 16 sp bold in a 22.1 dp row, 16 dp in from the
 * card's edges, the first row 17.9 dp below it; [trailing] sits at the right edge (a count badge).
 */
@Composable
fun CardHead(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 17.9.dp).height(22.1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SbbText(text, style = SbbTheme.type.bodyBold.copy(lineHeight = 22.1.sp), maxLines = 1)
        trailing()
    }
}

private val ProductShape = RoundedCornerShape(12.dp)

/**
 * A product (web `.product-row`): 48.4 dp, inset 16 dp with 8 dp between rows, 12 dp corners; #444444 with a #5A5A5A
 * edge (dark) or #F6F6F6 with a #D2D2D2 edge (light); 24 dp icon, 16 sp light label, a chevron.
 */
@Composable
private fun ProductRow(icon: String, label: String) {
    val c = SbbTheme.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 48.4.dp)
            .clip(ProductShape)
            .background(c.productRow)
            .border(1.1.dp, c.productRowEdge, ProductShape)
            .sbbClickable(ProductShape) {}
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SbbIcon(icon, size = 24.dp)
        SbbText(label, Modifier.weight(1f).offset(y = 1.4.dp), style = SbbTheme.type.body)
        SbbIcon("chevron-small-right-small", size = 24.dp)
    }
}

@Composable
private fun Services() {
    // On the phone this page has no red band: the title sits 8 dp under the header, 8 dp above the first card.
    ScreenColumn(overlap = false) {
        SectionHeader(stringResource(Res.string.shop_services_title), strong = true, top = 7.dp)
        Spacer(Modifier.height(8.dp))
        SERVICES.forEach { ServiceCard(it) }
    }
}

/**
 * A service (web `.service-card`): 18 sp light title, grey 14 sp text after a 32 dp icon slot, 14 sp text on 19.6 dp lines, and a
 * 32 dp outlined link button 12 dp after the text, 16 dp from the right.
 */
@Composable
private fun ServiceCard(s: Service) {
    SbbCard(onClick = {}) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.4.dp, bottom = 11.7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(32.dp)) { SbbIcon(s.icon, size = 24.dp) }
                    SbbText(stringResource(s.title), style = SbbTheme.type.title.copy(lineHeight = 22.1.sp), maxLines = 1)
                }
                SbbText(stringResource(s.text), Modifier.padding(top = 3.4.dp), style = SbbTheme.type.small, color = SbbTheme.colors.text2)
            }
            RoundButton("link-external-small", stringResource(Res.string.shop_cd_open_link), onClick = {}, style = RoundButtonStyle.Outline)
        }
    }
}
