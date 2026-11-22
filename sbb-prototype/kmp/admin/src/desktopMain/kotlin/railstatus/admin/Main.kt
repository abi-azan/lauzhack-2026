package railstatus.admin

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.MainScope

/** Desktop build of the same dashboard. Backend from API_URL (default http://localhost:8080). */
fun main() {
    val store = AdminStore(AdminApi(System.getenv("API_URL") ?: "http://localhost:8080", System.getenv("ADMIN_KEY")), MainScope())
    store.start()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Rail Status Control",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) { App(store) }
    }
}
