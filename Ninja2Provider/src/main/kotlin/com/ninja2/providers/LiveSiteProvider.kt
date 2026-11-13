package com.ninja2.providers

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LiveSiteProvider : MainAPI() {

    override var name = "Rede Canais TV"
    override var mainUrl = "https://redecanaistv.by"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val domainKey = "redecanaistv"

    override val mainPage = mainPageOf(
        "/" to "Todos os canais"
    )

    private val paginated = false
    private fun pageUrl(base: String, path: String, page: Int): String {
        return if (paginated) "$base$path/page/$page" else "$base$path"
    }

    private fun searchUrl(base: String, query: String): String {
        return "$base/search.php?keywords=" + URLEncoder.encode(query, "UTF-8")
    }

    private object Sel {
        const val LIST_ITEM = "div.thumbnail, div.canal, div.channel"
        const val ITEM_LINK = "a[href]"
        const val ITEM_TITLE = "h3 a, .title"
        const val ITEM_LOGO = "img"

        const val PAGE_TITLE = "h1"
        const val PAGE_LOGO = "div.poster img, .capa img"
        const val PAGE_PLOT = "div.sinopse, .description"

        const val PLAYER = "iframe, [data-src], [data-url], [data-player]"
    }

    private suspend fun base(): String {
        mainUrl = DomainManager.current(domainKey, mainUrl)
        return mainUrl
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        return runCatching { app.get(url, referer = referer).document }.getOrElse {
            val oldBase = mainUrl
            DomainManager.invalidate(domainKey)
            val newBase = base()
            if (newBase == oldBase) throw it
            app.get(url.replaceFirst(oldBase, newBase), referer = referer).document
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!paginated && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = getDoc(pageUrl(base(), request.data, page))
        val items = doc.select(Sel.LIST_ITEM).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = paginated && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        getDoc(searchUrl(base(), query))
            .select(Sel.LIST_ITEM)
            .mapNotNull { it.toSearchResult() }

    override suspend fun load(url: String): LoadResponse {
        base()
        val doc = getDoc(url)

        val title = doc.selectFirst(Sel.PAGE_TITLE)?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Nome do canal não encontrado")
        val logo = fixUrlNull(doc.selectFirst(Sel.PAGE_LOGO)?.imageUrl())
        val plot = doc.selectFirst(Sel.PAGE_PLOT)?.text()?.trim()

        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = logo
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        base()
        val page = app.get(data)

        var found = emitM3u8(page.text, referer = data, label = name, callback = callback)

        val playerUrls = page.document.select(Sel.PLAYER)
            .mapNotNull { it.playerUrl() }
            .map { fixUrl(it) }
            .distinct()

        playerUrls.forEachIndexed { i, playerUrl ->
            val label = if (playerUrls.size > 1) "$name ${i + 1}" else name
            val html = runCatching { app.get(playerUrl, referer = data).text }.getOrNull()
            if (html != null && emitM3u8(html, referer = playerUrl, label = label, callback = callback)) {
                found = true
            } else if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun emitM3u8(
        html: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decoded = BASE64_REGEX.findAll(html)
            .mapNotNull { it.groupValues[1].decodeBase64() }
            .joinToString("\n")
        val urls = M3U8_REGEX.findAll(html.unescapeJs() + "\n" + decoded)
            .map { it.value }
            .distinct()
            .toList()

        urls.forEach { url ->
            callback(
                newExtractorLink(label, label, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    quality = Qualities.Unknown.value
                }
            )
        }
        return urls.isNotEmpty()
    }

    private fun String.unescapeJs(): String = replace("\\/", "/")

    private fun String.decodeBase64(): String? =
        runCatching { String(android.util.Base64.decode(this, android.util.Base64.DEFAULT)) }.getOrNull()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = (if (tagName() == "a") this else selectFirst(Sel.ITEM_LINK)) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = (selectFirst(Sel.ITEM_TITLE)?.text()
            ?: link.attr("title").ifBlank { selectFirst("img")?.attr("alt").orEmpty() }).trim()
        if (title.isEmpty() || DomainManager.isBlocked(title)) return null

        val logo = selectFirst(Sel.ITEM_LOGO)?.imageUrl()
        return newLiveSearchResponse(title, href, TvType.Live) { posterUrl = fixUrlNull(logo) }
    }

    private fun Element.imageUrl(): String? =
        listOf("data-echo", "data-src", "data-lazy-src", "data-original", "src")
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun Element.playerUrl(): String? =
        listOf("data-src", "data-url", "data-player", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "about:blank" }

    private companion object {
        val M3U8_REGEX = Regex("https?://[^\\s\"'<>\\\\]+?\\.m3u8[^\\s\"'<>\\\\]*")
        val BASE64_REGEX = Regex("atob\\(\\s*['\"]([A-Za-z0-9+/=]{16,})['\"]\\s*\\)")
    }
}