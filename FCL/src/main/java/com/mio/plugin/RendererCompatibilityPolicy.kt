package com.mio.plugin

import com.mio.data.Renderer
import com.tungsten.fclcore.util.versioning.GameVersionNumber

object RendererCompatibilityPolicy {
    private val fpeCapableBuiltinIds = setOf(
        Renderer.ID_NGGL4ES, Renderer.ID_GL4ES, Renderer.ID_VGPU, Renderer.ID_ZINK
    )

    @JvmStatic
    fun useSfpew(
        renderer: Renderer,
        minecraftVersion: String?,
        fpeEmulatorPresent: Boolean,
        pluginLayerEnabled: Boolean
    ): Boolean {
        if (renderer.vrOnly || fpeEmulatorPresent) return false
        if (renderer.path.isNotEmpty()) return pluginLayerEnabled
        if (renderer.id in fpeCapableBuiltinIds) return false
        if (renderer.id == Renderer.ID_MOBILEGLUES && !minecraftVersion.isNullOrBlank()
            && GameVersionNumber.isKnown(minecraftVersion)
            && GameVersionNumber.asGameVersion(minecraftVersion).isAtLeast("1.17", "21w10a")) {
            return false
        }
        return true
    }
}
