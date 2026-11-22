package railstatus.mobile.platform

import android.content.Context
import railstatus.mobile.data.KeyValueStore as AppKeyValueStore
import railstatus.mobile.incident.KeyValueStore as PassengerKeyValueStore

/** SharedPreferences behind both stores' key-value interfaces (the web app's localStorage). */
class PrefsStore(context: Context, name: String) : AppKeyValueStore, PassengerKeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()

    override fun getString(key: String): String? = get(key)
    override fun putString(key: String, value: String) = put(key, value)
}
