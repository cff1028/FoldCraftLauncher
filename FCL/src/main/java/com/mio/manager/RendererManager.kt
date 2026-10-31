package com.mio.manager

import android.content.Context
import com.mio.data.Renderer
import com.mio.plugin.DriverPlugin
import com.mio.plugin.RendererPlugin
import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.R

object RendererManager {
    lateinit var RENDERER_GL4ES: Renderer
    lateinit var RENDERER_VGPU: Renderer
    lateinit var RENDERER_ZINK: Renderer
    lateinit var RENDERER_NGGL4ES: Renderer
    lateinit var RENDERER_MOBILEGLUES: Renderer
    lateinit var RENDERER_QUEST_MOBILEGLUES: Renderer
    private var isInit = false

    @JvmStatic
    val rendererList: MutableList<Renderer> = mutableListOf()
        get() {
            if (!isInit) {
                init(FCLApp.getAppContext())
            }
            return field
        }

    fun init(context: Context) {
        if (isInit) return
        isInit = true
        rendererList.clear()
        RENDERER_GL4ES = Renderer(
            "Holy-GL4ES",
            context.getString(R.string.settings_fcl_renderer_gl4es),
            "libgl4es_114.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_GL4ES,
            "",
            "1.21.4"
        )

        RENDERER_VGPU = Renderer(
            "VGPU",
            context.getString(R.string.settings_fcl_renderer_vgpu),
            "libvgpu.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_VGPU,
            "",
            "1.16.5"
        )

        RENDERER_ZINK = Renderer(
            "Zink",
            context.getString(R.string.settings_fcl_renderer_zink),
            "libglxshim.so",
            "libEGL_mesa.so",
            "",
            null,
            null,
            Renderer.ID_ZINK,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_NGGL4ES = Renderer(
            "Krypton Wrapper",
            context.getString(R.string.settings_fcl_renderer_nggl4es),
            "libng_gl4es.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_NGGL4ES,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_MOBILEGLUES = Renderer(
            name = "MobileGlues 2.0.0",
            des = context.getString(R.string.settings_renderer_mobileglues),
            glName = "libquest_mobileglues.so",
            eglName = "libmobileglues.so",
            path = "",
            boatEnv = null,
            pojavEnv = null,
            id = Renderer.ID_MOBILEGLUES,
            displayMaxMCver = "26.2",
            vrCompatible = false,
            vrOnly = false
        )
        RENDERER_QUEST_MOBILEGLUES = Renderer(
            name = "MobileGlues VR",
            des = context.getString(R.string.settings_vr_renderer_mobileglues),
            glName = "libquest_mobileglues.so",
            eglName = "libEGL.so",
            path = "",
            boatEnv = null,
            pojavEnv = null,
            id = Renderer.ID_QUEST_MOBILEGLUES,
            displayMaxMCver = "26.2",
            vrCompatible = true,
            vrOnly = true
        )

        RendererPlugin.init(context)
        addRenderer()
        DriverPlugin.init(context)
    }

    private fun addRenderer() {
        rendererList.add(RENDERER_NGGL4ES)
        rendererList.add(RENDERER_GL4ES)
        rendererList.add(RENDERER_VGPU)
        rendererList.add(RENDERER_ZINK)
        rendererList.add(RENDERER_MOBILEGLUES)
        rendererList.add(RENDERER_QUEST_MOBILEGLUES)
        rendererList.addAll(RendererPlugin.rendererList)
    }

    fun refresh(context: Context) {
        RendererPlugin.refresh(context)
        rendererList.clear()
        addRenderer()
    }

    /** 原位替换同 id 的插件渲染器实例（v2 环境变量配置变化后调用），已初始化时才生效 */
    fun replaceRenderer(renderer: Renderer) {
        if (!isInit) return
        rendererList.removeIf { it.id == renderer.id }
        rendererList.add(renderer)
    }

    @JvmStatic
    fun getRenderer(id: String): Renderer {
        val resolvedId = if (id == "mobileglues") Renderer.ID_MOBILEGLUES else id
        return rendererList.find { it.id == resolvedId } ?: RENDERER_NGGL4ES
    }

    @JvmStatic
    fun getRenderersForMode(vrEnabled: Boolean): List<Renderer> = rendererList.filter {
        if (vrEnabled) it.vrCompatible else !it.vrOnly
    }

    @JvmStatic
    fun getRendererForMode(id: String, vrEnabled: Boolean): Renderer {
        val allowed = getRenderersForMode(vrEnabled)
        val resolvedId = if (id == "mobileglues") Renderer.ID_MOBILEGLUES else id
        return allowed.find { it.id == resolvedId }
            ?: if (vrEnabled) RENDERER_QUEST_MOBILEGLUES else RENDERER_NGGL4ES
    }

}
