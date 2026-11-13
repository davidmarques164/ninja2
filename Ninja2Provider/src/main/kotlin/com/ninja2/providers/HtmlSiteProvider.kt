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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import android.util.Base64

class HtmlSiteProvider : MainAPI() {

    override var name = "redecanais"
    override var mainUrl = "https://redecanais.by"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val domainKey = "redecanais"

    // Instância única do CloudflareKiller para preservar o cookie cf_clearance e o User-Agent
    private val cfKiller by lazy { CloudflareKiller() }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/browse-filmes-videos-1-date.html" to "Filmes",
        "/browse-series-videos-1-date.html" to "Séries",
        "/browse-animes-videos-1-date.html" to "Animes",
        "/browse-desenhos-videos-1-date.html" to "Desenhos",
        "/browse-novelas-videos-1-date.html" to "Novelas",
        "/browse-doramas-videos-1-date.html" to "Doramas",
        "/browse-Netflix-videos-1-date.html" to "Netflix",
        "/browse-disneyplus-videos-1-date.html" to "Disney+",
        "/browse-amazon-prime-video-videos-1-date.html" to "Amazon Prime Video",
        "/browse-Globoplay-mais-canais-globo-videos-1-date.html" to "Globoplay + Canais Globo",
        "/browse-hbo-max-videos-1-date.html" to "HBO Max",
        "/browse-paramountplus-videos-1-date.html" to "Paramount+",
        "/browse-apple-tv-plus-videos-1-date.html" to "AppleTV+",
        "/browse-discovery-plus-videos-1-date.html" to "Discovery+",
        "/browse-mgmplus-videos-1-date.html" to "Lionsgate+"
    )

    private fun pageUrl(base: String, path: String, page: Int): String {
        return "$base$path"
    }

    private fun searchUrl(base: String, query: String): String {
        return "$base/search.php?keywords=" + URLEncoder.encode(query, "UTF-8")
    }

    private object Sel {
        const val LIST_ITEM = "ul.pm-ul-carousel-videos li, ul.pm-ul-browse-videos li, div.thumbnail"
        const val ITEM_LINK = "a[href]"
        const val ITEM_TITLE = "h3 a, .caption h3 a, .title"
        const val ITEM_POSTER = "img"

        const val PAGE_TITLE = "h1"
        const val PAGE_POSTER = "div.poster img, .pm-video-watch-featured img"
        const val PAGE_PLOT = "div.sinopse, .description p"
        const val PAGE_YEAR = "span.ano, .year"
        const val PAGE_TAGS = ".entry-tags a, a[rel=tag]"

        const val EPISODE_LINK = "ul.episodios li a, .episodes a"
        const val PLAYER = "iframe, [data-src], [data-url], [data-player]"
    }

    private suspend fun base(): String {
        mainUrl = DomainManager.current(domainKey, mainUrl)
        return mainUrl
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        return runCatching { 
            app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer).document 
        }.getOrElse {
            val oldBase = mainUrl
            DomainManager.invalidate(domainKey)
            val newBase = base()
            if (newBase == oldBase) throw it
            app.get(url.replaceFirst(oldBase, newBase), headers = browserHeaders, interceptor = cfKiller, referer = referer).document
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(pageUrl(base(), request.data, page))
        val items = doc.select(Sel.LIST_ITEM).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
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
            ?: throw ErrorLoadingException("Título não encontrado")
        val poster = fixUrlNull(doc.selectFirst(Sel.PAGE_POSTER)?.imageUrl())
        val plot = doc.selectFirst(Sel.PAGE_PLOT)?.text()?.trim()
        val year = doc.selectFirst(Sel.PAGE_YEAR)?.text()?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        val tags = doc.select(Sel.PAGE_TAGS).map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodeLinks = doc.select(Sel.EPISODE_LINK)
        return if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { index, a ->
                val (season, number) = parseSeasonEpisode(a.text()) ?: (1 to index + 1)
                newEpisode(fixUrl(a.attr("href"))) {
                    name = a.text().trim()
                    this.season = season
                    episode = number
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        base()
        val doc = getDoc(data)

        val playerUrls = doc.select(Sel.PLAYER)
            .mapNotNull { it.playerUrl() }
            .map { fixUrl(it) }
            .distinct()

        var found = false
        for (url in playerUrls) {
            val finalUrl = runCatching { resolveRedirects(url, referer = data) }.getOrDefault(url)
            if (loadExtractor(finalUrl, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst(Sel.ITEM_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = (selectFirst(Sel.ITEM_TITLE)?.text() ?: link.attr("title")).trim()
        if (title.isEmpty() || DomainManager.isBlocked(title)) return null

        val poster = selectFirst(Sel.ITEM_POSTER)?.imageUrl()

        val type = when {
            "/series/" in href || "/browse-series-" in href || "lista-de-episodios" in href -> TvType.TvSeries
            "/animes/" in href || "/browse-animes-" in href -> TvType.Anime
            "/desenhos/" in href || "/browse-desenhos-" in href -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) { posterUrl = fixUrlNull(poster) }
    }

    private fun Element.imageUrl(): String? =
        listOf("data-echo", "data-src", "data-lazy-src", "data-original", "src")
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun Element.playerUrl(): String? =
        listOf("data-src", "data-url", "data-player", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "about:blank" }

    private fun parseSeasonEpisode(text: String): Pair<Int, Int>? {
        SE_REGEX.find(text)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return EP_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { 1 to it }
    }

    private suspend fun resolveRedirects(url: String, referer: String, depth: Int = 0): String {
        if (depth >= MAX_REDIRECT_DEPTH || !url.isSameSite()) return url

        // Passa o interceptor do CloudflareKiller e os headers também nas requisições de redirecionamento/player
        val res = app.get(url, headers = browserHeaders, interceptor = cfKiller, referer = referer)
        if (!res.url.isSameSite()) return res.url

        val html = res.text
        val doc = res.document
        
        val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.substringAfter("url=", "")?.replace(QUOTES_REGEX, "")?.trim()

        val next = doc.selectFirst("iframe")?.playerUrl()
            ?: metaRefresh
            ?: JS_LOCATION_REGEX.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }
            ?: BASE64_REGEX.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }?.decodeBase64()
            ?: return url

        if (next.isBlank()) return url
        return resolveRedirects(fixUrl(next), referer = url, depth = depth + 1)
    }

    private fun String.isSameSite(): Boolean =
        runCatching { URI(this).host == URI(mainUrl).host }.getOrDefault(false)

    private fun String.decodeBase64(): String? =
        runCatching { String(Base64.decode(this, Base64.DEFAULT)) }.getOrNull()
            ?.takeIf { it.startsWith("http") || it.startsWith("/") }

    private companion object {
        const val MAX_REDIRECT_DEPTH = 4
        val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
        val SE_REGEX = Regex("(\\d+)\\s*[xXeE]\\s*(\\d+)")
        val EP_REGEX = Regex("(?i)ep(?:is[oó]dio)?\\.?\\s*(\\d+)")
        val QUOTES_REGEX = Regex("[\"\']")
        val JS_LOCATION_REGEX =
            Regex("(?:window\\.|document\\.)?location(?:\\.href)?\\s*=\\s*\\x22([^\\x22]+)\\x22|\\x27([^\\x27]+)\\x27")
        val BASE64_REGEX = Regex("atob\\(\\s*\\x22([A-Za-z0-9+/=]{16,})\\x22|\\x27([A-Za-z0-9+/=]{16,})\\x27\\s*\\)")
    }
}