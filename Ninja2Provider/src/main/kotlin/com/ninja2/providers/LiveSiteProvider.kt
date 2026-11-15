package com.ninja2.providers

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * RedeCanaisTV (canais ao vivo). Estrutura conferida no HTML real (nov/2026):
 *  - Home: <section class="catalog-row"> com <h2>categoria</h2> e cartões
 *    <a class="channel-card" href="/assistir-<canal>-online-24-horas-ao-vivo_<hash>.html">
 *      <img class="channel-cover" src="/assets/imgs-videos/Canais/thumb-<canal>.jpg">
 *      <span class="card-label">Nome</span></a>
 *  - Canal: <h1>Nome</h1>, og:image (logo), og:description, linha "Canal 015 · ★RC | Categoria · Full HD",
 *    player <iframe name="Player" src="/player3/ch.php?categoria=live&canal=<canal>">.
 *  - Player: o mesmo do Rede Canais (filmes), resultado em HLS. A reprodução usa a mesma captura
 *    (HtmlSiteProvider.resolveRcPlayer): WebView, DNS próprio, proxy local com reescrita de playlist.
 * Logs: adb logcat -s RedeCanais
 */
class LiveSiteProvider : MainAPI() {

    override var name = "Rede Canais TV"
    override var mainUrl = "https://redecanaistv.by"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val domainKey = "redecanaistv"

