# Grimmdroid v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android client for Grimmory servers that mirrors the catalogue offline, downloads books, and hands them to the user's reader app, share sheet or folder.

**Architecture:** One Gradle module `:app`, packages split by responsibility (`api`, `auth`, `account`, `catalog`, `sync`, `download`, `export`, `settings`, `ui/*`). A hand-written `AppContainer` wires dependencies (no DI framework). Room holds the per-account catalogue mirror and is the only source the UI reads; WorkManager runs sync and downloads; Ktor talks to Grimmory.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Navigation Compose (type-safe `@Serializable` routes), Ktor client (OkHttp engine, ContentNegotiation + kotlinx.serialization, Auth bearer), Room (KSP, FTS4, Paging 3), WorkManager, DataStore Preferences, Coil 3 (OkHttp network), androidx.browser Custom Tabs, androidx.documentfile. Tests: Kotest (runner-junit5, assertions, property) for pure JVM logic; Robolectric (JUnit4, run on the JUnit Platform via vintage engine) for Room/Android-dependent tests; Ktor `MockEngine` for HTTP. All library versions: latest stable at implementation time, declared only in `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-10-19-grimmdroid-design.md` and `docs/superpowers/specs/2026-10-19-grimmdroid-ui.md` (mockups: https://claude.ai/artifact/WhTGXCz9UYhv8TST9tdywJ). Grimmory server source referenced below is upstream `grimmory-tools/grimmory` at commit `c11582e`.

## Global Constraints

- Application id / package: `io.github.asm0dey.grimmdroid`. App name: `Grimmdroid`.
- `minSdk 26`, `targetSdk`/`compileSdk` = latest stable. JDK: BellSoft Liberica 21 (CI and Gradle daemon criteria).
- License GPL-3.0-or-later; every Kotlin source file carries the license header, enforced by Spotless (`licenseHeaderFile`), plus ktlint via Spotless.
- `gradle/libs.versions.toml` is the single version source. No refreshVersions. Renovate: `config:recommended` + one grouped PR for minor/patch/digest/pin; `vulnerabilityAlerts` on.
- F-Droid: only FOSS dependencies. No Firebase, no Google Play Services, no proprietary SDKs, no analytics.
- Merge gate (CI on every push/PR): `./gradlew spotlessCheck lint testDebugUnitTest` (wrapped by `fastlane test`).
- Never call `GET /api/v1/books` (returns the whole library). Catalogue comes from `/api/v1/app/*`, page size **50** (server max, `AppBookService.MAX_PAGE_SIZE`).
- Every catalogue row, downloaded file, token and export folder is scoped by `accountId`.
- OIDC redirect URI: exactly `grimmory://oauth2-callback`.
- Copy that the spec fixes (use verbatim):
  - "Your account can't download books"
  - "Audiobooks aren't supported yet"
  - "Signed out of <server> · Sign in"
  - "<title> downloaded" (notification title)
  - "Saved to <folder> · Change" (snackbar)
  - "After a download finishes": "Do nothing" / "Open in my reader" / "Save to my folder"
- Touch targets ≥ 48dp; system font (no bundled fonts); Material Symbols outlined icons; dynamic colour on by default, fallback seed `#92400E`.

## Review Focus

1. **Library of 10,000+ books.** First sync must stream page-by-page (never hold the whole catalogue in memory) and the grid must stay scrollable while sync runs. → Task 7 test `sync of 10,050 books stores all and keeps memory bounded per page`.
2. **Sync interrupted half-way** (network drop, process death). The previously synced catalogue must stay complete; no books disappear because a sweep didn't finish. → Task 7 test `failed sweep deletes nothing`.
3. **Access token (2h) expires during a long sync or download.** Must refresh once and continue; only a failed refresh signs the account out. → Task 3 test `401 mid-request triggers one refresh then retries`; Task 8 test `download resumes after token refresh`.
4. **Server without the mobile API** (older Grimmory, Booklore, reverse proxy returning HTML). Show the "not supported" message with the server version, never crash or loop. → Task 2 tests `html response classifies as NotGrimmory`, `404 on app api classifies as Unsupported`.
5. **Hostile or awkward filenames** (non-ASCII, `/`, `..`, very long, duplicates in the export folder). Stored and exported names must be safe and unique. → Task 8 test `content-disposition filename star is decoded and sanitised`; Task 9 test `name collision gets numbered suffix`.

## Decisions taken while planning (my calls, flagged to the author)

- **Sync = full page sweep with a generation marker**, not an `/ids` diff: `AppBookSummary` has no `updatedAt`, so an id diff cannot detect edited metadata. `/api/v1/app/books/ids?shelfId=…|magicShelfId=…` *is* used for shelf membership. `shortcut: full sweep each sync (~200 requests for 10k books); upgrade to a change feed if Grimmory adds one.` (Design doc updated.)
- **Self-signed certificates:** supported only when the user installs the certificate as a user CA in Android settings (network security config trusts user CAs). No in-app "trust this certificate" pinning in v1. Plain `http://` allowed with a warning. (Design doc and mockup copy updated accordingly.)
- **Server support is detected by capability, not version number:** after login, `GET /api/v1/app/users/me` returning 404 ⇒ Unsupported. Integration tests pin `ghcr.io/grimmory-tools/grimmory:v3.5.0`.
- Covers load from `/api/v1/media/book/{id}/thumbnail` (grid) and `/cover` (details) with `?t=<coverUpdatedOn epoch>` as cache key. `AppBookSummary.thumbnailUrl` (`/api/books/{id}/cover`) is ignored: that route does not exist on the server.
- Primary file downloads via `GET /api/v1/books/{id}/download` (needs `canDownload`); other files via `GET /api/v1/books/{bookId}/files/{fileId}/download`.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties
renovate.json, LICENSE, HEADER (license header template), README.md
.github/workflows/ci.yml, .github/workflows/integration.yml
fastlane/Fastfile, fastlane/Appfile, fastlane/metadata/android/en-US/{title,short_description,full_description}.txt, changelogs/1.txt
integration/docker-compose.yml, integration/fixtures/ (small generated EPUB)
app/build.gradle.kts, app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/xml/network_security_config.xml, file_paths.xml
app/src/main/kotlin/io/github/asm0dey/grimmdroid/
  GrimmdroidApp.kt            Application; builds AppContainer
  AppContainer.kt             manual wiring of everything below
  MainActivity.kt             edge-to-edge, theme, NavHost, OIDC redirect intent
  api/Dto.kt                  @Serializable DTOs mirroring server JSON
  api/ServerProbe.kt          probe(url) → ProbeResult
  api/GrimmoryApi.kt          authenticated endpoints
  api/HttpClients.kt          Ktor client factory (bearer auth + refresh)
  auth/PasswordLogin.kt
  auth/Oidc.kt                PKCE, discovery, authorize URL, callback exchange
  account/AccountEntity.kt, AccountDao.kt, AccountRepository.kt
  account/TokenCipher.kt      Keystore AES-GCM (interface + impl)
  catalog/Entities.kt, CatalogDao.kt, CatalogDb.kt, BookQuery.kt
  sync/LibrarySync.kt, SyncWorker.kt, SyncState.kt
  download/DownloadRepository.kt, DownloadWorker.kt, FileNames.kt, Notifications.kt
  export/Exporter.kt, MimeTypes.kt, FolderExport.kt, AfterDownload.kt
  settings/SettingsStore.kt   DataStore: theme, dynamic colour, after-download, folder per account
  ui/theme/Theme.kt
  ui/nav/Routes.kt, AppNav.kt, AccountSheet.kt, GlobalBanners.kt
  ui/onboarding/{ServerScreen,SignInScreen,OnboardingViewModel}.kt
  ui/library/{LibraryScreen,LibraryViewModel,CoverGrid,SortFilterSheet}.kt
  ui/search/{SearchScreen,SearchViewModel}.kt
  ui/shelves/{ShelvesScreen,ShelvesViewModel}.kt
  ui/home/{HomeScreen,HomeViewModel}.kt
  ui/downloads/{DownloadsScreen,DownloadsViewModel}.kt
  ui/details/{DetailsScreen,DetailsViewModel,DetailsState}.kt
  ui/settings/{SettingsScreen,SettingsViewModel}.kt
app/src/test/kotlin/io/github/asm0dey/grimmdroid/...   mirrors main
app/src/integrationTest/kotlin/...                     Task 16
```

---

### Task 1: Project skeleton, tooling and CI

**Files:**
- Create: everything in the top block of File Structure, `app/build.gradle.kts`, `AndroidManifest.xml`, `GrimmdroidApp.kt`, `MainActivity.kt` (shows a blank themed `Scaffold`), `app/src/test/kotlin/.../SmokeSpec.kt`

**Interfaces:**
- Produces: Gradle tasks `spotlessCheck`, `lint`, `testDebugUnitTest`; `fastlane test` lane running them; Kotest + Robolectric both executing under `testDebugUnitTest`.

- [ ] **Step 1: Write `SmokeSpec` (Kotest `FunSpec`, asserts `1 + 1 shouldBe 2`) and `RobolectricSmokeTest` (JUnit4 `@RunWith(AndroidJUnit4::class)`, asserts `ApplicationProvider.getApplicationContext<Context>().packageName == "io.github.asm0dey.grimmdroid"`).**
- [ ] **Step 2: Build the Gradle setup** (version catalog, AGP, Kotlin + Compose compiler plugin, KSP, kotlinx-serialization plugin, Spotless with ktlint + `HEADER`, JUnit Platform with Kotest + vintage engine, `unitTests.isIncludeAndroidResources = true`), Gradle daemon JVM criteria = Liberica 21.
- [ ] **Step 3: Run** `./gradlew spotlessCheck lint testDebugUnitTest` — Expected: BUILD SUCCESSFUL, 2 tests passed.
- [ ] **Step 4: Add `ci.yml`** (setup-java Liberica 21, `fastlane test`), `renovate.json` per Global Constraints, fastlane `Fastfile` with lanes `test` and `release` (release signs from env vars `GRIMMDROID_KEYSTORE`, `GRIMMDROID_KEYSTORE_PASSWORD`, `GRIMMDROID_KEY_ALIAS`, `GRIMMDROID_KEY_PASSWORD` read in `app/build.gradle.kts` `signingConfigs`), F-Droid metadata text files.
- [ ] **Step 5: `git init`, commit** `chore: project skeleton, tooling, CI`. Move `docs/` in with this commit.

### Task 2: Server probe

**Files:**
- Create: `api/Dto.kt`, `api/ServerProbe.kt`, `api/HttpClients.kt`
- Test: `api/ServerProbeSpec.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class PublicSettings(val oidcEnabled: Boolean = false, val remoteAuthEnabled: Boolean = false, val oidcForceOnlyMode: Boolean = false, val oidcProviderDetails: OidcProviderDetails? = null)`
  - `@Serializable data class OidcProviderDetails(val providerName: String? = null, val clientId: String, val issuerUri: String, val scopes: String? = null)`
  - `@Serializable data class VersionInfo(val current: String, val latest: String? = null)`
  - `sealed interface ProbeResult { data class Ok(val baseUrl: String, val version: String, val settings: PublicSettings, val cleartext: Boolean) ; data object Unreachable ; data object NotGrimmory ; data class TlsUntrusted(val host: String) }`
  - `class ServerProbe(client: HttpClient) { suspend fun probe(input: String): ProbeResult }`
  - `fun normalizeBaseUrl(input: String): String` — trims, adds `https://` when no scheme, strips trailing `/`.
  - `fun httpClient(engine: HttpClientEngine = OkHttp.create(), json: Json = GrimmoryJson): HttpClient`; `val GrimmoryJson = Json { ignoreUnknownKeys = true; explicitNulls = false }`

- [ ] **Step 1: Write failing tests** (MockEngine):
  - `normalizeBaseUrl("books.example.net/") shouldBe "https://books.example.net"`; `"http://10.0.0.2:6060" ` stays as is.
  - JSON for `/api/v1/public-settings` + `/api/v1/version` (`{"current":"v3.5.0","latest":"v3.5.0"}`) ⇒ `Ok(version="v3.5.0", cleartext=false)`.
  - `http://` URL ⇒ `Ok(cleartext = true)`.
  - `html response classifies as NotGrimmory` (200 `text/html` login page from a proxy).
  - 404 on `public-settings` ⇒ `NotGrimmory`.
  - `IOException` / `UnknownHostException` ⇒ `Unreachable`; `SSLHandshakeException` ⇒ `TlsUntrusted(host)`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ServerProbeSpec'` — Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** `ServerProbe.probe` — both GETs, unauthenticated; classify by exception type and by `ContentType` not being JSON.
- [ ] **Step 4: Run the tests** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(api): server probe`.

### Task 3: Password login, authenticated client, capability check

**Files:**
- Create: `auth/PasswordLogin.kt`, `api/GrimmoryApi.kt`; Modify: `api/HttpClients.kt`, `api/Dto.kt`
- Test: `auth/PasswordLoginSpec.kt`, `api/AuthRefreshSpec.kt`

**Interfaces:**
- Consumes: `httpClient`, `GrimmoryJson` (Task 2).
- Produces:
  - `@Serializable data class Tokens(val accessToken: String, val refreshToken: String, val expires: Long? = null, val isDefaultPassword: Boolean? = null)` (server `AccessTokenDto`; `expires` is seconds of access-token lifetime)
  - `@Serializable data class AppUserInfo(val isAdmin: Boolean = false, val canDownload: Boolean = false, val canUpload: Boolean = false)` — server JSON keys are `isAdmin`, `canDownload`… verify against a real response in Task 16 (Lombok boolean naming).
  - `sealed interface LoginResult { data class Ok(val tokens: Tokens, val user: AppUserInfo) ; data object BadCredentials ; data class Unsupported(val serverVersion: String) ; data class Failed(val cause: Throwable) }`
  - `class PasswordLogin(client: HttpClient) { suspend fun login(baseUrl: String, serverVersion: String, username: String, password: String): LoginResult }` — `POST /api/v1/auth/login {username,password}`; 401/403 ⇒ `BadCredentials`; then `GET /api/v1/app/users/me` with the token; 404 ⇒ `Unsupported`.
  - `interface TokenSource { suspend fun current(): Tokens? ; suspend fun save(t: Tokens) ; suspend fun expired() }` (implemented in Task 4)
  - `fun authedClient(baseUrl: String, tokens: TokenSource, engine: HttpClientEngine = OkHttp.create()): HttpClient` — Ktor `Auth { bearer { loadTokens; refreshTokens { POST /api/v1/auth/refresh {refreshToken} } } }`, `defaultRequest { url(baseUrl) }`. A failed refresh calls `tokens.expired()` and surfaces `SessionExpiredException`.
  - `class GrimmoryApi(client: HttpClient)` with: `me(): AppUserInfo`, `books(page: Int, size: Int = 50): AppPage<BookSummaryDto>`, `bookIds(shelfId: Long? = null, magicShelfId: Long? = null): List<Long>`, `book(id: Long): BookDetailDto`, `libraries(): List<LibraryDto>`, `shelves(): List<ShelfDto>`, `magicShelves(): List<MagicShelfDto>`, `continueReading(): List<BookSummaryDto>`, `recentlyAdded(): List<BookSummaryDto>`. DTO fields: copy names from server `app/dto/AppBookSummary`, `AppBookDetail`, `AppBookFile`, `AppPageResponse`, `AppLibrarySummary`, `AppShelfSummary`, `AppMagicShelfSummary` — only fields used by Tasks 6–14 (id, title, authors, seriesName, seriesNumber, libraryId, addedOn, lastReadTime, readProgress, readStatus, primaryFileId, primaryFileType, primaryFileName, coverUpdatedOn, audiobookCoverUpdatedOn, fileSizeKb, pageCount, language, publisher, publishedDate, categories; detail adds subtitle, description, libraryName, files, fileTypes).

- [ ] **Step 1: Write failing tests:**
  - login 200 + me 200 ⇒ `Ok`, request body is `{"username":"pavel","password":"pw"}`.
  - login 401 ⇒ `BadCredentials`.
  - `404 on app api classifies as Unsupported` (`Unsupported("v3.0.0")`).
  - `401 mid-request triggers one refresh then retries`: first `/api/v1/app/users/me` 401, `/api/v1/auth/refresh` 200 with new tokens, retry 200 ⇒ result OK, `TokenSource.save` called once with new tokens.
  - refresh returns 401 ⇒ `SessionExpiredException`, `TokenSource.expired()` called, no infinite loop (exactly 1 refresh request).
  - `books(0)` sends `page=0&size=50&sort=addedOn&dir=asc` and parses an `AppPageResponse` fixture.
- [ ] **Step 2: Run** `--tests '*PasswordLoginSpec' --tests '*AuthRefreshSpec'` — FAIL.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(api): password login, token refresh, app api`.

### Task 4: Accounts and encrypted tokens

**Files:**
- Create: `account/AccountEntity.kt`, `account/AccountDao.kt`, `account/AccountRepository.kt`, `account/TokenCipher.kt`, `settings/SettingsStore.kt` (DataStore, minimal), `catalog/CatalogDb.kt` (Room database class, version 1; Task 6 adds catalogue entities to it)
- Test: `account/AccountRepositoryTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `Tokens`, `AppUserInfo`, `TokenSource` (Task 3).
- Produces:
  - `@Entity data class AccountEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val baseUrl: String, val username: String, val serverVersion: String, val canDownload: Boolean, val isAdmin: Boolean, val encryptedTokens: ByteArray?, val signedOut: Boolean = false, val lastSyncedAt: Long? = null)` — unique index on (`baseUrl`, `username`).
  - `interface TokenCipher { fun encrypt(plain: ByteArray): ByteArray ; fun decrypt(cipher: ByteArray): ByteArray }`; `class KeystoreTokenCipher : TokenCipher` (AES/GCM/NoPadding, key alias `grimmdroid_tokens`, IV prepended). Tests use a `FakeTokenCipher` (XOR); Keystore impl is verified manually on device (Robolectric has no AndroidKeyStore).
  - `class AccountRepository(dao: AccountDao, cipher: TokenCipher, settings: SettingsStore)`: `accounts: Flow<List<AccountEntity>>`, `current: Flow<AccountEntity?>`, `suspend fun addOrUpdate(baseUrl, username, version, user: AppUserInfo, tokens: Tokens): Long` (also makes it current), `suspend fun switchTo(id: Long)`, `suspend fun remove(id: Long, deleteDownloads: Boolean)`, `fun tokenSource(id: Long): TokenSource` (`expired()` sets `signedOut = true`, keeps catalogue).
  - `SettingsStore` minimal here: `currentAccountId: Flow<Long?>`, `setCurrentAccountId(Long?)` (rest in Task 9).

- [ ] **Step 1: Write failing tests:** add two accounts on different servers ⇒ `accounts` has 2, `current` is the second; re-adding same (`baseUrl`,`username`) updates, doesn't duplicate; tokens round-trip through `tokenSource(id).current()`; `expired()` ⇒ `signedOut == true` and `current()` still returns the account; `remove` of current switches `current` to the remaining account (or null).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(account): multi-account store with encrypted tokens`.

