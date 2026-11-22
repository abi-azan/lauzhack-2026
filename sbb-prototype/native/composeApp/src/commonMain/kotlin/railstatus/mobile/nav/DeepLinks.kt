package railstatus.mobile.nav

import railstatus.mobile.screens.easyride.CheckInLinkDestination
import railstatus.mobile.screens.incident.IncidentDestination
import railstatus.mobile.screens.incident.IncidentSheet

/** Links into the app, the same routes as the web app's hash router. */
sealed interface DeepLink {
    /** `railstatus://checkin/<trainId>?car=N` (web `#/checkin/<id>?car=N`): the QR check-in fallback. */
    data class CheckIn(val trainId: String, val car: Int?) : DeepLink

    /** `railstatus://incident/<trainId>` (web `#/incident/<id>`): where push notifications point. */
    data class Incident(val trainId: String) : DeepLink
}

object DeepLinks {
    /**
     * Parses `railstatus://checkin/<id>?car=N`, `railstatus://incident/<id>`, and the same routes as a web URL's
     * hash (`https://host/#/checkin/<id>?car=N`) or a bare route (`/incident/<id>`). Returns null for anything else.
     */
    fun parse(uri: String): DeepLink? {
        val route = when {
            uri.contains("#") -> uri.substringAfter('#')
            uri.startsWith("railstatus://") -> uri.removePrefix("railstatus://")
            else -> uri
        }.trimStart('/')
        val path = route.substringBefore('?')
        val query = route.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val segs = path.split('/').filter { it.isNotEmpty() }.map(::decode)
        return when {
            segs.size >= 2 && segs[0] == "checkin" -> DeepLink.CheckIn(segs[1], query["car"]?.toIntOrNull())
            segs.size >= 2 && segs[0] == "incident" -> DeepLink.Incident(segs[1])
            segs.size >= 3 && segs[0] == "easyride" && segs[1] == "incident" -> DeepLink.Incident(segs[2])
            else -> null
        }
    }

    private fun decode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        var i = 0
        fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length + 0 && i + 2 <= s.lastIndex) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { bytes += b.toByte(); i += 3; continue }
            }
            flush(); out.append(s[i]); i++
        }
        flush()
        return out.toString()
    }
}

/** Opens a deep link: both land on the EasyRide tab (check-in and the incident page live there). */
fun Navigator.open(link: DeepLink) = when (link) {
    is DeepLink.CheckIn -> open(Tab.EasyRide, CheckInLinkDestination(link.trainId, link.car))
    is DeepLink.Incident -> IncidentSheet.open(link.trainId)
}
