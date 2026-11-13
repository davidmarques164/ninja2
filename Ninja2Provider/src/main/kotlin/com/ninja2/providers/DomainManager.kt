package com.ninja2.providers

import com.lagradost.cloudstream3.app
import java.net.URI

/**
 * Resolve o domínio atual de cada provedor.
 *
 * Ordem de tentativa:
 *  1. Domínios listados no domains.json remoto (você edita no GitHub, sem recompilar).
 *  2. O domínio padrão do código (fallback).
 * Para cada candidato, segue o redirecionamento HTTP: se o domínio antigo
 * redireciona para um novo, o novo é usado.
 *
 * O resultado fica em cache durante a sessão do app.
 */
internal object DomainManager {

    // TODO: troque pela URL raw do seu domains.json
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/davidmarques164/ninja2/main/domains.json"

    private const val TIMEOUT_SECONDS = 8L

    private var remote: Map<String, List<String>>? = null
    private val resolved = mutableMapOf<String, String>()

    /** Títulos bloqueados (chave "blocked" no domains.json), em minúsculas. */
    var blockedTitles: Set<String> = emptySet()
        private set

    private suspend fun loadRemote(): Map<String, List<String>> {
        remote?.let { return it }
        val parsed = runCatching {
            val json = app.get(CONFIG_URL, timeout = TIMEOUT_SECONDS).parsed<Map<String, Any?>>()
            blockedTitles = (json["blocked"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim().lowercase() }
                ?.toSet()
                .orEmpty()
            json.filterKeys { it != "blocked" }
                .mapValues { (_, v) -> (v as? List<*>)?.filterIsInstance<String>().orEmpty() }
        }.getOrDefault(emptyMap())
        remote = parsed
        return parsed
    }

    /** Devolve o domínio que está respondendo para [key]; se nenhum responder, [fallback]. */
    suspend fun current(key: String, fallback: String): String {
        resolved[key]?.let { return it }

        val candidates = (loadRemote()[key].orEmpty() + fallback).distinct()
        val working = candidates.firstNotNullOfOrNull { probe(it) } ?: fallback

        resolved[key] = working
        return working
    }

    /** Esquece o domínio em cache (use quando uma requisição falhar). */
    fun invalidate(key: String) {
        resolved.remove(key)
    }

    fun isBlocked(title: String?): Boolean =
        title != null && title.trim().lowercase() in blockedTitles

    /** Faz GET no domínio; se responder, devolve o domínio final (após redirects). */
    private suspend fun probe(url: String): String? = runCatching {
        val res = app.get(url, timeout = TIMEOUT_SECONDS)
        if (!res.isSuccessful) return null
        val finalUri = URI(res.url)
        val path = URI(url).path.orEmpty().trimEnd('/')   // preserva subcaminho, ex.: /app/v2
        "${finalUri.scheme}://${finalUri.host}$path"
    }.getOrNull()
}
