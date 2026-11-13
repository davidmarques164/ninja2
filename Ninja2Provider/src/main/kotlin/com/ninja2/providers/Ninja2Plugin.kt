package com.ninja2.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Ponto de entrada do plugin: registre aqui cada provedor do bundle. */
@CloudstreamPlugin
class Ninja2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HtmlSiteProvider())
        registerMainAPI(LiveSiteProvider())
        // registerMainAPI(OutroProvider())
    }
}