### Task 5: OIDC (SSO) login

**Files:**
- Create: `auth/Oidc.kt`; Modify: `AndroidManifest.xml` (intent filter on `MainActivity`: scheme `grimmory`, host `oauth2-callback`, `launchMode="singleTask"`), `MainActivity.kt` (forwards the redirect `Intent.data` to `OidcLogin.complete`)
- Test: `auth/OidcSpec.kt`

**Interfaces:**
- Consumes: `PublicSettings`, `OidcProviderDetails`, `LoginResult`, `AppUserInfo` (Tasks 2–3).
- Produces:
  - `data class Pkce(val verifier: String, val challenge: String)`; `fun pkceFrom(verifier: String): Pkce` (S256, base64url no padding); `fun newPkce(random: SecureRandom = SecureRandom()): Pkce` (43–128 chars).
  - `data class PendingOidc(val baseUrl: String, val serverVersion: String, val state: String, val nonce: String, val verifier: String)` — kept in a `SavedStateHandle` / small DataStore entry so process death during the Custom Tab doesn't lose it.
  - `class OidcLogin(client: HttpClient)`: `suspend fun start(baseUrl: String, version: String, details: OidcProviderDetails): Pair<Uri, PendingOidc>` — `GET /api/v1/auth/oidc/state` (`{"state":…}`), fetch `<issuerUri>/.well-known/openid-configuration` → `authorization_endpoint`, build URL with `response_type=code`, `client_id`, `redirect_uri=grimmory://oauth2-callback`, `scope` (= `details.scopes` or `openid profile email offline_access`), `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`.
  - `suspend fun complete(redirect: Uri, pending: PendingOidc): LoginResult` — reject when `state` differs (`Failed`), when `error` param present (`Failed` with IdP's `error_description`); else `POST /api/v1/auth/oidc/mobile/callback` with query params `code, code_verifier, redirect_uri, nonce, state` ⇒ `Tokens`, then `me()` as in Task 3.
  - `data class RedirectRejected(val redirectUri: String) : Exception()` — when the server answers 400/401 mentioning the redirect URI, so UI can show "ask your admin to allow grimmory://oauth2-callback".

- [ ] **Step 1: Write failing tests:**
  - RFC 7636 Appendix B: `pkceFrom("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"`.
  - property test: `newPkce().verifier` length in 43..128 and only `[A-Za-z0-9-._~]`.
  - `start` builds an authorize URL containing all params above with `redirect_uri=grimmory%3A%2F%2Foauth2-callback`.
  - `complete` with mismatched state ⇒ `Failed`, no HTTP call made.
  - `complete` happy path posts to `/api/v1/auth/oidc/mobile/callback` with the five query params.
  - server 400 body containing `redirect` ⇒ `RedirectRejected("grimmory://oauth2-callback")`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** (Custom Tab launch lives in the onboarding UI, Task 11). **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(auth): OIDC PKCE login via mobile callback`.

### Task 6: Catalogue database

**Files:**
- Create: `catalog/Entities.kt`, `catalog/CatalogDao.kt`, `catalog/BookQuery.kt`; Modify: `catalog/CatalogDb.kt`
- Test: `catalog/CatalogDaoTest.kt` (Robolectric, in-memory Room)

**Interfaces:**
- Produces:
  - `@Entity(primaryKeys = ["accountId","id"]) data class BookEntity(accountId: Long, id: Long, title: String, authors: String /* joined with ", " */, seriesName: String?, seriesNumber: Float?, libraryId: Long?, addedOn: Long?, lastReadTime: Long?, readProgress: Float?, primaryFileId: Long?, primaryFileType: String?, primaryFileName: String?, fileSizeKb: Long?, coverUpdatedOn: Long?, isAudiobook: Boolean, syncGeneration: Long)` — `isAudiobook = primaryFileType == "AUDIOBOOK"` (verify value in Task 16).
  - `@Fts4(contentEntity = BookEntity::class) BookFts(title, authors, seriesName)`.
  - `LibraryEntity(accountId, id, name, bookCount)`, `ShelfEntity(accountId, id, name, bookCount, magic: Boolean)`, `ShelfBookCrossRef(accountId, shelfId, magic: Boolean, bookId)`, `DownloadEntity(accountId, bookId, fileId, fileName, path, sizeBytes, state: DownloadState, progress: Float)` with `enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }`.
  - `enum class BookSort(val column: String) { ADDED("addedOn DESC"), TITLE("title COLLATE NOCASE ASC"), AUTHOR("authors COLLATE NOCASE ASC"), SERIES("seriesName, seriesNumber") }`
  - `data class BookQuery(val accountId: Long, val libraryId: Long? = null, val shelfId: Long? = null, val magicShelfId: Long? = null, val formats: Set<String> = emptySet(), val downloadedOnly: Boolean = false, val sort: BookSort = BookSort.ADDED)`; `fun BookQuery.toSql(): SimpleSQLiteQuery` (parameters bound, never string-concatenated values).
  - `CatalogDao`: `@RawQuery(observedEntities=[BookEntity::class, ShelfBookCrossRef::class, DownloadEntity::class]) fun books(q: SupportSQLiteQuery): PagingSource<Int, BookRow>`, `search(accountId, ftsQuery: String): PagingSource<Int, BookRow>`, `upsertBooks(List<BookEntity>)`, `deleteBooksNotIn(accountId, generation)`, `book(accountId, id): Flow<BookEntity?>`, `continueReading(accountId, limit=20)` (lastReadTime not null, readProgress < 100, order lastReadTime desc), `recentlyAdded(accountId, limit=20)`, library/shelf upserts + replace-membership, `deleteAccount(accountId)`.
  - `data class BookRow(@Embedded val book: BookEntity, val downloaded: Boolean)` (left join `DownloadEntity` state DONE).

- [ ] **Step 1: Write failing tests:** rows of account 1 never appear in account 2 queries; `libraryId` filter; `shelfId` filter via cross-ref; `downloadedOnly`; each `BookSort` order; FTS search `"mel*"` finds "Moby-Dick" by author "Herman Melville"; a query with a format value `"EPUB'; DROP TABLE"` returns 0 rows and the table survives; `deleteBooksNotIn` removes only older generations for that account.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(catalog): per-account catalogue mirror with FTS`.

