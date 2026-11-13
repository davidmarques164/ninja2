# Ninja 2

Esqueleto de plugin CloudStream para sites em PHP/HTML tradicional
(listagens em blocos HTML, categorias por tag, players em iframe ou atrás de redirecionamentos).

## Estrutura

```
Ninja2\
├── settings.gradle.kts            ← lista os módulos (include("Ninja2Provider"))
├── build.gradle.kts               ← plugins, JVM 11, dependências (comum a todos os módulos)
├── Ninja2Provider\
│   ├── build.gradle.kts           ← metadados e versão do plugin
│   └── src\main\kotlin\com\ninja2\providers\
│       ├── Ninja2Plugin.kt        ← @CloudstreamPlugin: registra os provedores
│       ├── DomainManager.kt       ← domínio atual (domains.json remoto + redirects) e títulos bloqueados
│       └── HtmlSiteProvider.kt    ← provedor genérico (copie e renomeie para cada site)
└── repo\                          ← conteúdo do repositório GitHub que o app lê
    ├── repo.json
    ├── plugins.json
    └── domains.json
```

## Primeira configuração

1. Copie `gradlew`, `gradlew.bat` e a pasta `gradle\` do projeto Ninja para a raiz do Ninja2.
2. Crie o repositório `ninja2` no GitHub (ou outro nome) e ajuste as URLs em:
   - `build.gradle.kts` → `setRepo(...)`
   - `DomainManager.kt` → `CONFIG_URL`
   - `repo\repo.json` e `repo\plugins.json`
3. Build: `.\gradlew Ninja2Provider:make` → `Ninja2Provider\build\Ninja2Provider.cs3`

## Adicionar um site

1. Copie `HtmlSiteProvider.kt` → `NomeDoSiteProvider.kt` e renomeie a classe.
2. Ajuste os campos marcados com `TODO`:
   - `name`, `mainUrl`, `domainKey`
   - `mainPage` (caminhos das categorias/tags)
   - `pageUrl` / `searchUrl` (formato de paginação e busca)
   - seletores em `Sel` (pegue pelo F12 → inspecionar)
3. Registre em `Ninja2Plugin.kt`: `registerMainAPI(NomeDoSiteProvider())`
4. Adicione a chave do site em `repo\domains.json`.

Se o player carrega por AJAX (botão de servidor que chama `player.php?id=...`),
veja a requisição em F12 → Network e reproduza com `app.post(...)` dentro de `loadLinks`.

Se o site usar Cloudflare: `app.get(url, interceptor = CloudflareKiller())`
(`import com.lagradost.cloudstream3.network.CloudflareKiller`).

## Troca de domínio

`DomainManager.current(key, fallback)` testa, em ordem, os domínios do `domains.json`
e depois o `mainUrl` do código, seguindo redirecionamentos HTTP. O primeiro que responde é usado
durante a sessão. Se uma requisição falhar, o provedor revalida o domínio e tenta de novo uma vez.

Quando um site mudar de endereço: edite `domains.json` no GitHub. Não precisa recompilar.

## Publicar atualização

1. Aumente `version` em `Ninja2Provider\build.gradle.kts`.
2. `.\gradlew Ninja2Provider:make`
3. No repositório: substitua o `.cs3`, atualize `version` e `fileSize` em `plugins.json`, faça push.
