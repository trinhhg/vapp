# Contract: VBook JS Extension Runtime (`:engine`)

**Purpose**: Defines the exact JavaScript API surface, return schema, charset handling, and error behavior exposed to VBook extension scripts inside `vapp`.

## 1. Script Entrypoints & Return Envelope

Every script file in `src/*.js` defines `function execute(...)` returning a JSON string via `Response`:

- `Response.success(data)` -> `{"code":0,"data":<data>}`
- `Response.success(data, data2)` -> `{"code":0,"data":<data>,"data2":<data2>}`
- `Response.error(message)` -> `{"code":1,"data":<message>}`
- Legacy fallback: plain `{data, data2}` objects without `code` are accepted as success; `null`/`undefined` return or `code != 0` fails loudly with explicit diagnostic reason.

### Supported Script Contracts (Novel & Discovery)
- `home.js`: `execute()` -> `[{ title, input, script }]`
- `explore.js`: `execute()` -> `[{ title, input, script, layout }]`
- `genre.js`: `execute()` -> `[{ title, input, script }]`
- `gen.js` / list scripts: `execute(url, page)` -> `Response.success([{ name, link, cover, description, host }], nextPageToken)`
- `search.js`: `execute(key, page)` -> `Response.success([{ name, link, cover, description, host }], nextPageToken)`
- `detail.js`: `execute(url)` -> `Response.success({ name, cover, host, author, description, detail, ongoing, genres, suggests })`
- `page.js` (optional): `execute(url)` -> `Response.success([tocPageUrl, ...])`
- `toc.js`: `execute(url)` -> `Response.success([{ name, url, host }])`
- `chap.js`: `execute(url)` -> `Response.success(htmlContent, optionalTitle)`

## 2. HTTP & `fetch()` Contract (Fixes LeechText Incompatibilities)

```javascript
let res = fetch(url, {
  method: "GET" | "POST" | "PUT" | "DELETE" | "PATCH" | "HEAD",
  headers: { ... },
  queries: { ... },
  body: string | flatObject | Blob,
  timeout: 30000
});
```

`res` MUST be a JS wrapper object (`JsHttpResponse`) exposing:
- **Boolean/Numeric/String Properties** (evaluated accurately as JS primitives, NOT Java method references):
  - `res.ok` (`boolean`: `true` iff `200 <= status <= 299`)
  - `res.status` (`number`: HTTP status code, or `504` on transport error)
  - `res.statusText` (`string`)
  - `res.url` (`string`: final URL after redirects)
  - `res.headers` (`object`: lower-cased header map)
  - `res.request` (`object`: `{ url, headers }`)
- **Methods**:
  - `res.header(key)` -> `string` (case-insensitive, `""` if absent)
  - `res.text(charset?)` -> `string` (decodes bytes via explicit `charset` -> `Content-Type` header -> HTML `<meta charset>` -> `UTF-8`, strips BOM)
  - `res.html(charset?)` -> `JSDocument` (decodes with same charset pipeline, parses with Ksoup, auto-triggers Challenge Solver if Cloudflare marker detected)
  - `res.json()` -> parsed JS value (`JSON.parse`); retains LeechText backward-compat `{ chap_list, status: 200 }` wrap only when an HTML page is requested via `.json()` on legacy extensions
  - `res.base64()` -> `string`
  - `res.blob()` -> `{ _isBlob: true, size, type, base64() }`

Legacy `Http.get(url)` / `Http.post(url)` MUST also support `.string(charset?)`, `.html(charset?)`, `.blob()`, `.headers()`, `.params()`, `.queries()`, `.body()`, `.timeout()`.

## 3. DOM (`Html`), `UserAgent`, Storage & Browser Bridge

- **`Html.parse(text)`**: Returns `JSElement` / `JSElements` supporting `.select()`, `.first()`, `.last()`, `.get(i)`, `.eq(i)`, `.size()`, `.length`, `.isEmpty()`, `.forEach()`, `.map()`, `.text()`, `.html()`, `.outerHtml()`, `.attr(k)`, `.attr(k, v)`, `.attributes()`, `.remove()`, `.remove(selector)`, `.tagName()`, `.tagName(newTag)` (needed by `isach`), `.addClass()`, `.removeClass()`.
- **`UserAgent`**: `UserAgent.system()`, `UserAgent.chrome()`, `UserAgent.android()`, `UserAgent.ios()`, `UserAgent.desktop()`, `UserAgent.mobile()`.
- **`Engine.newBrowser()`**: Delegates to `BrowserSession` bridge (implemented on Android via `WebView`):
  - `.launch(url, timeoutMs)` -> `JSDocument`
  - `.launchAsync(url)`
  - `.loadHtml(baseUrl, html)`
  - `.waitUrl(regexOrArray, timeoutMs)`
  - `.block(regexArray)`
  - `.urls()` -> `string[]`
  - `.callJs(jsCode, timeoutMs)` -> `JSDocument` (supports `_callNativeFunction` / `putVariable` bridge)
  - `.getVariable(name)` -> `string?`
  - `.setUserAgent(ua)`
  - `.html(timeoutMs)` -> `JSDocument`
  - `.close()`
- **Storage & Config**: `localStorage`, `cacheStorage`, `localCookie` (`getCookie`/`setCookie`), `localConfig.getItem(key, defaultValue)` + injected config keys + `BASE_URL` (skipped if `config.js` is present).
- **`load(fileName)`**: Resolves `src/<fileName>` in-memory via pre-execution substitution or sandboxed eval (plus built-in MIT `crypto.js` shim when requested), blocking path traversal and files > 256 KB.

## 4. Security & Rejection Guarantees

- **Encrypted plugins (`"encrypt": true`)**: MUST throw `EncryptedExtensionException` during zip validation with explicit message (`Encrypted VBook extensions are not supported`).
- **Sandbox**: Rhino `ClassShutter` + `SandboxContextFactory` blocks reflection/Java IO/process execution; URLs checked by `NetworkSecurityValidator`; regexes checked by `RegexSecurityValidator`; zip entries checked by `ZipSecurityValidator`.