    /** A home traz todas as categorias de uma vez; vira várias linhas numa resposta só. */
    override val mainPage = mainPageOf("/" to "Canais")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "RC-Site-Requests",
    )

    /** Captura/reprodução compartilhada com o provider de filmes. */
    private val player by lazy { HtmlSiteProvider() }

    private object Sel {
        const val ROW = "section.catalog-row"
        const val ROW_TITLE = "h2"
        const val CARD = "a.channel-card[href]"
        const val CARD_LABEL = ".card-label"
        const val CARD_LOGO = "img.channel-cover"

        const val PAGE_TITLE = "h1"
        const val PAGE_INFO = ".watch-channel-title p"
        const val PAGE_DESC = "p.watch-channel-description"
        const val PLAYER = "iframe[name=Player], iframe[src*=ch.php], iframe[src*=player3]"
    }

    private data class Channel(val title: String, val href: String, val logo: String?, val category: String)

    @Volatile private var homeCache: Pair<Long, List<Channel>>? = null
    private val logoCache = ConcurrentHashMap<String, String>()

    private fun log(msg: String) { Log.d(TAG, "[tv] $msg") }

    private suspend fun base(): String {
        mainUrl = DomainManager.current(domainKey, mainUrl).trimEnd('/')
        return mainUrl
    }

    private fun String.pathOnly(): String = runCatching {
        val u = URI(this)
        (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(this)

    /** Reescreve links de domínios antigos da TV para o domínio atual. */
    private fun String.rehost(base: String): String {
        val host = runCatching { URI(this).host }.getOrNull() ?: return this
        return if (host.contains(domainKey) && !base.contains(host)) base.trimEnd('/') + pathOnly() else this
    }

    /** null = falha de rede (justifica trocar de domínio); 404 e bloqueios lançam erro. */
    private suspend fun fetch(url: String, referer: String?): Document? {
        val res = try {
            app.get(url, headers = headers, referer = referer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("falha de rede em $url: ${e.javaClass.simpleName}")
            return null
        }
        if (res.isSuccessful) return res.document
        if (res.code == 404) throw ErrorLoadingException("Página não encontrada (404)")
        if (res.code in setOf(403, 429, 503)) throw ErrorLoadingException("Acesso bloqueado (HTTP ${res.code})")
        return null
    }

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        val current = base()
        fetch(url.rehost(current), referer)?.let { return it }
        DomainManager.invalidate(domainKey)
        val newBase = base()
        if (newBase == current) throw ErrorLoadingException("Falha ao carregar $url")
        return fetch(url.rehost(newBase), referer) ?: throw ErrorLoadingException("Falha ao carregar $url")
    }

    /** Canais da home (cache de 10 min, usado também pela busca). */
    private suspend fun channels(): List<Channel> {
        homeCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60_000L && list.isNotEmpty()) return list }
        val doc = getDoc(base() + "/")
        val list = doc.select(Sel.ROW).flatMap { row ->
            val category = row.selectFirst(Sel.ROW_TITLE)?.text()?.let(::cleanCategory).orEmpty()
            row.select(Sel.CARD).mapNotNull { it.toChannel(category) }
        }
        log("home: ${list.size} cartões, ${list.distinctBy { it.href }.size} canais")
        if (list.isNotEmpty()) homeCache = System.currentTimeMillis() to list
        return list
    }

    private fun cleanCategory(s: String) = s.replace(Regex("""^\s*★?\s*RC\s*\|\s*"""), "").trim()

    private fun Element.toChannel(category: String): Channel? {
        val href = fixUrl(attr("href")).rehost(mainUrl)
        val title = selectFirst(Sel.CARD_LABEL)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst(Sel.CARD_LOGO)?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (DomainManager.isBlocked(title)) return null
        val logo = fixUrlNull(selectFirst(Sel.CARD_LOGO)?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") })
        logo?.let { logoCache[href.pathOnly()] = it }
        return Channel(title, href, logo, category)
    }

    private fun Channel.toSearch(): SearchResponse =
        newLiveSearchResponse(title, href, TvType.Live) { posterUrl = logo }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val rows = channels().groupBy { it.category }
            .map { (category, list) -> HomePageList(category.ifEmpty { "Canais" }, list.map { it.toSearch() }, isHorizontalImages = true) }
        return newHomePageResponse(rows, hasNext = false)
    }

    /** A busca do site filtra na própria página: aqui filtra a lista de canais da home. */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = Tmdb.normalizeWords(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        val compactQ = q.replace(" ", "")
        return channels().distinctBy { it.href }
            .filter { c ->
                val n = Tmdb.normalizeWords(c.title)
                words.all { it in n } || (compactQ.length >= 3 && compactQ in n.replace(" ", ""))
            }
            .sortedBy { if (Tmdb.normalizeWords(it.title).startsWith(q)) 0 else 1 }
            .map { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val pageUrl = url.rehost(mainUrl)
        val title = doc.selectFirst(Sel.PAGE_TITLE)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:image:alt]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Nome do canal não encontrado")
        val logo = logoCache[pageUrl.pathOnly()]
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst(Sel.PAGE_DESC)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
        // "Canal 015 · ★RC | Canais Abertos · Full HD" -> ["Canais Abertos", "Full HD"]
        val tags = doc.selectFirst(Sel.PAGE_INFO)?.text()?.split('·')
            ?.map { cleanCategory(it) }
            ?.filter { it.isNotEmpty() && !it.startsWith("Canal ", ignoreCase = true) }
            .orEmpty()

        return newLiveStreamLoadResponse(title, pageUrl, pageUrl) {
            posterUrl = logo
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = getDoc(data)
        val pageUrl = data.rehost(mainUrl)
        val players = doc.select(Sel.PLAYER)
            .mapNotNull { f -> f.attr("src").ifBlank { f.attr("data-src") }.trim().takeIf { it.isNotEmpty() && it != "about:blank" } }
            .map { fixUrl(it) }
            .filter { "chatango" !in it }
            .distinct()
        log("canal $pageUrl players=$players")
        if (players.isEmpty()) throw ErrorLoadingException("Player do canal não encontrado")

        val emitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var ok = false
        for (p in players) {
            val r = try {
                player.resolveRcPlayer(p, pageUrl, subtitleCallback, callback, emitted, site = mainUrl, label = name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("player $p falhou: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (r) { ok = true; break }   // um player basta (são o mesmo canal)
        }
        return ok
    }

    private companion object {
        const val TAG = "RedeCanais"
    }
}