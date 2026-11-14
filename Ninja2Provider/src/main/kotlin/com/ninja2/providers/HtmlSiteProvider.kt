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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Logs: adb logcat -s RedeCanais
 */
class HtmlSiteProvider : MainAPI() {

    override var name = "Rede Canais V16"
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

        val title = doc.selectFirst(Sel.PAGE_TITLE)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.metaContent("og:title")
            ?: throw ErrorLoadingException("Título não encontrado")

        // Poster: 1) o mesmo da listagem  2) og:image  3) imagem do bloco principal
        val posterCandidates = listOf(
            "cache" to posterCache[url.pathKey()],
            "og:image" to doc.metaContent("og:image"),
            "itemprop" to doc.selectFirst(Sel.PAGE_POSTER)?.let { it.attr("content").ifBlank { it.attr("href") } },
        )
        val poster = pick(posterCandidates, GENERIC_IMG_REGEX)

        // Descrição: [itemprop=description] tem o texto completo; og:description vem cortado com "..."
        val plotCandidates = listOf(
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

        val episodes = parseEpisodes(main)
        log("load", "episódios=${episodes.size}")

        return if (episodes.isNotEmpty()) {
            val crumbs = (doc.select(Sel.BREADCRUMB).map { it.text() } + tags).joinToString(" ").lowercase()
            val type = when {
                "dorama" in crumbs -> TvType.AsianDrama
                "desenho" in crumbs -> TvType.Cartoon
                "anime" in crumbs -> TvType.Anime
                else -> typeFromUrl(pageUrl, TvType.TvSeries)
            }
            newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                posterUrl = fixUrlNull(poster); this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = fixUrlNull(poster); this.plot = plot; this.year = year; this.tags = tags
            }
        }
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
                val text = el.text().trim()
                if (!useList && !EP_REGEX.containsMatchIn(text) && !SE_REGEX.containsMatchIn(text)) continue
                val href = fixUrl(el.attr("href")).rehostIfOurs(mainUrl)
                if (!seen.add(href)) continue

                val parsed = parseSeasonEpisode(text)
                val season = parsed?.first ?: currentSeason
                val number = parsed?.second ?: ((counters[season] ?: 0) + 1)
                counters[season] = maxOf(counters[season] ?: 0, number)

                val name = text.replace(EP_SUFFIX_REGEX, "").ifBlank { text }
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

        // O host do proxy ("-_kerberos-__tcp-...null-null.shop") começa com hífen e tem "_";
        // o DNS do Android pode recusar. Testa o original e variantes antes de entregar.
        val variants = rcVariants(videoUrl)
        val results = variants.amap { (label, u) -> Triple(label, u, preflight(u, "$site/", playHeaders)) }
        log("vídeo", "testes: ${results.map { it.first + "=" + it.third }}")

        val working = results.filter { it.third.startsWith("2") }
        val toEmit = working.ifEmpty { results.take(1) }   // nenhum passou: entrega o original mesmo assim
        for ((label, u, _) in toEmit) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = u,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$site/"
                    this.quality = quality
                    this.headers = playHeaders
                }
            )
        }
        if (DEBUG_LINKS) {
            debugLink("teste: " + results.joinToString(" | ") { "${it.first}=${it.third}" } +
                " | dns: " + RcDns.report(), callback)
        }
        return true
    }

    /**
     * Variantes do mesmo link assinado:
     *  proxy     -> URL original
     *  proxy-alt -> mesmo caminho, subdomínio simples (a Cloudflare roteia pelo domínio)
     *  proxy-raiz-> domínio sem subdomínio
     *  direto    -> URL de origem que está dentro do parâmetro url= (neosoro.gq)
     */
    private fun rcVariants(videoUrl: String): List<Pair<String, String>> {
        val out = mutableListOf("proxy" to videoUrl)
        runCatching {
            val u = URI(videoUrl)
            val host = u.host ?: u.authority
            val root = PROXY_ROOT_REGEX.find(host)?.value
            if (root != null && host != root) {
                val rest = videoUrl.substringAfter(host)
                out += "proxy-alt" to "https://v.$root$rest"
                out += "proxy-raiz" to "https://$root$rest"
            }
        }
        INNER_URL_REGEX.find(videoUrl)?.groupValues?.get(1)?.let { inner ->
            val decoded = if (inner.startsWith("http")) inner else URLDecoder.decode(inner, "UTF-8")
            if (decoded.startsWith("http")) out += "direto" to decoded
        }
        return out.distinctBy { it.second }
    }

    /** GET com Range de 2 bytes. Devolve o status HTTP ou o nome da exceção (ex.: UnknownHostException). */
    private suspend fun preflight(url: String, referer: String, headers: Map<String, String>): String = try {
        val res = app.get(url, headers = headers + ("Range" to "bytes=0-1"), referer = referer, timeout = 15L)
        res.code.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.javaClass.simpleName + (e.message?.let { ":" + it.take(50) } ?: "")
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
        /** Domínio registrável do proxy (ex.: null-null.shop) dentro de um host qualquer. */
        val PROXY_ROOT_REGEX = Regex("""[A-Za-z0-9-]+\.[A-Za-z]{2,}$""")
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
        const val DEBUG_LINKS = true

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
        app.baseClient = client.newBuilder().dns(this).build()
        Log.d(TAG, "[dns] RcDns instalado (anterior: ${fallback.javaClass.simpleName})")
    }

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { (until, ips) -> if (System.currentTimeMillis() < until) return ips }

        val firstError = try {
            return fallback.lookup(hostname).also { lastResult[hostname] = "sistema" }
        } catch (e: UnknownHostException) {
            e
        }

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
                lastResult[hostname] = "doh $via"
                Log.d(TAG, "[dns] $hostname -> ${ips.map { it.hostAddress }} via $via")
                cache[hostname] = (System.currentTimeMillis() + 5 * 60_000L) to ips
                return ips
            }
        }
        lastResult[hostname] = "falhou"
        throw firstError
    }

    /** Resumo para o [DEBUG]: como cada host foi resolvido. */
    fun report(): String {
        val installed = app.baseClient.dns is RcDns
        val hosts = lastResult.entries.filter { "null-null" in it.key || "neosoro" in it.key }
            .joinToString(", ") { (h, r) -> (if (h.length > 24) "…" + h.takeLast(20) else h) + "=" + r }
        return "instalado=$installed ${hosts.ifEmpty { "(nenhuma consulta)" }}"
    }
}