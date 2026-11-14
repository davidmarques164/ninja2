package com.ninja2.providers

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.net.SocketAddress
import java.security.KeyStore
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Logs: adb logcat -s RedeCanais
 */
class HtmlSiteProvider : MainAPI() {

    override var name = "Rede Canais V23"
    override var mainUrl = "https://redecanais.by"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama
    )

    private val domainKey = "redecanais"

    /** Headers para hosts de terceiros (players/CDNs). */
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    /** Headers só para o próprio site. */
    private val siteHeaders = baseHeaders + ("X-Requested-With" to "RC-Site-Requests")

    private val cfKiller by lazy { CloudflareKiller() }

    /** Poster visto na listagem/busca, indexado pelo path (independe do domínio). */
    private val posterCache = ConcurrentHashMap<String, String>()

    override val mainPage = mainPageOf(
        "/browse-filmes-videos-1-date.html" to "Filmes",
        "/browse-series-videos-1-date.html" to "Séries",
        "/browse-animes-videos-1-date.html" to "Animes",
        "/browse-desenhos-videos-1-date.html" to "Desenhos",
        "/browse-novelas-videos-1-date.html" to "Novelas",
        "/browse-doramas-videos-1-date.html" to "Doramas",
        "/browse-netflix-videos-1-date.html" to "Netflix",
        "/browse-disneyplus-videos-1-date.html" to "Disney+",
        "/browse-amazon-prime-video-videos-1-date.html" to "Amazon Prime Video",
        "/browse-globoplay-mais-canais-globo-videos-1-date.html" to "Globoplay + Canais Globo",
        "/browse-hbo-max-videos-1-date.html" to "HBO Max",
        "/browse-paramountplus-videos-1-date.html" to "Paramount+",
        "/browse-apple-tv-plus-videos-1-date.html" to "Apple TV+",
        "/browse-discovery-plus-videos-1-date.html" to "Discovery+",
        "/browse-mgmplus-videos-1-date.html" to "Lionsgate+"
    )

    private object Sel {
        const val LIST_ITEM = "ul.pm-ul-carousel-videos li, ul.pm-ul-browse-videos li"
        /**
         * O 1º <a> de cada item é o botão "Watch Later" (href="#modal-login-form").
         * Pegar "a[href]" fazia todos os itens apontarem para a home.
         */
        const val ITEM_LINK = ".caption h3 a[href], h3 a[href], a[href\$=.html]:not(.pm-watch-later-add)"
        const val ITEM_TITLE = ".caption h3 a, h3 a, .title"
        const val ITEM_POSTER = "img[data-echo], img"

        /** Bloco principal (o <h1> fica fora dele, por isso título/sinopse usam o doc). */
        const val MAIN = ".pm-video-watch-main, #content-main, main, #content"
        const val PAGE_TITLE = "h1[itemprop=name], h1"
        const val PAGE_POSTER = "meta[itemprop=thumbnailUrl], link[rel=image_src]"
        const val PAGE_PLOT = "[itemprop=description]"
        const val PAGE_YEAR = "span.ano, .year"
        const val PAGE_TAGS = ".entry-tags a, a[rel=tag], .pm-video-tags a, a[href*=/tags/]"
        const val BREADCRUMB = ".breadcrumb a, ul.breadcrumb li a"
        const val PAGER = ".pagination, ul.pagination, .pm-pagination"

        const val EPISODE_LIST_LINK = "ul.episodios li a, .episodes a"
        const val EPISODE_DESC_LINK = "[itemprop=description] a[href], .pm-video-description a[href], .pm-category-description a[href]"
        const val SEASON_HEADER = "strong, b, h2, h3, h4, span"

        const val PLAYER_CONTAINER = "#player, .player, .pm-video-watch-main, .embed-responsive, #video-player"
        const val PLAYER = "iframe[src], iframe[data-src], [data-player], [data-url]"
    }

    // ---------------------------------------------------------------- util

    private fun log(step: String, msg: String) {
        Log.d(TAG, "[$step] $msg")
    }

    /** runCatching que não engole CancellationException. */
    private inline fun <T> safe(step: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "[$step] ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun pageUrl(base: String, path: String, page: Int): String =
        base + path.replace(PAGE_REGEX, "-$page-date.html")

    private fun searchUrl(base: String, query: String): String =
        "$base/search.php?keywords=" + URLEncoder.encode(query, "UTF-8")

    private fun typeFromUrl(href: String, hint: TvType? = null): TvType = when {
        "/series/" in href || "/browse-series-" in href || "lista-de-episodios" in href -> TvType.TvSeries
        "/animes/" in href || "/browse-animes-" in href -> TvType.Anime
        "/desenhos/" in href || "/browse-desenhos-" in href -> TvType.Cartoon
        "/doramas/" in href || "/browse-doramas-" in href -> TvType.AsianDrama
        hint != null -> hint
        else -> TvType.Movie
    }

    private fun typeFromCategory(path: String): TvType? = when {
        "/browse-series-" in path || "/browse-novelas-" in path -> TvType.TvSeries
        "/browse-doramas-" in path -> TvType.AsianDrama
        "/browse-animes-" in path -> TvType.Anime
        "/browse-desenhos-" in path -> TvType.Cartoon
        "/browse-filmes-" in path -> TvType.Movie
        else -> null
    }

    // ---------------------------------------------------------------- rede

    private suspend fun base(): String {
        mainUrl = DomainManager.current(domainKey, mainUrl)
        return mainUrl
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        val current = base()
        val target = url.rehostIfOurs(current)
        fetch(target, referer)?.let { return it }

        log("getDoc", "falhou em $current, invalidando domínio")
        DomainManager.invalidate(domainKey)
        val newBase = base()
        if (newBase == current) throw ErrorLoadingException("Falha ao carregar $target")
        log("getDoc", "novo domínio: $newBase")
        return fetch(target.rehostIfOurs(newBase), referer)
            ?: throw ErrorLoadingException("Falha ao carregar $target")
    }

    /**
     * null  -> falha de rede ou HTTP inesperado (justifica trocar de domínio)
     * throw -> 404 ou bloqueio (403/429/503): trocar de domínio não resolve
     */
    private suspend fun fetch(url: String, referer: String?): Document? {
        val res = safe("fetch") { app.get(url, headers = siteHeaders, referer = referer) }
            ?: run { log("fetch", "falha de rede: $url"); return null }

        if (res.isSuccessful) return res.document
        if (res.code == 404) throw ErrorLoadingException("Página não encontrada (404)")

        if (res.code in BLOCK_CODES) {
            log("fetch", "HTTP ${res.code} em $url, tentando CloudflareKiller")
            val retry = safe("fetch-cf") {
                app.get(url, headers = siteHeaders, referer = referer, interceptor = cfKiller)
            }
            if (retry?.isSuccessful == true) return retry.document
            throw ErrorLoadingException("Acesso bloqueado (HTTP ${res.code})")
        }

        log("fetch", "HTTP ${res.code} em $url")
        return null
    }

    private fun headersFor(url: String) = if (url.isSameSite()) siteHeaders else baseHeaders

    // ---------------------------------------------------------------- listagem / busca

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(pageUrl(base(), request.data, page))
        val host = currentHost()
        val hint = typeFromCategory(request.data)
        val items = doc.select(Sel.LIST_ITEM)
            .mapNotNull { it.toSearchResult(host, hint) }
            .distinctBy { it.url }

        val nextMarker = "-${page + 1}-date.html"
        val hasPager = doc.selectFirst(Sel.PAGER) != null
        val hasNext = items.isNotEmpty() &&
            (!hasPager || doc.select("a[href]").any { nextMarker in it.attr("href") })

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc(searchUrl(base(), query))
        val host = currentHost()
        return doc.select(Sel.LIST_ITEM)
            .mapNotNull { it.toSearchResult(host, null) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(host: String, hint: TvType?): SearchResponse? {
        val link = selectFirst(Sel.ITEM_LINK) ?: return null
        val href = fixUrl(link.attr("href")).rehostIfOurs(mainUrl)
        val title = selectFirst(Sel.ITEM_TITLE)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim().orEmpty()
        if (title.isEmpty() || DomainManager.isBlocked(title)) return null

        val itemHost = runCatching { URI(href).host.normHost() }.getOrNull() ?: return null
        if (host.isNotEmpty() && itemHost != host && !itemHost.endsWith(".$host")) return null

        val poster = fixUrlNull(selectFirst(Sel.ITEM_POSTER)?.imageUrl())
        poster?.let { posterCache[href.pathKey()] = it }

        return newMovieSearchResponse(title, href, typeFromUrl(href, hint)) { posterUrl = poster }
    }

    // ---------------------------------------------------------------- detalhes

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val pageUrl = url.rehostIfOurs(mainUrl)
        val main: Element = doc.selectFirst(Sel.MAIN) ?: doc.body()

        val rawTitle = doc.selectFirst(Sel.PAGE_TITLE)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.metaContent("og:title")
            ?: throw ErrorLoadingException("Título não encontrado")
        val title = rawTitle.replace(LIST_SUFFIX_REGEX, "").trim().ifEmpty { rawTitle }

        // Poster: 1) o mesmo da listagem  2) og:image  3) imagem do bloco principal
        val posterCandidates = listOf(
            "cache" to posterCache[url.pathKey()],
            "og:image" to doc.metaContent("og:image"),
            "itemprop" to doc.selectFirst(Sel.PAGE_POSTER)?.let { it.attr("content").ifBlank { it.attr("href") } },
        )
        val poster = pick(posterCandidates, GENERIC_IMG_REGEX)

        // Descrição: [itemprop=description] tem o texto completo; og:description vem cortado com "..."
        // Em série, [itemprop=description] é a lista de episódios: a sinopse fica nas metas.
        val episodes = parseEpisodes(main)
        val plotCandidates = if (episodes.isNotEmpty()) listOf(
            "og:description" to doc.metaContent("og:description"),
            "description" to doc.metaContent("description"),
        ) else listOf(
            "itemprop" to doc.selectFirst(Sel.PAGE_PLOT)?.text()?.trim()?.takeIf { it.isNotEmpty() },
            "og:description" to doc.metaContent("og:description"),
            "description" to doc.metaContent("description"),
        )
        val plot = pick(plotCandidates, GENERIC_TEXT_REGEX)

        log("load", "url=$pageUrl título=$title")
        log("load", "poster candidatos=$posterCandidates escolhido=$poster")
        log("load", "plot candidatos=${plotCandidates.map { it.first to it.second?.take(80) }}")

        val year = (main.selectFirst(Sel.PAGE_YEAR)?.text() ?: title)
            .let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        val tags = main.select(Sel.PAGE_TAGS).map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        log("load", "episódios=${episodes.size}")

        // ---- TMDB: poster, fundo, sinopse, nota, duração, gêneros, elenco, trailer e dados por episódio
        val isSeries = episodes.isNotEmpty()
        val seasons = episodes.mapNotNull { it.season }.distinct()
        val meta = safe("tmdb") { Tmdb.lookup(title, year, isSeries, seasons, TMDB_TIMEOUT_MS) }
        log("load", "tmdb=" + (meta?.let { "${it.id} ${it.title} (${it.year}) eps=${it.episodes.size}" } ?: "sem resultado"))

        // Sinopse do site em série costuma ser a própria lista de episódios: descarta nesse caso.
        val sitePlot = plot?.takeUnless { isSeries && LIST_PLOT_REGEX.containsMatchIn(it) }
        val finalPlot = meta?.overview?.takeIf { it.isNotBlank() } ?: sitePlot
        val finalPoster = meta?.poster ?: fixUrlNull(poster)
        val finalTags = meta?.genres?.takeIf { it.isNotEmpty() } ?: tags
        val finalYear = meta?.year ?: year

        if (meta != null && isSeries) {
            for (ep in episodes) {
                val e = meta.episodes[(ep.season ?: 1) to (ep.episode ?: continue)] ?: continue
                e.still?.let { ep.posterUrl = it }
                e.overview?.takeIf { it.isNotBlank() }?.let { ep.description = it }
                e.runtime?.let { ep.runTime = it }
                e.airDate?.let { ep.addDate(it) }
                if (ep.name.isNullOrBlank() || GENERIC_EP_NAME_REGEX.matches(ep.name ?: "")) {
                    e.name?.takeIf { it.isNotBlank() }?.let { ep.name = it }
                }
            }
        }

        return if (isSeries) {
            val crumbs = (doc.select(Sel.BREADCRUMB).map { it.text() } + tags).joinToString(" ").lowercase()
            val type = when {
                "dorama" in crumbs -> TvType.AsianDrama
                "desenho" in crumbs -> TvType.Cartoon
                "anime" in crumbs -> TvType.Anime
                else -> typeFromUrl(pageUrl, TvType.TvSeries)
            }
            newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                posterUrl = finalPoster; this.plot = finalPlot; this.year = finalYear; this.tags = finalTags
                applyTmdb(meta)
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = finalPoster; this.plot = finalPlot; this.year = finalYear; this.tags = finalTags
                applyTmdb(meta)
            }
        }
    }

    /** Campos comuns a filme e série vindos do TMDB. */
    private suspend fun LoadResponse.applyTmdb(meta: TmdbMeta?) {
        if (meta == null) return
        backgroundPosterUrl = meta.backdrop ?: backgroundPosterUrl
        meta.runtime?.let { duration = it }
        meta.vote?.takeIf { it > 0 }?.let { score = Score.from10(it) }
        actors = meta.cast.map { (name, character, photo) -> ActorData(Actor(name, photo), roleString = character) }
        addTMDbId(meta.id.toString())
        addImdbId(meta.imdbId)
        meta.trailers.forEach { addTrailer(it) }
    }

    /** Primeiro candidato não genérico; se todos forem genéricos, o primeiro não nulo. */
    private fun pick(candidates: List<Pair<String, String?>>, generic: Regex): String? {
        val values = candidates.mapNotNull { it.second?.takeIf { v -> v.isNotBlank() } }
        return values.firstOrNull { !generic.containsMatchIn(it) } ?: values.firstOrNull()
    }

    private class EpisodeAcc(val name: String) {
        val urls = LinkedHashSet<String>()
    }

    /**
     * Dublado/legendado do mesmo episódio viram um único Episode;
     * as URLs vão juntas no data, separadas por DATA_SEP.
     */
    private fun parseEpisodes(root: Element): List<Episode> {
        val useList = root.select(Sel.EPISODE_LIST_LINK).isNotEmpty()
        val linkSelector = if (useList) Sel.EPISODE_LIST_LINK else Sel.EPISODE_DESC_LINK

        var currentSeason = 1
        val counters = mutableMapOf<Int, Int>()
        val seen = HashSet<String>()
        val grouped = LinkedHashMap<Pair<Int, Int>, EpisodeAcc>()

        for (el in root.select("${Sel.SEASON_HEADER}, $linkSelector")) {
            if (el.`is`(linkSelector)) {
                // Na descrição o link é só "Assistir"; número e nome ficam no texto da mesma
                // linha, antes do link: "<strong>Episódio 01 - </strong>Nome - <a>Assistir</a><br>"
                val prefix = if (useList) "" else lineTextBefore(el)
                val text = if (useList) el.text().trim() else "$prefix ${el.text()}".trim()
                if (!useList && !EP_REGEX.containsMatchIn(text) && !SE_REGEX.containsMatchIn(text)) continue
                val href = fixUrl(el.attr("href")).rehostIfOurs(mainUrl)
                if (!seen.add(href)) continue

                val epAtStart = EP_REGEX.find(text)?.takeIf { it.range.first <= 3 }
                val parsed = if (epAtStart != null) null to epAtStart.groupValues[1].toInt()
                             else parseSeasonEpisode(text)
                val season = parsed?.first ?: currentSeason
                val number = parsed?.second ?: ((counters[season] ?: 0) + 1)
                counters[season] = maxOf(counters[season] ?: 0, number)

                val name = if (!useList && prefix.isNotBlank()) {
                    val afterNum = EP_REGEX.find(prefix)?.let { prefix.substring(it.range.last + 1) } ?: prefix
                    afterNum.trim().trim('-', '–', '|', ' ').trim().replace(EP_SUFFIX_REGEX, "")
                        .ifBlank { "Episódio $number" }
                } else {
                    text.replace(EP_SUFFIX_REGEX, "").ifBlank { text }
                }
                grouped.getOrPut(season to number) { EpisodeAcc(name) }.urls += href
            } else if (el.children().size <= 1) {
                SEASON_REGEX.find(el.ownText())?.let { m ->
                    m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()?.let { currentSeason = it }
                }
            }
        }

        return grouped.map { (key, acc) ->
            newEpisode(acc.urls.joinToString(DATA_SEP)) {
                this.name = acc.name
                this.season = key.first
                this.episode = key.second
            }
        }
    }

    /** Texto da mesma "linha" antes do elemento: irmãos anteriores até o <br> (ou início do bloco). */
    private fun lineTextBefore(el: Element): String {
        val parts = ArrayList<String>()
        var n: Node? = el.previousSibling()
        while (n != null) {
            if (n is Element && n.tagName().equals("br", ignoreCase = true)) break
            when (n) {
                is TextNode -> parts.add(n.text())
                is Element -> if (!n.tagName().equals("img", ignoreCase = true)) parts.add(n.text())
            }
            n = n.previousSibling()
        }
        return parts.asReversed().joinToString("").replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun parseSeasonEpisode(text: String): Pair<Int?, Int>? {
        SE_REGEX.find(text)?.let { m ->
            val season = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull()
            val ep = m.groupValues[3].toIntOrNull()
            if (season != null && ep != null) return season to ep
        }
        return EP_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { null to it }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val found = AtomicBoolean(false)
        val emitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        RcDns.install()
        val pages = data.split(DATA_SEP).map { it.trim() }.filter { it.isNotEmpty() }
        log("loadLinks", "início: ${pages.size} página(s) $pages")

        pages.amap { page ->
            val ok = safe("loadLinks") { linksFromPage(page, subtitleCallback, callback, emitted) } ?: false
            if (ok) found.set(true)
        }

        log("loadLinks", "fim: encontrou=${found.get()} links=${emitted.size}")
        return found.get()
    }

    private suspend fun linksFromPage(
        page: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>,
    ): Boolean {
        val doc = getDoc(page)
        val pageUrl = page.rehostIfOurs(mainUrl)
        val html = doc.outerHtml().unescapeJs()
        val ok = AtomicBoolean(false)

        // 1) vídeo direto já na página do episódio/filme
        if (scanVideos(html, pageUrl, emitted, callback)) ok.set(true)

        // 2) players (DOM + URLs montadas em JS)
        val players = findPlayers(doc, html, pageUrl)
        log("página", "$pageUrl players=${players.size} $players")
        if (players.isEmpty()) {
            log("página", "nenhum player no HTML estático. Trecho do container: " +
                (doc.selectFirst(Sel.PLAYER_CONTAINER)?.outerHtml()?.take(LOG_SNIPPET) ?: "<sem container>"))
        }

        // Player próprio do site (/player3/server.php): o link só sai via WebView.
        // Um WebView por vídeo (distinct por vid), e os demais players são ignorados
        // para não abrir WebView à toa (ex.: /embed.php aponta para o mesmo vídeo).
        val rcPlayers = players.filter { RC_PLAYER_REGEX.containsMatchIn(it) }
            .distinctBy { VID_REGEX.find(it)?.groupValues?.get(1) ?: it }
        if (rcPlayers.isNotEmpty()) {
            for (player in rcPlayers) {
                val res = safe("rc") { resolveRcPlayer(player, pageUrl, subtitleCallback, callback, emitted) } ?: false
                if (res) ok.set(true)
            }
            return ok.get()
        }

        players.amap { player ->
            val res = safe("player") { followPlayer(player, pageUrl, subtitleCallback, callback, emitted) } ?: false
            if (res) ok.set(true)
        }
        return ok.get()
    }

    /**
     * Fluxo do player do Rede Canais (capturado no HAR):
     *  server.php -> POST serverforms.api (tokens gerados por JS ofuscado em VM)
     *  -> POST jquery.videojs.4.5.2.api (rctoken) -> URL assinada, presa ao IP, validade ~5 min:
     *  //...null-null.shop/tos-alisg-avt-0068/proxy?container=videos&...&url=https://neosoro.gq/V/<server>/<pasta>/<vid>.mp4?...
     * Os tokens não são reproduzíveis fora do navegador, então o server.php roda num
     * WebView e a requisição do vídeo (o player faz um probe com Range antes de tocar)
     * é interceptada.
     */
    private suspend fun resolveRcPlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>,
    ): Boolean {
        addRcSubtitle(playerUrl, subtitleCallback)

        log("rc", "WebView em $playerUrl")
        val result = safe("rc-webview") {
            WebViewResolver(
                interceptUrl = RC_INTERCEPT_REGEX,
                useOkhttp = false,
                script = RC_CAPTURE_SCRIPT,
            ).resolveUsingWebView(playerUrl, referer)
        } ?: return debugLink("WebView falhou ao iniciar", callback)

        val intercepted = result.first?.url?.toString()
        log("rc", "interceptado=$intercepted outros=${result.second.size}")
        if (intercepted == null) {
            return debugLink("timeout: o script de captura não respondeu (página não carregou ou bloqueou o WebView)", callback)
        }

        // Sentinela do script: ?u=<url do vídeo> ou ?err=<estado do player>
        val captured = if (RC_CAPTURE_HOST in intercepted) {
            val err = CAPTURE_ERR_REGEX.find(intercepted)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            if (err != null) return debugLink("player sem vídeo: $err", callback)
            CAPTURE_URL_REGEX.find(intercepted)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                ?: return debugLink("sentinela sem URL", callback)
        } else intercepted

        val videoUrl = captured.unwrapRcProxy()
        if (!emitted.add(videoUrl)) return true
        val site = mainUrl.trimEnd('/')
        val quality = getQualityFromName(RC_QUALITY_REGEX.find(referer)?.value)
        val playHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to site,
            "X-Requested-With" to "RC-Site-Requests",
            RC_HEADER_NAME to RC_HEADER_VALUE,
        )

        // O host do proxy ("-_kerberos-__tcp-...null-null.shop") tem "_": o TLS do Android
        // recusa esse nome. Testa o original e variantes antes de entregar.
        val variants = rcVariants(videoUrl)
        val results = variants.amap { v -> v to preflight(v.url, "$site/", playHeaders + v.headers) }
        log("vídeo", "testes: ${results.map { it.first.label + "=" + it.second }}")

        // O player do app usa um cliente de rede próprio (sem o DNS/TLS do provider). Então a
        // variante que passou no teste é servida por um proxy local em 127.0.0.1, que repassa
        // cada pedido (Range) pelo cliente do app, onde o contorno funciona.
        val allResults = results.toMutableList()
        results.firstOrNull { it.second.startsWith("2") && it.first.label != "direto" }?.let { (v, _) ->
            val upstreamHeaders = playHeaders + v.headers + mapOf(
                "Referer" to "$site/",
                "Accept-Encoding" to "identity",
            )
            val localUrl = safe("local") { RcLocalProxy.register(v.url, upstreamHeaders) }
            if (localUrl != null) {
                val res = preflight(localUrl, "$site/", emptyMap())
                allResults.add(0, Variant("local", localUrl) to res)
            }
        }
        log("vídeo", "com local: ${allResults.map { it.first.label + "=" + it.second }}")

        val working = allResults.filter { it.second.startsWith("2") }
        val toEmit = working.ifEmpty { allResults.take(1) }   // nenhum passou: entrega o primeiro mesmo assim
        for ((v, _) in toEmit) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${v.label}",
                    url = v.url,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$site/"
                    this.quality = quality
                    this.headers = if (v.label == "local") emptyMap() else playHeaders + v.headers
                }
            )
        }
        if (DEBUG_LINKS) {
            debugLink("teste: " + allResults.joinToString(" | ") { "${it.first.label}=${it.second}" } +
                " | dns: " + RcDns.report(), callback)
        }
        return true
    }

    private data class Variant(val label: String, val url: String, val headers: Map<String, String> = emptyMap())

    /**
     * Variantes do mesmo link assinado (em ordem de preferência):
     *  proxy-sni -> mesmo caminho em "rcv.<domínio>" (nome válido, coberto pelo certificado
     *               *.<domínio>); o DNS próprio aponta esse nome para os IPs do host real,
     *               e o host real vai no header Host. Evita o nome com "_" no TLS.
     *  proxy     -> URL original
     *  direto    -> URL de origem dentro do parâmetro url= (neosoro.gq)
     */
    private fun rcVariants(videoUrl: String): List<Variant> {
        val out = mutableListOf<Variant>()
        val realHost = videoUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        val root = realHost.split('.').takeLast(2).joinToString(".")
        if (!isValidSni(realHost) && realHost.count { it == '.' } >= 2) {
            val alias = "rcv.$root"
            RcDns.alias(alias, realHost)
            out += Variant("proxy-sni", videoUrl.replaceFirst(realHost, alias), mapOf("Host" to realHost))
        }
        out += Variant("proxy", videoUrl)
        INNER_URL_REGEX.find(videoUrl)?.groupValues?.get(1)?.let { inner ->
            val decoded = if (inner.startsWith("http")) inner else URLDecoder.decode(inner, "UTF-8")
            if (decoded.startsWith("http")) out += Variant("direto", decoded)
        }
        return out.distinctBy { it.url }
    }

    private fun isValidSni(host: String) = runCatching { SNIHostName(host) }.isSuccess

    /** GET com Range de 2 bytes. Devolve o status HTTP ou a causa raiz da exceção + onde ocorreu. */
    private suspend fun preflight(url: String, referer: String, headers: Map<String, String>): String = try {
        val res = app.get(url, headers = headers + ("Range" to "bytes=0-1"), referer = referer, timeout = 15L)
        res.code.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val at = root.stackTrace.take(2).joinToString(">") { it.className.substringAfterLast('.') + "." + it.methodName }
        Log.w(TAG, "[preflight] $url", e)
        e.javaClass.simpleName + " / raiz=" + root.javaClass.simpleName + ":" + (root.message?.take(60) ?: "") + " @" + at
    }

    /** Legenda em /player3/RedeCanaisVTTS/vtts/<server>/<vid>.vtt (só se existir). */
    private suspend fun addRcSubtitle(playerUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val server = SERVER_PARAM_REGEX.find(playerUrl)?.groupValues?.get(1) ?: return
        val vid = VID_REGEX.find(playerUrl)?.groupValues?.get(1) ?: return
        val vtt = "${mainUrl.trimEnd('/')}/player3/RedeCanaisVTTS/vtts/$server/$vid.vtt"
        val res = safe("vtt") { app.get(vtt, headers = siteHeaders, referer = playerUrl) } ?: return
        if (res.isSuccessful && res.text.trimStart().startsWith("WEBVTT")) {
            log("rc", "legenda $vtt")
            subtitleCallback(SubtitleFile("Português", vtt))
        }
    }

    /**
     * Com DEBUG_LINKS, a falha aparece na lista de fontes como "[DEBUG] motivo".
     * O link não toca; é só para ler o motivo. Desligue DEBUG_LINKS depois.
     */
    private suspend fun debugLink(reason: String, callback: (ExtractorLink) -> Unit): Boolean {
        log("rc", "falha: $reason")
        if (!DEBUG_LINKS) return false
        callback(
            newExtractorLink(
                source = name,
                name = "[DEBUG] ${reason.take(400)}",
                url = "https://$RC_CAPTURE_HOST/debug.mp4",
                type = ExtractorLinkType.VIDEO,
            )
        )
        return true
    }

    /** "/__RC__/proxy?src=<url codificada>" é rota do service worker; a URL real está em src. */
    private fun String.unwrapRcProxy(): String {
        val src = RC_SW_PROXY_REGEX.find(this)?.groupValues?.get(1)
        val real = src?.let { URLDecoder.decode(it, "UTF-8") } ?: this
        return if (real.startsWith("//")) "https:$real" else real
    }

    private fun findPlayers(doc: Document, html: String, pageUrl: String): List<String> {
        val scope: Element = doc.selectFirst(Sel.PLAYER_CONTAINER) ?: doc
        val fromDom = scope.select(Sel.PLAYER).ifEmpty { doc.select(Sel.PLAYER) }
            .filter { it.tagName() != "img" }
            .mapNotNull { it.playerUrl() }

        val fromJs = IFRAME_IN_HTML_REGEX.findAll(html).map { it.groupValues[1] } +
            PHP_URL_REGEX.findAll(html).map { it.groupValues[1] }

        return (fromDom + fromJs)
            .map { it.unescapeJs().resolveAgainst(pageUrl) }
            .filter {
                it.startsWith("http") && it != pageUrl &&
                    !it.isJunkHost() && !STATIC_ASSET_REGEX.containsMatchIn(it)
            }
            .distinct()
            .take(MAX_PLAYERS)
    }

    /**
     * Segue a cadeia player -> servidor -> vídeo, até MAX_HOPS.
     * Em cada passo: extractor conhecido? vídeo no HTML? próximo iframe/redirect?
     */
    private suspend fun followPlayer(
        start: String,
        firstReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>,
    ): Boolean {
        var url = start
        var referer = firstReferer
        val visited = HashSet<String>()

        for (hop in 0 until MAX_HOPS) {
            if (!visited.add(url)) {
                log("player", "hop=$hop loop em $url")
                return false
            }
            if (url.isDirectVideo()) return emitDirect(url, referer, emitted, callback)

            if (!url.isSameSite() && tryExtractor(url, referer, subtitleCallback, callback, hop)) return true

            val res = safe("player") { app.get(url, headers = headersFor(url), referer = referer) }
                ?: return false
            val finalUrl = res.url
            val html = res.text.unescapeJs()
            log("player", "hop=$hop GET $url -> HTTP ${res.code} final=$finalUrl tamanho=${html.length}")

            if (finalUrl != url && !finalUrl.isSameSite() &&
                tryExtractor(finalUrl, url, subtitleCallback, callback, hop)
            ) return true

            if (scanVideos(html, finalUrl, emitted, callback)) return true

            val next = nextHop(res.document, html)?.unescapeJs()?.resolveAgainst(finalUrl)
            if (next.isNullOrBlank() || next == finalUrl) {
                log("player", "hop=$hop sem próximo passo em $finalUrl. Trecho: ${html.take(LOG_SNIPPET)}")
                return false
            }
            log("player", "hop=$hop próximo -> $next")
            referer = finalUrl
            url = next
        }
        log("player", "MAX_HOPS atingido a partir de $start")
        return false
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        hop: Int,
    ): Boolean {
        val ok = safe("extractor") { loadExtractor(url, referer, subtitleCallback, callback) } == true
        log("player", "hop=$hop extractor($url) = $ok")
        return ok
    }

    private fun nextHop(doc: Document, html: String): String? {
        doc.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")
            ?.let { META_URL_REGEX.find(it)?.groupValues?.get(1) }
            ?.replace(QUOTES_REGEX, "")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val container = doc.selectFirst(Sel.PLAYER_CONTAINER)
        val iframes = (container?.select("iframe").orEmpty() + doc.select("iframe"))
            .mapNotNull { it.playerUrl() }
        iframes.firstOrNull { !it.isJunkHost() }?.let { return it }

        IFRAME_IN_HTML_REGEX.findAll(html).map { it.groupValues[1] }
            .firstOrNull { !it.isJunkHost() && !STATIC_ASSET_REGEX.containsMatchIn(it) }
            ?.let { return it }

        JS_LOCATION_REGEX.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?.let { return it }

        return BASE64_REGEX.findAll(html)
            .mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.decodeBase64() }
            .firstOrNull()
    }

    private suspend fun scanVideos(
        html: String,
        base: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val raw = buildList {
            DIRECT_VIDEO_URL_REGEX.findAll(html).forEach { add(it.value) }
            SOURCE_TAG_REGEX.findAll(html).forEach { add(it.groupValues[1]) }
            FILE_KEY_REGEX.findAll(html).forEach { add(it.groupValues[1]) }
            BASE64_REGEX.findAll(html).forEach { m ->
                m.groupValues[1].ifEmpty { m.groupValues[2] }.decodeBase64()?.let { add(it) }
            }
        }
        val urls = raw.map { it.resolveAgainst(base) }.filter { it.isDirectVideo() }.distinct()
        var ok = false
        for (u in urls) if (emitDirect(u, base, emitted, callback)) ok = true
        return ok
    }

    private suspend fun emitDirect(
        videoUrl: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!emitted.add(videoUrl)) return true
        log("vídeo", "emitindo $videoUrl (referer=$referer)")
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (".m3u8" in videoUrl.lowercase()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = getQualityFromName(QUALITY_REGEX.find(videoUrl)?.value)
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // ---------------------------------------------------------------- extensões

    private fun Document.metaContent(key: String): String? =
        selectFirst("meta[property=\"$key\"], meta[name=\"$key\"]")?.attr("content")
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.imageUrl(): String? =
        listOf("data-echo", "data-src", "data-lazy-src", "data-original", "src")
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun Element.playerUrl(): String? =
        listOf("data-src", "data-url", "data-player", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "about:blank" && !it.startsWith("javascript:") }

    private fun String.rehostIfOurs(base: String): String = runCatching {
        val u = URI(this)
        val host = u.host ?: return this
        if (!host.contains(domainKey) || host.normHost() == URI(base).host.normHost()) return this
        base.trimEnd('/') + (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(this)

    private fun String.pathKey(): String = runCatching {
        val u = URI(this)
        (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(this)

    private fun String.resolveAgainst(base: String): String =
        runCatching { URI(base).resolve(this.trim().replace(" ", "%20")).toString() }.getOrElse { fixUrl(this) }

    private fun String.unescapeJs(): String =
        replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private fun String.isDirectVideo(): Boolean = DIRECT_VIDEO_REGEX.containsMatchIn(this)

    private fun String.isJunkHost(): Boolean = runCatching {
        val h = URI(this).host?.normHost() ?: return@runCatching false
        JUNK_HOSTS.any { h == it || h.endsWith(".$it") }
    }.getOrDefault(false)

    private fun currentHost(): String = runCatching { URI(mainUrl).host.normHost() }.getOrDefault("")

    private fun String.normHost(): String = lowercase().removePrefix("www.")

    private fun String.isSameSite(): Boolean = runCatching {
        val h = URI(this).host.normHost()
        val main = currentHost()
        h == main || h.endsWith(".$main")
    }.getOrDefault(false)

    private fun String.decodeBase64(): String? =
        runCatching { String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("http") || it.startsWith("/") }

    private companion object {
        const val TAG = "RedeCanais"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val DATA_SEP = "\n"
        const val MAX_HOPS = 5
        const val MAX_PLAYERS = 15
        const val LOG_SNIPPET = 1500
        val BLOCK_CODES = setOf(403, 429, 503)

        val JUNK_HOSTS = listOf(
            "facebook.com", "twitter.com", "x.com", "whatsapp.com", "t.me", "telegram.me",
            "pinterest.com", "googletagmanager.com", "google-analytics.com",
            "googlesyndication.com", "doubleclick.net", "disqus.com", "addthis.com", "sharethis.com",
            "chatango.com",
        )

        val PAGE_REGEX = Regex("""-\d+-date\.html$""")
        val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

        /** S01E02, T1E2, 1x02. "1 e 2" (conjunção) NÃO casa. */
        val SE_REGEX = Regex("""(?i)\b(?:[st](\d{1,2})\s*e|(\d{1,2})\s*x)\s*(\d{1,3})\b""")

        /** Ep 3, Episódio 3, Cap. 3, Capítulo 3 */
        val EP_REGEX = Regex("""(?i)\b(?:ep(?:is[oó]dio)?|cap(?:[ií]tulo)?)\.?\s*(\d+)""")

        /** "Temporada 2" ou "2ª Temporada" */
        val SEASON_REGEX = Regex("""(?i)(?:temporada\s*(\d+)|(\d+)\s*[ªºa°]?\s*temporada)""")

        /** Texto que é lista de episódios, não sinopse. */
        val LIST_PLOT_REGEX = Regex("""(?i)assistir|(?:epis[oó]dio\s*\d+[\s\S]*){2}""")
        val GENERIC_EP_NAME_REGEX = Regex("""(?i)^\s*epis[oó]dio\s*\d+\s*$""")
        /** Prazo do TMDB; passou disso a página abre só com os dados do site (ou o que já chegou). */
        const val TMDB_TIMEOUT_MS = 8_000L
        val LIST_SUFFIX_REGEX = Regex("""(?i)\s*[-–]\s*lista\s+de\s+epis[oó]dios\s*$""")
        val EP_SUFFIX_REGEX = Regex("""(?i)\s*[-–|]?\s*\(?\b(?:dublado|legendado|dub|leg)\b\)?\s*$""")

        val META_URL_REGEX = Regex("""(?i)url\s*=\s*(.+)$""")
        val QUOTES_REGEX = Regex("""["']""")

        val DIRECT_VIDEO_REGEX = Regex("""(?i)\.(m3u8|mp4)(\?|#|$)""")
        val DIRECT_VIDEO_URL_REGEX = Regex("""https?://[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?""")
        val SOURCE_TAG_REGEX = Regex("""(?i)<source[^>]+src\s*=\s*["']([^"']+)["']""")
        val FILE_KEY_REGEX = Regex("""(?i)["']?\b(?:file|src|source|url|hls|video)["']?\s*[:=]\s*["']([^"']+)["']""")
        val QUALITY_REGEX = Regex("""(?i)\b(\d{3,4})p\b""")

        val IFRAME_IN_HTML_REGEX = Regex("""(?i)<iframe[^>]*?\ssrc\s*=\s*\\?["']([^"'\\]+)""")
        val PHP_URL_REGEX = Regex("""["']((?:https?:)?//[^"'\s]+?\.php\?[^"'\s]+|/[^"'\s]+?\.php\?[^"'\s]+)["']""")
        val STATIC_ASSET_REGEX = Regex("""(?i)\.(?:js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf)(?:\?|$)""")

        // ---- player do Rede Canais
        val RC_PLAYER_REGEX = Regex("""/player\d*/server\.php\?""")
        val VID_REGEX = Regex("""[?&]vid=([A-Za-z0-9_-]+)""")
        val SERVER_PARAM_REGEX = Regex("""[?&]server=([A-Za-z0-9_-]+)""")
        val RC_SW_PROXY_REGEX = Regex("""/__RC__/proxy\?src=([^&]+)""")
        val RC_QUALITY_REGEX = Regex("""(?i)(?<!\d)(2160|1440|1080|720|480|360)p""")
        const val RC_HEADER_NAME = "h31ffadrg3bb7"
        const val RC_HEADER_VALUE = "h31ffadrg3fj345a"
        /**
         * O vídeo é pedido pelo service worker / pelo elemento <video>, e o WebView do Android
         * não repassa essas requisições ao interceptador. Então o script lê a URL no próprio
         * player e "avisa" navegando um iframe para um host sentinela, que o interceptador vê.
         * Se em ~50 s não achar, avisa com ?err=<estado> para diagnóstico.
         */
        /** URL de origem dentro do proxy: ...proxy?...&url=<https://neosoro.gq/...> (vai até o fim). */
        val INNER_URL_REGEX = Regex("""[?&]url=(https?(?::|%3A).+)$""")
        const val RC_CAPTURE_HOST = "rc-capture.invalid"
        val RC_INTERCEPT_REGEX = Regex("""rc-capture\.invalid|null-null\.shop/[^?\s]*proxy[^?\s]*\?|/__RC__/proxy\?src=""")
        val CAPTURE_URL_REGEX = Regex("""[?&]u=([^&]+)""")
        val CAPTURE_ERR_REGEX = Regex("""[?&]err=([^&]+)""")
        val RC_CAPTURE_SCRIPT = """
            (function(){
              if (window.__rcCap) return 'dup'; window.__rcCap = 1;
              var n = 0, gate = 0, H = 'https://$RC_CAPTURE_HOST/';
              function ok(c){ return c && c.indexOf('blob:') !== 0 && !/servetraff|2mdn|doubleclick/.test(c)
                                && /null-null|__RC__|\.mp4|\.m3u8/.test(c); }
              function send(q){
                var u = H + '?' + q;
                try { var f = document.createElement('iframe'); f.style.display = 'none'; f.src = u;
                      (document.body || document.documentElement).appendChild(f); } catch(e) {}
                setTimeout(function(){ location.href = u; }, 1500);
              }
              var t = setInterval(function(){
                n++;
                try {
                  var s = null, vs = document.querySelectorAll('video');
                  for (var i = 0; i < vs.length && !s; i++) {
                    var v = vs[i], c = v.currentSrc || v.src || '';
                    if (ok(c)) s = c;
                    else if (!v.src) { var so = v.querySelector('source'); if (so && ok(so.src)) s = so.src; }
                    if (!s) { v.muted = true; try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e) {} }
                  }
                  if (!s && window.videojs && videojs.getPlayers) {
                    var ps = videojs.getPlayers();
                    for (var k in ps) { try { var c2 = ps[k] && ps[k].currentSrc(); if (ok(c2)) s = c2; } catch(e) {} }
                  }
                  if (!s) {
                    // Portão de play do site: <button id="submit" class="captcha_button">. Sem esse
                    // clique o player nunca pede o token (serverforms.api) nem cria o <video>.
                    var g = document.querySelector('#player button#submit, button.captcha_button');
                    if (g && (n === 2 || n % 30 === 0)) { gate++; g.click(); }  // reclica só a cada 15 s para não reiniciar o fluxo
                    else if (!g && n % 6 === 0) { var b = document.querySelector('.vjs-big-play-button'); if (b) b.click(); }
                  }
                  if (s) { clearInterval(t); send('u=' + encodeURIComponent(s)); return; }
                } catch(e) {}
                if (n >= 100) {
                  clearInterval(t);
                  var e1 = document.querySelector('.vjs-error-display, .vjs-modal-dialog-content');
                  var K = /serverforms|videojs\.4\.5\.2|null-null|__RC__|dns\.camera|probe/;
                  var api = [];
                  try {
                    performance.getEntriesByType('resource').forEach(function(r){
                      var m = r.name.match(K);
                      if (m && /[?&](css|template|p0|core)=/.test(r.name)) return;
                      if (m) api.push(m[0].replace('videojs.4.5.2', 'ticket') + ':' + (r.responseStatus || '?') + '/' + Math.round(r.duration) + 'ms');
                    });
                  } catch(e) { api.push('perf-erro'); }
                  var body = document.body;
                  var lb = document.querySelector('.rc-loader-label'), pc = document.querySelector('.rc-loader-percent');
                  var st = 'api=[' + (api.length ? api.join(',') : 'nenhuma') + ']'
                    + ' cliques=' + gate
                    + ' portao=' + !!document.querySelector('button.captcha_button')
                    + ' progresso=' + (pc ? pc.textContent : '') + ' ' + (lb ? lb.textContent : '')
                    + ' videos=' + document.querySelectorAll('video').length
                    + ' videojs=' + !!window.videojs
                    + ' sw=' + !!(navigator.serviceWorker && navigator.serviceWorker.controller)
                    + ' elems=' + (body ? body.getElementsByTagName('*').length : -1)
                    + ' html=' + document.documentElement.outerHTML.length
                    + ' tags=' + (body ? Array.prototype.slice.call(body.children, 0, 6).map(function(c){ return c.tagName + (c.id ? '#' + c.id : ''); }).join('|') : '')
                    + ' erro=' + (e1 ? (e1.innerText || '').slice(0, 60) : '')
                    + ' texto=' + ((body && body.innerText) || '').replace(/\s+/g, ' ').slice(0, 60);
                  send('err=' + encodeURIComponent(st));
                }
              }, 500);
              return 'ok';
            })();
        """.trimIndent()

        /** true = mostra o motivo da falha como um "link" na lista de fontes (sem precisar de adb). */
        const val DEBUG_LINKS = false

        val GENERIC_IMG_REGEX = Regex("""(?i)(logo|favicon|default|placeholder|no-?image|banner)""")
        val GENERIC_TEXT_REGEX = Regex("""(?i)rede\s*canais""")

        val JS_LOCATION_REGEX = Regex(
            """(?:window\.|document\.|top\.)?location(?:\.href)?\s*(?:=\s*|\.(?:replace|assign)\(\s*)(?:"([^"]+)"|'([^']+)')"""
        )
        val BASE64_REGEX = Regex(
            """atob\(\s*(?:"([A-Za-z0-9+/=]{16,})"|'([A-Za-z0-9+/=]{16,})')\s*\)"""
        )
    }
}

/**
 * DNS de reserva: usa o DNS atual do app e, se ele falhar (bloqueio da operadora,
 * DNS do Android que rejeita nomes como "-_kerberos-__tcp...null-null.shop"),
 * consulta DNS sobre HTTPS direto por IP (1.1.1.1 / 8.8.8.8), sem depender de nenhum DNS.
 *
 * É instalado no cliente global do app (app.baseClient), que também é usado pelo player;
 * só age quando o DNS normal falha, então não muda nada para os outros sites.
 */
object RcDns : Dns {
    private const val TAG = "RedeCanais"
    private val ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
    )
    private val A_RECORD = Regex("""\"type\"\s*:\s*1\s*,[^}]*?\"data\"\s*:\s*\"(\d{1,3}(?:\.\d{1,3}){3})\"""")

    @Volatile private var fallback: Dns = Dns.SYSTEM
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val lastResult = ConcurrentHashMap<String, String>()

    private val dohClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM) // os endpoints são IPs; não há nome para resolver
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** Coloca o RcDns no cliente global do app (uma vez; reinstala se o app trocar o cliente). */
    fun install() {
        val client = app.baseClient
        if (client.dns is RcDns) return
        fallback = client.dns
        val builder = client.newBuilder().dns(this)
        // TLS: hosts com "_" quebram o SNIHostName do Java; embrulha só esses sockets.
        runCatching {
            val tm = client.x509TrustManager ?: defaultTrustManager()
            builder.sslSocketFactory(SniSafeSocketFactory(client.sslSocketFactory), tm)
            sniPatched = true
        }.onFailure { Log.w(TAG, "[tls] não deu para instalar SniSafeSocketFactory: $it") }
        app.baseClient = builder.build()
        Log.d(TAG, "[dns] RcDns instalado (anterior: ${fallback.javaClass.simpleName}) sni=$sniPatched")
    }

    @Volatile var sniPatched = false
        private set

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Hosts do vídeo: o link é assinado para o IPv4 do cliente (ip=...&ipv6=), então só IPv4. */
    private val IPV4_ONLY = Regex("""(^|\.)(null-null\.shop|neosoro\.[a-z]+)$""", RegexOption.IGNORE_CASE)

    /** Nome falso -> host real (ex.: rcv.null-null.shop -> -_kerberos-...null-null.shop). */
    private val aliases = ConcurrentHashMap<String, String>()

    fun alias(fake: String, real: String) {
        aliases[fake.lowercase()] = real
    }

    override fun lookup(hostname: String): List<InetAddress> {
        aliases[hostname.lowercase()]?.let { real ->
            val ips = lookup(real).filterIsInstance<Inet4Address>()
                .map { InetAddress.getByAddress(hostname, it.address) }
            if (ips.isEmpty()) throw UnknownHostException("$hostname -> $real: sem IPv4")
            lastResult[hostname] = "alias de " + (if (real.length > 20) "…" + real.takeLast(16) else real)
            return ips
        }
        cache[hostname]?.let { (until, ips) -> if (System.currentTimeMillis() < until) return ips }
        val v4only = IPV4_ONLY.containsMatchIn(hostname)

        var firstError: UnknownHostException? = null
        val sys = try {
            fallback.lookup(hostname)
        } catch (e: UnknownHostException) {
            firstError = e; null
        }

        val sysV4 = sys?.filterIsInstance<Inet4Address>().orEmpty()
        if (sysV4.isNotEmpty()) {
            lastResult[hostname] = "sistema v4"
            // IPv4 primeiro sempre; IPv6 só como último recurso e nunca nos hosts do vídeo
            return if (v4only) sysV4 else sysV4 + (sys.orEmpty() - sysV4.toSet())
        }

        // Sistema falhou ou só devolveu IPv6: pede registro A via DNS sobre HTTPS
        val doh = dohLookupV4(hostname)
        if (doh.isNotEmpty()) return doh

        if (!sys.isNullOrEmpty() && !v4only) {
            lastResult[hostname] = "sistema só v6"
            return sys
        }
        lastResult[hostname] = if (sys.isNullOrEmpty()) "falhou" else "só v6 (descartado)"
        throw firstError ?: UnknownHostException("$hostname: sem endereço IPv4")
    }

    private fun dohLookupV4(hostname: String): List<InetAddress> {
        for (endpoint in ENDPOINTS) {
            val ips = try {
                val req = Request.Builder()
                    .url(endpoint + java.net.URLEncoder.encode(hostname, "UTF-8"))
                    .header("Accept", "application/dns-json")
                    .build()
                val body = dohClient.newCall(req).execute().use { it.body?.string() }.orEmpty()
                A_RECORD.findAll(body).map { m ->
                    val b = m.groupValues[1].split('.').map { it.toInt().toByte() }.toByteArray()
                    InetAddress.getByAddress(hostname, b)
                }.toList()
            } catch (e: Exception) {
                Log.w(TAG, "[dns] $endpoint falhou: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (ips.isNotEmpty()) {
                val via = endpoint.substringAfter("//").substringBefore('/')
                lastResult[hostname] = "doh v4 $via"
                Log.d(TAG, "[dns] $hostname -> ${ips.map { it.hostAddress }} via $via")
                cache[hostname] = (System.currentTimeMillis() + 5 * 60_000L) to ips
                return ips
            }
        }
        return emptyList()
    }

    /** Resumo para o [DEBUG]: como cada host foi resolvido. */
    fun report(): String {
        val installed = app.baseClient.dns is RcDns
        val hosts = lastResult.entries.filter { "null-null" in it.key || "neosoro" in it.key || it.key.startsWith("rcv.") }
            .joinToString(", ") { (h, r) -> (if (h.length > 24) "…" + h.takeLast(20) else h) + "=" + r }
        return "instalado=$installed sni=$sniPatched ${hosts.ifEmpty { "(nenhuma consulta)" }}"
    }
}

/**
 * O OkHttp no Android chama SSLSocket.getSSLParameters(); o Conscrypt monta ali um
 * SNIHostName(host), que lança IllegalArgumentException para hosts com "_" ou hífen
 * no início do rótulo (ex.: "-_kerberos-__tcp-...null-null.shop"). O Chrome não tem essa
 * restrição. Para esses hosts o socket é embrulhado: o SNI continua sendo enviado pelo
 * Conscrypt (ele recebe o host em createSocket) e a verificação do certificado continua.
 */
class SniSafeSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun wrap(socket: Socket, host: String?): Socket {
        if (socket !is SSLSocket || host == null) return socket
        val sniOk = runCatching { SNIHostName(host) }.isSuccess
        return if (sniOk) socket else SniSafeSSLSocket(socket)
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        wrap(delegate.createSocket(s, host, port, autoClose), host)
    override fun createSocket(host: String, port: Int): Socket = wrap(delegate.createSocket(host, port), host)
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(host, port, localHost, localPort), host)
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)
    override fun createSocket(): Socket = delegate.createSocket()
}

/** SSLSocket que repassa tudo ao socket real, exceto get/setSSLParameters, que ficam tolerantes. */
class SniSafeSSLSocket(private val d: SSLSocket) : SSLSocket() {
    override fun getSSLParameters(): SSLParameters = try {
        d.sslParameters
    } catch (e: RuntimeException) {
        SSLParameters(d.enabledCipherSuites, d.enabledProtocols)
    }

    override fun setSSLParameters(params: SSLParameters) {
        try {
            d.sslParameters = params
        } catch (e: RuntimeException) {
            params.cipherSuites?.let { d.enabledCipherSuites = it }
            params.protocols?.let { d.enabledProtocols = it }
        }
    }

    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = d.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>) { d.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = d.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = d.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>) { d.enabledProtocols = protocols }
    override fun getSession(): SSLSession = d.session
    override fun getHandshakeSession(): SSLSession? = d.handshakeSession
    override fun addHandshakeCompletedListener(l: HandshakeCompletedListener) = d.addHandshakeCompletedListener(l)
    override fun removeHandshakeCompletedListener(l: HandshakeCompletedListener) = d.removeHandshakeCompletedListener(l)
    override fun startHandshake() = d.startHandshake()
    override fun setUseClientMode(mode: Boolean) { d.useClientMode = mode }
    override fun getUseClientMode(): Boolean = d.useClientMode
    override fun setNeedClientAuth(need: Boolean) { d.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = d.needClientAuth
    override fun setWantClientAuth(want: Boolean) { d.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = d.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { d.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = d.enableSessionCreation

    override fun connect(endpoint: SocketAddress?) = d.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = d.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = d.bind(bindpoint)
    override fun getInetAddress(): java.net.InetAddress? = d.inetAddress
    override fun getLocalAddress(): java.net.InetAddress? = d.localAddress
    override fun getPort(): Int = d.port
    override fun getLocalPort(): Int = d.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = d.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = d.localSocketAddress
    override fun setTcpNoDelay(on: Boolean) { d.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = d.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = d.setSoLinger(on, linger)
    override fun getSoLinger(): Int = d.soLinger
    override fun setSoTimeout(timeout: Int) { d.soTimeout = timeout }
    override fun getSoTimeout(): Int = d.soTimeout
    override fun setSendBufferSize(size: Int) { d.sendBufferSize = size }
    override fun getSendBufferSize(): Int = d.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { d.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = d.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { d.keepAlive = on }
    override fun getKeepAlive(): Boolean = d.keepAlive
    override fun setTrafficClass(tc: Int) { d.trafficClass = tc }
    override fun getTrafficClass(): Int = d.trafficClass
    override fun setReuseAddress(on: Boolean) { d.reuseAddress = on }
    override fun getReuseAddress(): Boolean = d.reuseAddress
    override fun setOOBInline(on: Boolean) { d.oobInline = on }
    override fun getOOBInline(): Boolean = d.oobInline
    override fun sendUrgentData(data: Int) = d.sendUrgentData(data)
    override fun getInputStream(): java.io.InputStream = d.inputStream
    override fun getOutputStream(): java.io.OutputStream = d.outputStream
    override fun close() = d.close()
    override fun shutdownInput() = d.shutdownInput()
    override fun shutdownOutput() = d.shutdownOutput()
    override fun isConnected(): Boolean = d.isConnected
    override fun isBound(): Boolean = d.isBound
    override fun isClosed(): Boolean = d.isClosed
    override fun isInputShutdown(): Boolean = d.isInputShutdown
    override fun isOutputShutdown(): Boolean = d.isOutputShutdown
    override fun getChannel(): java.nio.channels.SocketChannel? = d.channel
    override fun toString(): String = "SniSafeSSLSocket($d)"
}

/**
 * Proxy HTTP local (127.0.0.1) para o player. O player recebe http://127.0.0.1:<porta>/v/<id>.mp4
 * e cada pedido dele (com Range) é refeito pelo cliente de rede do app (app.baseClient), onde
 * estão o DNS próprio, o IPv4 forçado e o contorno do nome com "_" no TLS.
 */
object RcLocalProxy {
    private const val TAG = "RedeCanais"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private class Target(val url: String, val headers: Map<String, String>, val created: Long)

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({ acceptLoop(ss) }, "rc-local-proxy").apply { isDaemon = true }.start()
        Log.d(TAG, "[local] proxy em 127.0.0.1:${ss.localPort}")
        return ss.localPort
    }

    fun register(url: String, headers: Map<String, String>): String {
        val port = start()
        val now = System.currentTimeMillis()
        targets.entries.removeAll { now - it.value.created > MAX_AGE_MS }
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        targets[id] = Target(url, headers, now)
        return "http://127.0.0.1:$port/v/$id.mp4"
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try { ss.accept() } catch (e: Exception) { break }
            Thread({ handle(client) }, "rc-local-conn").apply { isDaemon = true }.start()
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 60_000
                val input = java.io.BufferedInputStream(c.getInputStream())
                val out = java.io.BufferedOutputStream(c.getOutputStream(), 64 * 1024)
                val requestLine = readLine(input) ?: return
                val method = requestLine.substringBefore(' ')
                val path = requestLine.substringAfter(' ').substringBefore(' ')
                val reqHeaders = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) reqHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                val id = path.substringAfter("/v/").substringBefore('.').substringBefore('?')
                val t = targets[id]
                if (t == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }

                val rb = Request.Builder().url(t.url)
                t.headers.forEach { (k, v) -> rb.header(k, v) }
                reqHeaders["range"]?.let { rb.header("Range", it) }
                if (method == "HEAD") rb.head()

                val resp = try {
                    app.baseClient.newCall(rb.build()).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "[local] upstream falhou: ${e.javaClass.simpleName}: ${e.message}")
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush(); return
                }

                resp.use { r ->
                    val head = StringBuilder("HTTP/1.1 ${r.code} ${r.message.ifBlank { "OK" }}\r\n")
                    for (h in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                        r.header(h)?.let { head.append(h).append(": ").append(it).append("\r\n") }
                    }
                    if (r.header("Accept-Ranges") == null) head.append("Accept-Ranges: bytes\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray())
                    if (method != "HEAD") r.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // o player fecha a conexão ao buscar outro trecho (seek): esperado
            Log.d(TAG, "[local] conexão encerrada: ${e.javaClass.simpleName}")
        }
    }
}

// =====================================================================================
// TMDB
// =====================================================================================

/**
 * COLE AQUI SUA CHAVE DO TMDB (grátis): themoviedb.org > Configurações > API.
 * Serve a "Chave da API (v3)" ou o "Token de leitura da API (v4)". Vazio = sem TMDB.
 */
const val TMDB_API_KEY = "490e3556418b90551b88c10d31e536fe"

data class TmdbEpisode(
    val name: String?,
    val overview: String?,
    val still: String?,
    val airDate: String?,
    val runtime: Int?,
)

data class TmdbMeta(
    val id: Int,
    val title: String,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val year: Int?,
    val runtime: Int?,
    val vote: Double?,
    val genres: List<String>,
    /** nome, personagem, foto */
    val cast: List<Triple<String, String?, String?>>,
    val trailers: List<String>,
    val imdbId: String?,
    val episodes: Map<Pair<Int, Int>, TmdbEpisode>,
)

object Tmdb {
    private const val TAG = "RedeCanais"
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val LANG = "pt-BR"

    private val cache = ConcurrentHashMap<String, TmdbMeta>()

    /** Partes do título do site que atrapalham a busca: (Dublado), ano, qualidade etc. */
    private val NOISE_PARENS = Regex("""(?i)\((?:dublado|legendado|nacional|dual[^)]*|dub|leg|4k|hd)\)""")
    private val NOISE_PART = Regex("""(?i)^(?:\d{4}|\d{3,4}p|4k|hd|dublado|legendado|nacional|lista de epis[oó]dios|\d+[ªºa]?\s*temporada.*)$""")

    fun cleanTitle(siteTitle: String): String =
        siteTitle.replace(NOISE_PARENS, " ")
            .split(Regex("""\s+[-–|]\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !NOISE_PART.matches(it) }
            .joinToString(" - ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]"""), "")

    private fun img(path: String?, size: String): String? =
        path?.takeIf { it.startsWith("/") }?.let { IMG + size + it }

    /**
     * Prazo da consulta (sem kotlinx.coroutines): passado o prazo, as próximas requisições
     * nem saem, e cada uma tem timeout limitado ao tempo que resta (máx. 6 s).
     */
    private suspend fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        until: Long = Long.MAX_VALUE,
    ): JSONObject? {
        val key = TMDB_API_KEY.trim()
        if (key.isEmpty()) return null
        val remainingMs = until - System.currentTimeMillis()
        if (remainingMs <= 300) {
            Log.w(TAG, "[tmdb] prazo esgotado, pulando $path")
            return null
        }
        val timeoutSec = (remainingMs / 1000).coerceIn(1L, 6L)
        val bearer = key.length > 40   // token v4 é um JWT longo; chave v3 tem 32 caracteres
        val all = params + ("language" to LANG) + (if (bearer) emptyMap() else mapOf("api_key" to key))
        val url = API + path + "?" + all.entries.joinToString("&") {
            it.key + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val headers = mutableMapOf("Accept" to "application/json")
        if (bearer) headers["Authorization"] = "Bearer $key"
        val res = app.get(url, headers = headers, timeout = timeoutSec)
        if (!res.isSuccessful) {
            Log.w(TAG, "[tmdb] HTTP ${res.code} em $path")
            return null
        }
        return JSONObject(res.text)
    }

    suspend fun lookup(
        siteTitle: String,
        siteYear: Int?,
        isSeries: Boolean,
        seasons: List<Int>,
        timeoutMs: Long = 8_000L,
    ): TmdbMeta? {
        if (TMDB_API_KEY.isBlank()) return null
        val until = System.currentTimeMillis() + timeoutMs
        val query = cleanTitle(siteTitle).ifEmpty { return null }
        val cacheKey = "${if (isSeries) "tv" else "movie"}|${normalize(query)}|$siteYear"
        cache[cacheKey]?.let { return it }

        val id = search(query, siteYear, isSeries, until) ?: return null
        val meta = if (isSeries) loadTv(id, seasons, until) else loadMovie(id, until)
        meta?.let { cache[cacheKey] = it }
        return meta
    }

    /** Busca e escolhe o resultado mais parecido (nome igual pesa mais; ano confirma). */
    private suspend fun search(query: String, year: Int?, isSeries: Boolean, until: Long): Int? {
        val path = if (isSeries) "/search/tv" else "/search/movie"
        val yearParam = if (isSeries) "first_air_date_year" else "primary_release_year"
        var results = year?.let { get(path, mapOf("query" to query, yearParam to it.toString()), until)?.optJSONArray("results") }
        if (results == null || results.length() == 0) results = get(path, mapOf("query" to query), until)?.optJSONArray("results")
        if (results == null || results.length() == 0) return null

        val q = normalize(query)
        var best: Pair<Int, Double>? = null
        for (i in 0 until minOf(results.length(), 10)) {
            val r = results.optJSONObject(i) ?: continue
            val names = listOfNotNull(
                r.str(if (isSeries) "name" else "title"),
                r.str(if (isSeries) "original_name" else "original_title"),
            ).map { normalize(it) }.filter { it.isNotEmpty() }
            var score = when {
                names.any { it == q } -> 10.0
                names.any { (it.length > 3 && q.contains(it)) || (q.length > 3 && it.contains(q)) } -> 4.0
                else -> continue
            }
            val ry = r.str(if (isSeries) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
            if (year != null && ry != null) score += when (kotlin.math.abs(ry - year)) {
                0 -> 5.0; 1 -> 2.0; else -> -3.0
            }
            score -= i * 0.1
            if (best == null || score > best.second) best = r.optInt("id") to score
        }
        return best?.first?.takeIf { it > 0 }
    }

    private fun parseCommon(d: JSONObject, isSeries: Boolean): TmdbMeta {
        val genres = d.optJSONArray("genres").objects().mapNotNull { it.str("name") }
        val cast = d.optJSONObject("credits")?.optJSONArray("cast").objects().take(20).map {
            Triple(it.str("name") ?: "", it.str("character"), img(it.str("profile_path"), "w185"))
        }.filter { it.first.isNotBlank() }
        val videos = d.optJSONObject("videos")?.optJSONArray("results").objects()
            .filter { it.str("site").equals("YouTube", true) && it.str("key") != null }
            .sortedBy {
                val typeRank = when (it.str("type")) { "Trailer" -> 0; "Teaser" -> 1; else -> 2 }
                val langRank = if (it.str("iso_639_1") == "pt") 0 else 1
                typeRank * 2 + langRank
            }
            .take(2)
            .map { "https://www.youtube.com/watch?v=" + it.str("key") }
        val date = d.str(if (isSeries) "first_air_date" else "release_date") ?: ""
        val runtime = if (isSeries) d.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
                      else d.optInt("runtime").takeIf { it > 0 }
        val imdb = d.str("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: d.optJSONObject("external_ids")?.str("imdb_id")?.takeIf { it.startsWith("tt") }
        return TmdbMeta(
            id = d.optInt("id"),
            title = d.str(if (isSeries) "name" else "title") ?: "",
            overview = d.str("overview"),
            poster = img(d.str("poster_path"), "w500"),
            backdrop = img(d.str("backdrop_path"), "w1280"),
            year = date.take(4).toIntOrNull(),
            runtime = runtime,
            vote = d.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            genres = genres,
            cast = cast,
            trailers = videos,
            imdbId = imdb,
            episodes = emptyMap(),
        )
    }

    private val APPEND = mapOf(
        "append_to_response" to "credits,videos,external_ids",
        "include_video_language" to "pt,en,null",
    )

    private suspend fun loadMovie(id: Int, until: Long): TmdbMeta? =
        get("/movie/$id", APPEND, until)?.let { parseCommon(it, isSeries = false) }

    private suspend fun loadTv(id: Int, seasons: List<Int>, until: Long): TmdbMeta? {
        val details = get("/tv/$id", APPEND, until) ?: return null
        val base = parseCommon(details, isSeries = true)
        val eps = ConcurrentHashMap<Pair<Int, Int>, TmdbEpisode>()
        seasons.amap { n ->
            val season = runCatching { get("/tv/$id/season/$n", emptyMap(), until) }.getOrNull() ?: return@amap
            season.optJSONArray("episodes").objects().forEach { e ->
                val num = e.optInt("episode_number").takeIf { it > 0 } ?: return@forEach
                eps[n to num] = TmdbEpisode(
                    name = e.str("name"),
                    overview = e.str("overview"),
                    still = img(e.str("still_path"), "w300"),
                    airDate = e.str("air_date")?.takeIf { it.length >= 10 },
                    runtime = e.optInt("runtime").takeIf { it > 0 },
                )
            }
        }
        return base.copy(episodes = eps)
    }

    /** optString do Android devolve "null" para campo nulo: aqui vira null de verdade. */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}