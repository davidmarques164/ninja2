package com.ninja2.providers

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolve o domínio atual de cada provedor.
 *
 * Ordem de preferência:
 *  1. Domínios listados no domains.json remoto (você edita no GitHub, sem recompilar).
 *  2. O domínio padrão do código (fallback).
 * Todos os candidatos são testados EM PARALELO; vence o primeiro da lista que responder.
 * Se o candidato redireciona para outro domínio, o domínio final é usado, mas só se o host
 * final contiver a chave do provedor (ex.: "redecanais"); assim uma página de bloqueio da
 * operadora nunca é adotada como domínio do site.
 *
 * O resultado fica em cache durante a sessão do app (até invalidate()).
 */
internal object DomainManager {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/davidmarques164/ninja2/main/domains.json"

    private const val TIMEOUT_SECONDS = 8L

    /** Se o domains.json falhar, tenta de novo depois deste intervalo (não fica vazio a sessão toda). */
    private const val REMOTE_RETRY_MS = 5 * 60 * 1000L

    @Volatile private var remote: Map<String, List<String>>? = null
    @Volatile private var remoteFailedAt = 0L
    private val resolved = ConcurrentHashMap<String, String>()

    /** Títulos bloqueados (chave "blocked" no domains.json), em minúsculas. */
    @Volatile var blockedTitles: Set<String> = emptySet()
        private set

    private suspend fun loadRemote(): Map<String, List<String>> {
        remote?.let { return it }
        if (System.currentTimeMillis() - remoteFailedAt < REMOTE_RETRY_MS) return emptyMap()

        val parsed = try {
            val json = app.get(CONFIG_URL, timeout = TIMEOUT_SECONDS).parsed<Map<String, Any?>>()
            blockedTitles = (json["blocked"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim().lowercase() }
                ?.toSet()
                .orEmpty()
            json.filterKeys { it != "blocked" }
                .mapValues { (_, v) -> (v as? List<*>)?.filterIsInstance<String>().orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        if (parsed == null) {
            remoteFailedAt = System.currentTimeMillis()   // não guarda o vazio: tenta de novo depois
            return emptyMap()
        }
        remote = parsed
        return parsed
    }

    /** Devolve o domínio que está respondendo para [key]; se nenhum responder, [fallback]. */
    suspend fun current(key: String, fallback: String): String {
        resolved[key]?.let { return it }

        val candidates = (loadRemote()[key].orEmpty() + fallback)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
            .distinct()

        // Em paralelo: o tempo total é o do candidato mais lento (máx. ~8 s), não a soma.
        // amap preserva a ordem, então a preferência da lista é respeitada.
        val results = candidates.amap { probe(it, key) }
        val working = results.firstOrNull { it != null } ?: fallback.trimEnd('/')

        resolved[key] = working
        return working
    }

    /** Esquece o domínio em cache (use quando uma requisição falhar). */
    fun invalidate(key: String) {
        resolved.remove(key)
    }

    fun isBlocked(title: String?): Boolean =
        title != null && title.trim().lowercase() in blockedTitles

    /**
     * Faz GET no domínio; se responder, devolve o domínio final (após redirects).
     * Rejeita se o redirect levar a um host sem [key] (página de bloqueio, parking, etc.).
     */
    private suspend fun probe(url: String, key: String): String? = try {
        val res = app.get(url, timeout = TIMEOUT_SECONDS)
        if (!res.isSuccessful) {
            null
        } else {
            val finalUri = URI(res.url)
            val host = finalUri.host?.lowercase()
            if (host == null || !host.contains(key.lowercase())) {
                null
            } else {
                val path = URI(url).path.orEmpty().trimEnd('/')   // preserva subcaminho, ex.: /app/v2
                "${finalUri.scheme}://${finalUri.host}$path"
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}