### Task 7: Library sync

**Files:**
- Create: `sync/LibrarySync.kt`, `sync/SyncState.kt`, `sync/SyncWorker.kt`
- Test: `sync/LibrarySyncTest.kt` (Robolectric + fake `GrimmoryApi` via MockEngine)

**Interfaces:**
- Consumes: `GrimmoryApi` (Task 3), `CatalogDao` (Task 6), `AccountRepository` (Task 4).
- Produces:
  - `sealed interface SyncState { data object Idle ; data class Running(val done: Int, val total: Int) ; data class Failed(val cause: Throwable) ; data object SignedOut }`
  - `class LibrarySync(dao: CatalogDao, accounts: AccountRepository, apiFor: (AccountEntity) -> GrimmoryApi, clock: () -> Long)`: `val state: StateFlow<Map<Long, SyncState>>`, `suspend fun sync(accountId: Long)`.
  - Algorithm: `generation = clock()`; refresh `me()` permissions into the account; libraries, shelves, magic shelves; for page in 0.. while `hasNext`: `books(page)` → map → `upsertBooks(generation)` → emit `Running(done,total=totalElements)`; for each shelf / magic shelf `bookIds(shelfId|magicShelfId)` → replace cross-refs; **only after every page succeeded** `deleteBooksNotIn(accountId, generation)`; set `lastSyncedAt`. Any exception ⇒ `Failed`, nothing deleted. `SessionExpiredException` ⇒ `SignedOut`.
  - `class SyncWorker : CoroutineWorker` — input `accountId`; unique work name `sync-<accountId>`; `fun scheduleSync(context, accountId, now: Boolean)`: periodic every 6h with network constraint + one-time expedited when `now`.

