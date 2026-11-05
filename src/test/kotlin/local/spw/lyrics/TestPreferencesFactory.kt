package local.spw.lyrics

import java.util.prefs.AbstractPreferences
import java.util.prefs.PreferencesFactory

/** Keep host preference tests away from the user's SPW settings. */
class TestPreferencesFactory : PreferencesFactory {
    private val root = TestPreferences(null, "")
    override fun userRoot(): java.util.prefs.Preferences = root
    override fun systemRoot(): java.util.prefs.Preferences = root
}
private class TestPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val nodes = mutableMapOf<String, TestPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear(); nodes.clear() }
    override fun keysSpi() = values.keys.toTypedArray()
    override fun childrenNamesSpi() = nodes.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = nodes.getOrPut(name) { TestPreferences(this, name) }
    override fun syncSpi() {}
    override fun flushSpi() {}
}
