package com.ninja2.providers

import android.util.Base64
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provedor consolidado para o site ReDECanais / DavidCanais.
 * Otimizado para isolar lógicas estáticas via Jsoup e ignorar scripts de anúncios/VMs.
 */
class HtmlSiteProvider : MainAPI() {

    override var name = "Rede Canais V7"
    override var mainUrl = "https://redecanais.by"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val domainKey = "redecanais"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
    )

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

    // [FIX 1] Paginação real: troca o número da página no path.
    private fun pageUrl(base: String, path: String, page: Int): String =
        base + path.replace(PAGE_REGEX, "-$page-date.html")

    private fun searchUrl(base: String, query: String): String =
        "$base/search.php?keywords=" + URLEncoder.encode(query, "UTF-8")

    private object Sel {
        const val LIST_ITEM = "ul.pm-ul-carousel-videos li, ul.pm-ul-browse-videos li"
        const val ITEM_LINK = "a[href]"
        const val ITEM_TITLE = "h3 a, .caption h3 a, .title"
        const val ITEM_POSTER = "img"

        // A CONFIRMAR no HTML real: inclui seletores PHP Melody além dos genéricos.
        const val PAGE_TITLE = "h1"
        const val PAGE_POSTER = "div.poster img, .pm-video-watch-featured img, .pm-video-thumb img"
        const val PAGE_PLOT = "div.sinopse, .pm-video-description, .description p, #content-main .description"
        const val PAGE_YEAR = "span.ano, .year"
        const val PAGE_TAGS = ".entry-tags a, a[rel=tag], .pm-video-tags a"
        const val BREADCRUMB = ".breadcrumb a, ul.breadcrumb li a"

        // Lista explícita de episódios (temas tipo WordPress).
        const val EPISODE_LIST_LINK = "ul.episodios li a, .episodes a"
        // Links na descrição (padrão Rede Canais/PHP Melody); filtrados por texto "Episódio N".
        const val EPISODE_DESC_LINK = ".pm-video-description a[href], .pm-category-description a[href], #content-main .description a[href]"
        // Cabeçalhos candidatos a "Temporada N", avaliados na ordem do documento.
        const val SEASON_HEADER = "strong, b, h2, h3, h4, span"

        const val PLAYER_CONTAINER = "#player, .player, .pm-video-watch-main, .embed-responsive, #video-player"
        // [FIX 3] Sem [data-src] genérico (pegava <img> de lazy-load).
        const val PLAYER = "iframe[src], iframe[data-src], [data-player], [data-url]"
    }

    // [FIX 10] Tipo inferido pela categoria quando a URL do item não traz pista.
    private fun typeFromUrl(href: String, hint: TvType? = null): TvType = when {
        "/series/" in href || "/browse-series-" in href || "lista-de-episodios" in href -> TvType.TvSeries
        "/animes/" in href || "/browse-animes-" in href -> TvType.Anime
        "/desenhos/" in href || "/browse-desenhos-" in href -> TvType.Anime
        hint != null -> hint
        else -> TvType.Movie
    }

    private fun typeFromCategory(path: String): TvType? = when {
        "/browse-series-" in path || "/browse-novelas-" in path || "/browse-doramas-" in path -> TvType.TvSeries
        "/browse-animes-" in path || "/browse-desenhos-" in path -> TvType.Anime
        "/browse-filmes-" in path -> TvType.Movie
        else -> null
    }

    private suspend fun base(): String {
        mainUrl = DomainManager.current(domainKey, mainUrl)
        return mainUrl
    }

    // ---------------------------------------------------------------- HTTP

    /** [FIX 5] Fallback de domínio que funciona com HTTP != 2xx, respeita cancelamento e reescreve URLs antigas. */
    private suspend fun getDoc(url: String, referer: String? = null): Document {
        val current = base()
        val target = url.rehostIfOurs(current)
        fetch(target, referer)?.let { return it }

        DomainManager.invalidate(domainKey)
        val newBase = base()
        if (newBase == current) throw ErrorLoadingException("Falha ao carregar $target")
        return fetch(target.rehostIfOurs(newBase), referer)
            ?: throw ErrorLoadingException("Falha ao carregar $target")
    }

    /** null = falha atribuível ao domínio (rede, 403, 5xx); 404 é erro de conteúdo e não troca domínio. */
    private suspend fun fetch(url: String, referer: String?): Document? = try {
        val res = app.get(url, headers = headers, referer = referer)
        when {
            res.isSuccessful -> res.document
            res.code == 404 -> throw ErrorLoadingException("Página não encontrada (404)")
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ErrorLoadingException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Troca o host de URLs do próprio site (inclusive domínios antigos salvos em favoritos) pelo atual. */
    private fun String.rehostIfOurs(base: String): String = runCatching {
        val u = URI(this)
        val host = u.host ?: return this
        if (!host.contains(domainKey) || host.normHost() == URI(base).host.normHost()) return this
        base.trimEnd('/') + (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(this)

    // ---------------------------------------------------------------- API

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(pageUrl(base(), request.data, page))
        val host = currentHost()
        val hint = typeFromCategory(request.data)
        val items = doc.select(Sel.LIST_ITEM).mapNotNull { it.toSearchResult(host, hint) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc(searchUrl(base(), query))
        val host = currentHost()
        return doc.select(Sel.LIST_ITEM).mapNotNull { it.toSearchResult(host, null) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val pageUrl = url.rehostIfOurs(mainUrl)

        val title = (doc.selectFirst(Sel.PAGE_TITLE)?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content"))
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Título não encontrado")

        val poster = fixUrlNull(
            doc.selectFirst(Sel.PAGE_POSTER)?.imageUrl()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        )
        val plot = (doc.selectFirst(Sel.PAGE_PLOT)?.text()
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content"))
            ?.trim()?.takeIf { it.isNotEmpty() }
        val year = (doc.selectFirst(Sel.PAGE_YEAR)?.text() ?: title)
            .let { YEAR_REGEX.find(it)?.value?.toIntOrNull() }
        val tags = doc.select(Sel.PAGE_TAGS).map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodes = parseEpisodes(doc)
        return if (episodes.isNotEmpty()) {
            // [FIX 11] Anime/Desenho detectado por breadcrumb/tags.
            val crumbs = (doc.select(Sel.BREADCRUMB).map { it.text() } + tags).joinToString(" ").lowercase()
            val type = if ("anime" in crumbs || "desenho" in crumbs) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
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
        val doc = getDoc(data)
        val pageUrl = data.rehostIfOurs(mainUrl)

        // Prioriza o container do player; cai para o documento inteiro se não existir.
        val scope: Element = doc.selectFirst(Sel.PLAYER_CONTAINER) ?: doc
        val candidates = scope.select(Sel.PLAYER).ifEmpty { doc.select(Sel.PLAYER) }

        val playerUrls = candidates
            .filter { it.tagName() != "img" }
            .mapNotNull { it.playerUrl() }
            .map { it.resolveAgainst(pageUrl) }
            .distinct()

        val found = AtomicBoolean(false)
        // [Desempenho] Resolução em paralelo.
        playerUrls.amap { url ->
            val finalUrl = try {
                resolveRedirects(url, referer = pageUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                url
            }

            // [FIX 4] Link direto de vídeo não tem extractor: entrega direto.
            if (DIRECT_VIDEO_REGEX.containsMatchIn(finalUrl)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (".m3u8" in finalUrl) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found.set(true)
            } else if (loadExtractor(finalUrl, pageUrl, subtitleCallback, callback)) {
                found.set(true)
            }
        }
        return found.get()
    }

    // ---------------------------------------------------------------- Parsing

    private fun Element.toSearchResult(host: String, hint: TvType?): SearchResponse? {
        val link = selectFirst(Sel.ITEM_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = (selectFirst(Sel.ITEM_TITLE)?.text() ?: link.attr("title")).trim()
        if (title.isEmpty() || DomainManager.isBlocked(title)) return null
        // [FIX 8] Comparação de host normalizada (www / subdomínio).
        val itemHost = runCatching { URI(href).host.normHost() }.getOrNull() ?: return null
        if (host.isNotEmpty() && itemHost != host && !itemHost.endsWith(".$host")) return null

        val poster = selectFirst(Sel.ITEM_POSTER)?.imageUrl()
        return newMovieSearchResponse(title, href, typeFromUrl(href, hint)) { posterUrl = fixUrlNull(poster) }
    }

    /**
     * Percorre cabeçalhos "Temporada N" e links de episódio na ordem do documento,
     * atribuindo a temporada vigente a cada episódio.
     */
    private fun parseEpisodes(doc: Document): List<Episode> {
        val listLinks = doc.select(Sel.EPISODE_LIST_LINK)
        val useList = listLinks.isNotEmpty()
        val linkSelector = if (useList) Sel.EPISODE_LIST_LINK else Sel.EPISODE_DESC_LINK

        var currentSeason = 1
        val counters = mutableMapOf<Int, Int>()
        val seen = HashSet<String>()
        val result = mutableListOf<Episode>()

        for (el in doc.select("${Sel.SEASON_HEADER}, $linkSelector")) {
            if (el.`is`(linkSelector)) {
                val text = el.text().trim()
                // Links da descrição só contam se parecerem episódio.
                if (!useList && !EP_REGEX.containsMatchIn(text) && !SE_REGEX.containsMatchIn(text)) continue
                val href = fixUrl(el.attr("href"))
                if (!seen.add(href)) continue

                val parsed = parseSeasonEpisode(text)
                val season = parsed?.first ?: currentSeason
                val number = parsed?.second ?: ((counters[season] ?: 0) + 1)
                counters[season] = maxOf(counters[season] ?: 0, number)

                result += newEpisode(href) {
                    name = text
                    this.season = season
                    episode = number
                }
            } else if (el.children().size <= 1) {
                SEASON_REGEX.find(el.ownText())?.groupValues?.get(1)?.toIntOrNull()?.let { currentSeason = it }
            }
        }
        return result
    }

    private fun Element.imageUrl(): String? =
        listOf("data-echo", "data-src", "data-lazy-src", "data-original", "src")
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun Element.playerUrl(): String? =
        listOf("data-src", "data-url", "data-player", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "about:blank" && !it.startsWith("javascript:") }

    /** Só aceita "SxE" explícito; "Episódio N" mantém a temporada do cabeçalho. */
    private fun parseSeasonEpisode(text: String): Pair<Int?, Int>? {
        SE_REGEX.find(text)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return EP_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { null to it }
    }

    // ---------------------------------------------------------------- Redirects

    private suspend fun resolveRedirects(url: String, referer: String, depth: Int = 0): String {
        if (depth >= MAX_REDIRECT_DEPTH || !url.isSameSite()) return url

        val res = app.get(url, headers = headers, referer = referer)
        val current = res.url
        if (!current.isSameSite()) return current

        val html = res.text
        val doc = res.document

        // [FIX 7] "URL=" em qualquer caixa.
        val metaRefresh = doc.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")
            ?.let { META_URL_REGEX.find(it)?.groupValues?.get(1) }
            ?.replace(QUOTES_REGEX, "")?.trim()
            ?.takeIf { it.isNotEmpty() }

        // [FIX 9] Iframe do container do player primeiro.
        val iframe = (doc.selectFirst(Sel.PLAYER_CONTAINER)?.selectFirst("iframe") ?: doc.selectFirst("iframe"))
            ?.playerUrl()

        val next = iframe
            ?: metaRefresh
            ?: JS_LOCATION_REGEX.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }
            ?: BASE64_REGEX.find(html)?.groupValues?.let { it[1].ifEmpty { it[2] } }?.decodeBase64()
            ?: return current

        if (next.isBlank()) return current
        // [FIX 6] Relativo resolvido contra a página atual, não contra a raiz.
        return resolveRedirects(next.resolveAgainst(current), referer = current, depth = depth + 1)
    }

    private fun String.resolveAgainst(base: String): String =
        runCatching { URI(base).resolve(this.replace(" ", "%20")).toString() }.getOrElse { fixUrl(this) }

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
        const val MAX_REDIRECT_DEPTH = 4
        val PAGE_REGEX = Regex("-\\d+-date\\.html$")
        val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
        // Mais restrito: exige S/T opcional e separador típico (1x05, S01E05, T1E5).
        val SE_REGEX = Regex("(?i)\\b[st]?(\\d{1,2})\\s*[xe]\\s*(\\d{1,3})\\b")
        val EP_REGEX = Regex("(?i)\\bep(?:is[oó]dio)?\\.?\\s*(\\d+)")
        val SEASON_REGEX = Regex("(?i)temporada\\s*(\\d+)")
        val META_URL_REGEX = Regex("(?i)url\\s*=\\s*(.+)$")
        val DIRECT_VIDEO_REGEX = Regex("(?i)\\.(m3u8|mp4)(\\?|#|$)")

        val QUOTES_REGEX = Regex("[\\x22\\x27]")
        // [FIX 2] Alternância restrita às aspas; inclui location.replace/assign.
        val JS_LOCATION_REGEX = Regex(
            "(?:window\\.|document\\.)?location(?:\\.href)?\\s*(?:=\\s*|\\.(?:replace|assign)\\(\\s*)(?:\\x22([^\\x22]+)\\x22|\\x27([^\\x27]+)\\x27)"
        )
        val BASE64_REGEX = Regex(
            "atob\\(\\s*(?:\\x22([A-Za-z0-9+/=]{16,})\\x22|\\x27([A-Za-z0-9+/=]{16,})\\x27)\\s*\\)"
        )
    }
}