- [ ] **Step 1: Write failing tests:**
  - `sync of 10,050 books stores all and keeps memory bounded per page`: fake serves 201 pages of ≤ 50; afterwards 10,050 rows; fake asserts it never received `size > 50`; `Running.total == 10050`.
  - book absent from the second sync is deleted; book with changed title is updated.
  - `failed sweep deletes nothing`: second sync fails on page 3 ⇒ all rows from sync 1 remain, state `Failed`.
  - shelf membership replaced from `/ids?shelfId=`; magic shelf from `/ids?magicShelfId=`.
  - 401 + failed refresh ⇒ state `SignedOut`, account `signedOut == true`, rows remain.
  - `canDownload` change from `me()` is stored on the account.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(sync): full-sweep catalogue sync with generations`.

### Task 8: Downloads

**Files:**
- Create: `download/FileNames.kt`, `download/DownloadRepository.kt`, `download/DownloadWorker.kt`, `download/Notifications.kt`
- Test: `download/FileNamesSpec.kt` (Kotest), `download/DownloadRepositoryTest.kt` (Robolectric + MockEngine)

**Interfaces:**
- Consumes: `CatalogDao`/`DownloadEntity` (Task 6), `AccountRepository.tokenSource` + `authedClient` (Tasks 3–4).
- Produces:
  - `fun parseContentDisposition(header: String?): String?` — prefers `filename*=UTF-8''…` (percent-decoded), else `filename="…"`.
  - `fun safeFileName(raw: String, fallback: String): String` — replace `/` and `\` with `_`, then remove `..`, control chars and `:*?"<>|`; trim to 120 UTF-8 bytes keeping the extension; blank ⇒ `fallback`.
  - File layout: `filesDir/books/<accountId>/<bookId>/<safeFileName>`; download writes `<name>.part`, then atomic rename.
  - `class DownloadRepository(dao, http: (accountId) -> HttpClient, filesDir: File)`: `fun observe(accountId, bookId): Flow<DownloadEntity?>`, `suspend fun download(accountId: Long, bookId: Long, fileId: Long?, isPrimary: Boolean, onProgress: (Float) -> Unit): File` (primary ⇒ `/api/v1/books/{id}/download`, else `/api/v1/books/{bookId}/files/{fileId}/download`; streams with `bodyAsChannel`, never loads whole file in memory), `suspend fun delete(accountId, bookId)`, `fun enqueue(context, accountId, bookId, fileId, isPrimary)` (WorkManager unique work `dl-<accountId>-<bookId>`, `KEEP`), `fun cancel(context, accountId, bookId)`.
  - `DownloadWorker` runs as foreground (`setForeground` with a progress notification, channel `downloads`); on success posts "<title> downloaded" (no actions yet — Task 9 adds **Open**/**Share** and the after-download step).

- [ ] **Step 1: Write failing tests:**
  - `content-disposition filename star is decoded and sanitised`: `attachment; filename="Moby_Dick.epub"; filename*=UTF-8''Moby%E2%80%93Dick%2F..%2Fx.epub` ⇒ `Moby–Dick__x.epub` (each `/` or `\` becomes `_`, then every `..` is removed).
  - `safeFileName("", "book-12.epub") == "book-12.epub"`; a 300-char name keeps `.epub`.
  - download of a 5 MB body writes the file, final state `DONE`, `sizeBytes == 5_242_880`, no `.part` left.
  - server drops connection mid-body ⇒ state `FAILED`, no final file, `.part` deleted.
  - `download resumes after token refresh`: first request 401, refresh 200, retry 200 ⇒ `DONE`.
  - 403 (no permission) ⇒ `FAILED` with `NoDownloadPermission` cause.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(download): background downloads with notifications`.

### Task 9: Export: open with, share, save to folder, after-download action

**Files:**
- Create: `export/MimeTypes.kt`, `export/Exporter.kt`, `export/FolderExport.kt`, `export/AfterDownload.kt`, `res/xml/file_paths.xml` (FileProvider path `books/`); Modify: `settings/SettingsStore.kt`, `download/DownloadWorker.kt` + `download/Notifications.kt` (completion notification gets **Open** and **Share** actions; worker calls `AfterDownload.run`), `AndroidManifest.xml` (FileProvider authority `io.github.asm0dey.grimmdroid.files`)
- Test: `export/MimeTypesSpec.kt`, `export/FolderExportTest.kt` (Robolectric), `export/AfterDownloadSpec.kt`

**Interfaces:**
- Produces:
  - `fun mimeFor(fileName: String): String` — epub `application/epub+zip`, pdf `application/pdf`, cbz `application/vnd.comicbook+zip`, cbr `application/vnd.comicbook-rar`, mobi `application/x-mobipocket-ebook`, azw3 `application/vnd.amazon.ebook`, fb2 `application/x-fictionbook+xml`, else `application/octet-stream`.
  - `class Exporter(context)`: `fun openWith(file: File): ExportResult` (`ACTION_VIEW`, content URI, `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser`; no resolvable activity ⇒ `ExportResult.NoApp`), `fun share(file: File, title: String)` (`ACTION_SEND`, `EXTRA_STREAM`).
  - `class FolderExport(context, settings)`: `suspend fun save(accountId: Long, file: File): FolderResult` — `NeedsFolder` when no persisted tree URI for the account (UI then launches `OpenDocumentTree` and calls `setFolder(accountId, uri)` which takes persistable permission), `Saved(folderName)` on success, `FolderLost` when permission was revoked. Existing name ⇒ `"Name (1).epub"`, `"Name (2).epub"`…
  - `enum class AfterDownloadAction { NOTHING, OPEN, SAVE_TO_FOLDER }`; `SettingsStore` adds `afterDownload: Flow<AfterDownloadAction>` (default `NOTHING`), `folderUri(accountId): Flow<Uri?>`, `setFolder`, `theme: Flow<ThemeMode>` (`SYSTEM, LIGHT, DARK`), `dynamicColor: Flow<Boolean>` (default true).
  - `class AfterDownload(settings, exporter, folderExport) { suspend fun run(accountId: Long, file: File): AfterDownloadOutcome }` — `OPEN` from a background worker posts the notification's Open action instead of starting an activity (Android blocks background activity starts); `SAVE_TO_FOLDER` without folder ⇒ outcome `NeedsFolder` (notification says so).

- [ ] **Step 1: Write failing tests:** `mimeFor("A.EPUB") == "application/epub+zip"`, unknown ⇒ octet-stream; `name collision gets numbered suffix` (folder already has `Moby-Dick.epub` and `Moby-Dick (1).epub` ⇒ saves `Moby-Dick (2).epub`); `save` without folder ⇒ `NeedsFolder`; folders are per account (account 2 has none while account 1 has one); `AfterDownload` with `NOTHING` touches nothing, `SAVE_TO_FOLDER` calls `FolderExport.save` once.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(export): open-with, share, save-to-folder, after-download setting`.

### Task 10: Theme, navigation shell, account sheet, global banners

**Files:**
- Create: `ui/theme/Theme.kt`, `ui/nav/Routes.kt`, `ui/nav/AppNav.kt`, `ui/nav/AccountSheet.kt`, `ui/nav/GlobalBanners.kt`, `AppContainer.kt`; Modify: `MainActivity.kt`, `GrimmdroidApp.kt`
- Test: `ui/nav/GlobalBannersTest.kt` (Robolectric Compose UI test)

**Interfaces:**
- Consumes: `AccountRepository`, `LibrarySync.state`, `SettingsStore` (theme, dynamic colour).
- Produces:
  - `@Composable fun GrimmdroidTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit)` — `dynamicLight/DarkColorScheme` on API 31+ when `dynamic`, else schemes generated from seed `#92400E` (hard-code the M3 tonal scheme generated once with Material Theme Builder; light background `#FFFBEB`).
  - Routes: `@Serializable object Onboarding`, `Home`, `Library`, `Shelves`, `Downloads`, `data class ShelfBooks(val shelfId: Long, val magic: Boolean, val name: String)`, `data class Details(val bookId: Long)`, `object Search`, `object Settings`.
  - `AppNav`: bottom bar with 4 tabs, each tab its own back stack (`saveState`/`restoreState`), predictive back enabled, start at `Onboarding` when no accounts.
  - `GlobalBanners(state: BannerState)` where `data class BannerState(val offline: Boolean, val syncing: SyncState.Running?, val signedOutServer: String?, val unsupportedVersion: String?)` renders: thin `LinearProgressIndicator` under the top bar when syncing; "Offline" chip; "Signed out of <server> · Sign in" banner; unsupported banner.
  - `AccountSheet`: list accounts (current checked, signed-out ones show status in error colour), "Add server", "Settings".
  - `AppContainer` constructs every object from Tasks 2–9 once.

- [ ] **Step 1: Write failing tests:** `signedOutServer = "books.example.net"` shows text "Signed out of books.example.net · Sign in" and its "Sign in" is clickable; `offline = true` shows "Offline"; `syncing` shows a progress indicator with content description "Syncing library".
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS; then `./gradlew installDebug` and check on a device/emulator that the empty shell shows the 4 tabs in light, dark and dynamic colour.
- [ ] **Step 5: Commit** `feat(ui): theme, navigation shell, account sheet, banners`.

### Task 11: Onboarding (server address, sign in, first sync)

**Files:**
- Create: `ui/onboarding/OnboardingViewModel.kt`, `ui/onboarding/ServerScreen.kt`, `ui/onboarding/SignInScreen.kt`; Modify: `res/xml/network_security_config.xml` (base config: cleartext permitted; trust anchors `system` + `user`)
- Test: `ui/onboarding/OnboardingViewModelSpec.kt`

**Interfaces:**
- Consumes: `ServerProbe`, `PasswordLogin`, `OidcLogin`, `AccountRepository`, `scheduleSync`.
- Produces: `OnboardingViewModel` with `state: StateFlow<OnboardingState>`; `sealed interface OnboardingState { EnterServer(error: String?) ; Probing ; CleartextWarning(probe) ; SignIn(probe, passwordAllowed: Boolean, ssoAllowed: Boolean, error: String?) ; SigningIn ; Done(accountId) }`.
  - `passwordAllowed = !settings.oidcForceOnlyMode`, `ssoAllowed = settings.oidcEnabled && oidcProviderDetails != null`.
  - Error copy: Unreachable "Can't reach this server"; NotGrimmory "This isn't a Grimmory server"; TlsUntrusted "This server's certificate isn't trusted. Install it in Android Settings › Security › Encryption & credentials, then try again."; Unsupported "This server version isn't supported: <version>"; BadCredentials "Wrong username or password"; RedirectRejected "Your server's admin needs to allow grimmory://oauth2-callback as a redirect URI".
  - `Done` ⇒ `scheduleSync(now = true)` and navigate to Home (no blocking loader). `isDefaultPassword == true` ⇒ one-time snackbar "You're using the default password. Change it in the Grimmory web UI."

- [ ] **Step 1: Write failing tests** (fakes for probe/login): each `ProbeResult` maps to the copy above; `http://` probe ⇒ `CleartextWarning` then `SignIn` after `acceptCleartext()`; OIDC-only server ⇒ `passwordAllowed == false`; successful login ⇒ `Done` and account stored + sync scheduled.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** screens per mockup "Sign in" (SSO button primary, divider "or", labelled fields, error under the field). **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(ui): onboarding and sign-in`.

### Task 12: Library, shelves and search screens

**Files:**
- Create: `ui/library/*`, `ui/shelves/*`, `ui/search/*`; Modify: `AppContainer.kt` (Coil `ImageLoader` with an OkHttp interceptor adding `Authorization: Bearer` for the current account's base URL, disk cache 250 MB)
- Test: `ui/library/LibraryViewModelSpec.kt`, `ui/search/SearchViewModelSpec.kt`

**Interfaces:**
- Consumes: `CatalogDao`, `BookQuery`, `BookSort`, `SettingsStore`.
- Produces:
  - `LibraryViewModel(scope: ShelfScope?)`: `query: StateFlow<BookQuery>`, `books: Flow<PagingData<BookRow>>`, `libraries: StateFlow<List<LibraryEntity>>`, `setLibrary(id: Long?)`, `setSort(BookSort)`, `setFormats(Set<String>)`, `setDownloadedOnly(Boolean)`, `layout: StateFlow<Layout>` (`GRID`, `LIST`, persisted).
  - `CoverGrid`: `LazyVerticalGrid(GridCells.Adaptive(110.dp))`, `key = book.id`, 2:3 cover with placeholder tile (colour from `title.hashCode()`, title text) until the thumbnail loads, badges (downloaded check, headphones for audiobook), title + author 1 line each; fast-scroll thumb with letter bubble when sort is TITLE/AUTHOR.
  - `ShelvesScreen`: sections "Shelves" and "Magic shelves" → `ShelfBooks` reusing `LibraryScreen` content with `ShelfScope`.
  - `SearchViewModel`: `query: MutableStateFlow<String>`, debounced 150 ms, `results` via `CatalogDao.search` with FTS prefix query built by `fun ftsQuery(input: String): String` (each token `"token"*`, quotes stripped).

- [ ] **Step 1: Write failing tests:** `setLibrary(3)` ⇒ `query.libraryId == 3`; shelf scope sets `shelfId`; `ftsQuery("moby di") == "\"moby\"* \"di\"*"`; `ftsQuery("\"*")` ⇒ empty ⇒ no results, no crash.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** per mockup "Library". **Step 4: Run** — PASS; on device with a 10k-book account confirm the grid scrolls without visible jank and recomposition counts stay flat while sync runs.
- [ ] **Step 5: Commit** `feat(ui): library, shelves, search`.

### Task 13: Home and Downloads screens

**Files:**
- Create: `ui/home/*`, `ui/downloads/*`
- Test: `ui/downloads/DownloadsViewModelSpec.kt`

**Interfaces:**
- Consumes: `CatalogDao.continueReading/recentlyAdded`, downloads query, `DownloadRepository.delete`, `FolderExport.save`.
- Produces: `HomeViewModel.rows: StateFlow<List<HomeRow>>` (`HomeRow(title, books, seeAll: Route)`; rows "Continue reading", "Recently added", "On this phone"; empty "On this phone" shows "Books you download appear here"); `DownloadsViewModel`: `items`, `totalBytes`, `selection: StateFlow<Set<Long>>`, `toggle(bookId)`, `deleteSelected()`, `saveSelected()`. Long-press starts selection; no swipe-to-delete.

- [ ] **Step 1: Write failing tests:** `totalBytes` sums `sizeBytes`; `deleteSelected` deletes exactly the selected files and clears selection.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** per mockup "Home". **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(ui): home and downloads`.

### Task 14: Book details

**Files:**
- Create: `ui/details/DetailsState.kt`, `ui/details/DetailsViewModel.kt`, `ui/details/DetailsScreen.kt`
- Test: `ui/details/DetailsStateSpec.kt`

**Interfaces:**
- Consumes: `CatalogDao.book`, `GrimmoryApi.book` (detail fetched when online, cached in memory), `AccountEntity.canDownload/isAdmin`, `DownloadRepository`, `Exporter`, `FolderExport`.
- Produces:
  - `sealed interface PrimaryAction { data object Download ; data class Downloading(val progress: Float) ; data class Downloaded(val file: File, val sizeLabel: String) ; data object NoPermission ; data object Audiobook ; data object Offline }`
  - `fun primaryAction(book: BookEntity, canDownload: Boolean, isAdmin: Boolean, download: DownloadEntity?, file: File?, online: Boolean): PrimaryAction` — order: audiobook ⇒ `Audiobook`; `!(canDownload || isAdmin)` ⇒ `NoPermission`; DONE and file exists ⇒ `Downloaded`; QUEUED/RUNNING ⇒ `Downloading`; offline ⇒ `Offline`; else `Download`.
  - Screen per mockup "Book details": top bar Back, **Share** (visible unless `NoPermission`/`Audiobook`; if not downloaded it enqueues the download and shares on completion), overflow (Delete from this phone, Open in web UI → `<baseUrl>/book/<id>` in a Custom Tab). Several files in `detail.files` ⇒ Download opens a picker listing `fileName · size`. Save to folder ⇒ snackbar "Saved to <folder> · Change". Copy per Global Constraints.

- [ ] **Step 1: Write failing tests:** table test over `primaryAction` covering all six outcomes and the precedence (audiobook without permission ⇒ `Audiobook`; DONE but file deleted from disk ⇒ `Download`).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(ui): book details with download and export actions`.

### Task 15: Settings

**Files:**
- Create: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`
- Test: `ui/settings/SettingsViewModelSpec.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `AccountRepository`, `LibrarySync`, Coil disk cache.
- Produces: sections per UI spec §7 and mockup "Settings — After download": per-account (server, user, last synced, "Sync now", Sign out → asks "Keep downloaded books?" Keep/Delete), Downloads ("After a download finishes" radio group, Folder + Change, Reader app is just informational: shows "Ask every time"), Theme (System/Light/Dark, Dynamic colour switch, hidden below API 31), Storage (books size, cover cache size, "Clear cover cache"), About (version, licences via a static list, source link).

- [ ] **Step 1: Write failing tests:** choosing "Save to my folder" persists `SAVE_TO_FOLDER`; sign out with Delete removes account rows, files and tokens; with Keep leaves files.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(ui): settings`.

### Task 16: Integration tests against a real Grimmory

**Files:**
- Create: `integration/docker-compose.yml` (from upstream `deploy/compose/docker-compose.yml`, image `ghcr.io/grimmory-tools/grimmory:v3.5.0`, MariaDB, `./fixtures` mounted at `/books`), `integration/fixtures/` (3 tiny public-domain EPUBs + 1 PDF generated by a script in `integration/make-fixtures.kts`), `app/src/integrationTest/kotlin/.../LiveServerTest.kt`, `.github/workflows/integration.yml`; Modify: `app/build.gradle.kts` (JVM-only `integrationTest` source set + task, excluded from `check`)
- Test: `LiveServerTest.kt`

**Interfaces:**
- Consumes: `ServerProbe`, `PasswordLogin`, `GrimmoryApi`, `parseContentDisposition` (pure JVM pieces only).

- [ ] **Step 1: Write the test:** wait for `/api/v1/healthcheck`; `POST /api/v1/setup` (`username, email, name, password`) if `/api/v1/setup/status` says not set up; create a library at `/books` via the libraries API and wait for 4 books; then assert: probe ⇒ `Ok`; login ⇒ `Ok` with `canDownload || isAdmin`; `books(0)` has 4 items and our DTOs decode (this verifies the `AppUserInfo` boolean key names and the audiobook `primaryFileType` value — fix the DTOs if they differ); `bookIds()` returns 4 ids; download of one EPUB returns non-empty bytes and a filename; OIDC `state` endpoint returns a state string.
- [ ] **Step 2: Run** `docker compose -f integration/docker-compose.yml up -d && ./gradlew integrationTest` — Expected: PASS. Then `docker compose down -v`.
- [ ] **Step 3: Add `integration.yml`** (runs on push to main and nightly; not a merge gate, because it pulls a large image).
- [ ] **Step 4: Commit** `test: integration tests against Grimmory v3.5.0`.

### Task 17: Release readiness

**Files:**
- Modify: `fastlane/metadata/android/en-US/*` (final texts, 2+ phone screenshots from the real app), `README.md` (what it is, supported servers: Grimmory with the mobile API, how SSO needs `grimmory://oauth2-callback` allowed by the admin, how to trust a self-signed certificate, building, licence), `app/build.gradle.kts` (`versionCode 1`, `versionName "1.0.0"`, R8 enabled with keep rules for kotlinx.serialization)
- Test: release build check

- [ ] **Step 1: Run** `./gradlew assembleRelease` with signing env vars set — Expected: signed APK; install it and run the manual checklist: add server (password and SSO), first sync of a large library, download, Open with…, Share, Save to folder, after-download "Save to my folder", sign-out banner after revoking the session in the web UI, dark mode, largest font size, TalkBack labels on icon buttons, landscape.
- [ ] **Step 2: Run** `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -iE 'gms|firebase|crashlytics'` — Expected: no output.
- [ ] **Step 3: Commit** `chore: release 1.0.0 readiness`.
