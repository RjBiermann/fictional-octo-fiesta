package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #736: the plugin entrypoint drifted to the legacy app-side
    `plugins.Plugin` base (`load(context)` — the app-side branch of
    PluginManager.loadSinglePlugin, verified against recloudstream/cloudstream
    @ 0c8089d2). The canonical library entrypoint is `plugins.BasePlugin` with a
    parameterless `load()` — the shape every sibling provider uses. Pure JVM
    reflection: nothing is instantiated, no Android API is invoked. */
class FullPornerPluginShapeTest {

    @Test fun `plugin uses the canonical BasePlugin entrypoint with parameterless load`() {
        val klass = FullPornerPlugin::class.java

        // legacy app-side base must be gone — its load(Context) is the app-side
        // branch the framework only tolerates for backwards compatibility
        assertFalse(
            "FullPornerPlugin still extends the legacy app-side Plugin",
            com.lagradost.cloudstream3.plugins.Plugin::class.java.isAssignableFrom(klass)
        )
        assertTrue(
            "FullPornerPlugin must extend BasePlugin",
            com.lagradost.cloudstream3.plugins.BasePlugin::class.java.isAssignableFrom(klass)
        )

        // BasePlugin.load() is parameterless; the legacy overload took a Context
        val load = try {
            klass.getDeclaredMethod("load")
        } catch (e: NoSuchMethodException) {
            throw AssertionError("FullPornerPlugin must declare a parameterless load()", e)
        }
        assertEquals("load() must take no parameters", 0, load.parameterCount)
    }
}
