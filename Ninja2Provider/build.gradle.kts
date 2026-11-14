// Versão do plugin: aumente a cada release para o app detectar a atualização.
version = 21

cloudstream {
    description = "Ninja 2 - provedores PT-BR baseados em páginas HTML/PHP"
    authors = listOf("ResearchCaseStudy")
    tvTypes = listOf("Movie", "TvSeries", "Live")
    language = "pt-br"
    status = 1 // 0 = fora do ar, 1 = ok, 2 = lento, 3 = beta
}

android {
    namespace = "com.ninja2.providers"
}
