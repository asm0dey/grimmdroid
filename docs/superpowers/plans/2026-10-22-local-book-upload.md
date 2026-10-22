# Upload Local Books Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open an EPUB from any app (or an in-app picker), upload it to the user's Grimmory library, and open it in the reader once the server has it.

**Architecture:** A new `upload/` package. `BookUploader` holds the whole server-side flow (upload → find the book) as plain suspend code over injected lambdas, like `DetailsViewModel` does. `UploadViewModel` copies the incoming `content://` URI to a cache file, runs `BookUploader`, then reuses the existing path to read a book: `LibrarySync.sync` → `DownloadRepository.download` → `container.bookOpens.tryEmit(DetailsEvent.Read(...))`. Incoming intents reach `AppNav` through an `IncomingBooks` flow, the same way `OidcRedirects` does.

**Tech Stack:** Kotlin, Compose, Ktor client (multipart), DataStore, Room, Kotest + Ktor MockEngine + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-22-local-book-upload-design.md`

## Global Constraints

- Formats: EPUB only (`application/epub+zip`, `.epub`) — the reader opens only EPUB (`DetailsState.kt:70`).
- Server route: `POST api/v1/files/upload`, multipart `file`, `libraryId`, `pathId`; 204, no body. Never BookDrop.
- Upload goes to the **current** account; the remembered target is stored **per account**.
- Duplicates: upload anyway, then open the existing book.
- Never an endless wait: watched library 30 s, unwatched-after-refresh 180 s, poll every 2 s; then "not picked up yet".
- Kotest `FunSpec` for tests; fakes at the HTTP boundary via `MockEngine` that `error()`s on unexpected requests (see `api/ReaderApiSpec.kt`). No test-only interfaces.
- Each new file starts with the GPL header used in every source file.

## Review Focus

1. **A file whose upload succeeds but the server renames it** (naming pattern) — matching must not depend on the file name. Covered: matching is by new book id / EPUB title (Task 3 tests).
2. **Duplicate whose upload fails with 500** (server's `Files.move` refuses an existing target path → `FILE_READ_ERROR`). Expected: open the existing book, not an error. Test in Task 3.
3. **Share intent from a messenger with a non-EPUB or `octet-stream` file** — expected: "Only EPUB books can be uploaded", no request sent. Test in Task 4.
4. **Intent arrives before any account is signed in** — expected: the upload starts after sign-in, not lost. Handled by `IncomingBooks` being a replaying `StateFlow` consumed only when an account exists (Task 5, manual check).
5. **EPUB with no or broken OPF title** — expected: upload still works; only the duplicate shortcut is skipped. Test in Task 2.

---

### Task 1: Server API and catalog lookup

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/api/Dto.kt` (`LibraryDto`)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/api/GrimmoryApi.kt`
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDao.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/api/UploadApiSpec.kt` (create), `.../catalog/CatalogDaoTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class LibraryPathDto(val id: Long, val path: String? = null)`
  - `LibraryDto` gains `val watch: Boolean = false, val paths: List<LibraryPathDto> = emptyList()`
  - `class NoUploadPermission : IOException("Your account can't upload books")` (in `GrimmoryApi.kt`)
  - `suspend fun GrimmoryApi.upload(file: File, fileName: String, libraryId: Long, pathId: Long, onProgress: (Float) -> Unit)` — throws `NoUploadPermission` on 403
  - `suspend fun GrimmoryApi.refreshLibrary(libraryId: Long)` — `PUT api/v1/libraries/{id}/refresh`; swallows 403 (user can't manage libraries)
  - `CatalogDao.bookByTitle(accountId: Long, title: String): BookEntity?` — `title = :title COLLATE NOCASE`, `LIMIT 1`

- [ ] **Step 1: Write failing tests in `UploadApiSpec`** (helper `api(method, path, respond)` copied from `ReaderApiSpec`):
  - `upload posts multipart file, libraryId and pathId` — POST `/api/v1/files/upload` answering 204; assert the body bytes contain `name="libraryId"` + `3`, `name="pathId"` + `9`, `filename="b.epub"` and the file's content `"EPUBDATA"`.
  - `upload maps 403 to NoUploadPermission` — `shouldThrow<NoUploadPermission>`.
  - `refreshLibrary ignores 403` — PUT `/api/v1/libraries/3/refresh` answering 403; no throw.
  - `libraries reads watch and paths` — GET `/api/v1/app/libraries` answering `[{"id":3,"name":"Books","watch":true,"paths":[{"id":9,"path":"/b"}]}]`; `watch shouldBe true`, `paths.single().id shouldBe 9`.
- [ ] **Step 2: Add `bookByTitle finds a book case-insensitively and only for that account`** to `CatalogDaoTest` (follow that file's setup).
- [ ] **Step 3: Run** `./gradlew :app:testDebugUnitTest --tests '*UploadApiSpec' --tests '*CatalogDaoTest'` — expect FAIL (unresolved references).
- [ ] **Step 4: Implement** the Produces list. `upload` uses `client.submitFormWithBinaryData("api/v1/files/upload", formData { append("libraryId", …); append("pathId", …); append("file", ChannelProvider(file.length()) { file.readChannel() }, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$fileName\""); append(HttpHeaders.ContentType, "application/epub+zip") }) }) { onUpload { sent, total -> … } }`. Map 403 like `customFonts()` does.
- [ ] **Step 5: Run the same command** — expect PASS.
- [ ] **Step 6: Commit** `feat(upload): upload and library refresh API`

### Task 2: EPUB title

**Files:**
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/upload/EpubTitle.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/upload/EpubTitleSpec.kt`

**Interfaces:**
- Produces: `fun epubTitle(file: File): String?` — `META-INF/container.xml` → first `rootfile@full-path` → OPF's first `dc:title`, trimmed; `null` on any missing entry, parse error or blank title (never throws for a bad file).

- [ ] **Step 1: Write failing tests**, building tiny EPUB zips in a temp dir with `ZipOutputStream`:
  - `reads dc:title from the OPF named by container.xml` → `"Dune"` (OPF at `OEBPS/content.opf`).
  - `null when container.xml is missing`.
  - `null for a non-zip file`.
  - `null for a blank title`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*EpubTitleSpec'` — FAIL.
- [ ] **Step 3: Implement** with `java.util.zip.ZipFile` + a namespace-aware `DocumentBuilderFactory`, as `reader/cfi/EpubArchive.kt` parses XML.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(upload): read an EPUB's title`

### Task 3: Upload flow (`BookUploader`)

**Files:**
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/upload/BookUploader.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/upload/BookUploaderSpec.kt`

**Interfaces:**
- Consumes: Task 1 API, `epubTitle` (Task 2).
- Produces:
  ```kotlin
  data class UploadTarget(val libraryId: Long, val pathId: Long, val watched: Boolean)
  sealed interface UploadResult {
      data class Found(val bookId: Long) : UploadResult
      data object NotPickedUp : UploadResult
      data object NoPermission : UploadResult
      data class Failed(val error: Throwable) : UploadResult
  }
  class BookUploader(
      private val api: GrimmoryApi,
      private val existingBookId: suspend (title: String) -> Long?,
  ) {
      suspend fun upload(file: File, fileName: String, target: UploadTarget, onProgress: (Float) -> Unit): UploadResult
  }
  ```

Algorithm (not determined by the signature):
1. `existing = epubTitle(file)?.let { existingBookId(it) }`; `before = api.bookIds().toSet()`.
2. `api.upload(...)`. `NoUploadPermission` → `NoPermission`. `CancellationException` rethrown. Any other exception → `Found(existing)` if `existing != null`, else `Failed(e)`.
3. Success and `existing != null` → `Found(existing)` (duplicate: the server re-points that book).
4. If `!target.watched` → `api.refreshLibrary(target.libraryId)`; timeout = 180 s, else 30 s.
5. Every 2 s until timeout: `new = api.bookIds() - before`; non-empty → `Found(new.max())` (`// shortcut: several new books at once → newest id wins; match by title if that bites`).
6. Timeout → `NotPickedUp`.

- [ ] **Step 1: Write failing tests** with `runTest` (virtual time) and a MockEngine routing by method+path, failing on anything else:
  - `new book appears after two polls → Found(new id)`; assert no `refresh` call when watched.
  - `unwatched library calls refresh and waits up to 180 s` — book appears at 150 s → `Found`.
  - `watched library gives up after 30 s → NotPickedUp`.
  - `known title → Found(existing) right after upload, no polling` (assert `bookIds` called once).
  - `upload 500 with known title → Found(existing)` (Review Focus 2).
  - `upload 500 with unknown title → Failed`.
  - `403 → NoPermission`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*BookUploaderSpec'` — FAIL.
- [ ] **Step 3: Implement** `BookUploader`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(upload): upload a book and find it on the server`

### Task 4: Target choice, settings and `UploadViewModel`

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/settings/SettingsStore.kt`
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/upload/UploadViewModel.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/upload/UploadViewModelSpec.kt`

**Interfaces:**
- Consumes: `BookUploader`, `UploadTarget`, `UploadResult` (Task 3); `GrimmoryApi.libraries()`.
- Produces:
  - `SettingsStore.uploadTarget(accountId: Long): Flow<Pair<Long, Long>?>` and `setUploadTarget(accountId: Long, target: Pair<Long, Long>?)` — key `upload_target_$accountId`, stored as `"libraryId:pathId"`, same pattern as `folderUri`.
  - `data class TargetOption(val libraryId: Long, val pathId: Long, val label: String, val watched: Boolean)` — one per library × path; label `"Library"` when the library has one path, else `"Library — /path"`.
  - `sealed interface UploadState { Preparing; ChooseTarget(options: List<TargetOption>); Uploading(progress: Float); Waiting; NotPickedUp; NoPermission; NotEpub; Failed(message: String); Opening }`
  - `class UploadViewModel(uri: Uri, accountId: Long, …lambdas…) : ViewModel` with `val state: StateFlow<UploadState>` and `fun choose(option: TargetOption)`. Inject as lambdas: copy-URI-to-cache `(Uri) -> Pair<File, String>?` (null = unreadable), `libraries`, saved target get/set, `upload` (BookUploader), and `open: suspend (bookId: Long) -> Unit` (Task 5 wires it to sync → download → `bookOpens`).

Flow: copy → if the display name doesn't end with `.epub` (ignore case) → `NotEpub`, no network. Load libraries → saved target still present → use it; else exactly one option → use and save it; else `ChooseTarget`; `choose()` saves and continues. `Uploading` → `Waiting` (after bytes are sent) → on `Found` → `Opening`, call `open`. Cache file deleted when the ViewModel clears.

- [ ] **Step 1: Write failing tests** (fake lambdas; `Dispatchers.setMain` as in `DetailsViewModelSpec`):
  - `non-epub name → NotEpub and no upload` (Review Focus 3).
  - `single library with single path is used and remembered`.
  - `two options without a saved one → ChooseTarget; choose() saves and uploads`.
  - `saved target that no longer exists → ChooseTarget`.
  - `Found → open(bookId) called, state Opening`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*UploadViewModelSpec'` — FAIL.
- [ ] **Step 3: Implement** the settings key and `UploadViewModel`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(upload): choose a target library and drive the upload`

### Task 5: Wiring — intents, screen, entry points

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (`MainActivity`)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/MainActivity.kt`
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/upload/UploadScreen.kt` (incl. `object IncomingBooks`)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/Routes.kt`, `ui/nav/AppNav.kt`, `ui/library/LibraryScreen.kt`, `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `UploadViewModel`, `UploadState`, `TargetOption` (Task 4); `container.librarySync.sync`, `container.downloadRepository.download`, `container.bookOpens`.
- Produces: `object IncomingBooks { val incoming = MutableStateFlow<Uri?>(null) }`; route `@Serializable data class Upload(val uri: String)`.

- [ ] **Step 1: Manifest** — on `MainActivity` add intent filters: `VIEW` (`DEFAULT`, `BROWSABLE`) with `mimeType="application/epub+zip"` for `content` and `file` schemes; `SEND` (`DEFAULT`) with the same mime type.
- [ ] **Step 2: `MainActivity`** — in `onCreate` (when `savedInstanceState == null`) and `onNewIntent`, put `intent.data` (VIEW) or `EXTRA_STREAM` (SEND) into `IncomingBooks.incoming`.
- [ ] **Step 3: `AppNav`** — when `IncomingBooks.incoming` and the current account are both non-null, navigate to `Upload(uri)` and set `incoming` back to `null` (a signed-out user keeps it until sign-in; Review Focus 4).
- [ ] **Step 4: `UploadScreen`** — a full-screen route showing the account's server (`account.baseUrl`) and one view per `UploadState`; `ChooseTarget` is a list of `TargetOption.label`; `open` = `librarySync.sync(accountId)`, `downloadRepository.download(accountId, bookId, null, true) {}`, `bookOpens.tryEmit(DetailsEvent.Read(accountId, bookId, file))`, then pop the route.
- [ ] **Step 5: Entry points** — Library screen top bar: an "Upload book" action launching `ActivityResultContracts.OpenDocument()` with `arrayOf("application/epub+zip")`, result → `IncomingBooks.incoming`. Settings: an "Upload to" row showing the saved target's label, tapping clears it so the next upload asks again.
- [ ] **Step 6: Strings** (exact copy):
  - `upload_book` "Upload book"; `upload_to` "Upload to"; `upload_to_unset` "Ask on next upload"
  - `upload_progress` "Uploading to %1$s…"; `upload_waiting` "Waiting for the server to add it…"
  - `upload_not_picked_up` "Uploaded. The server hasn't picked it up yet; it'll appear in your library once it does."
  - `upload_no_permission` "Your account can't upload books"
  - `upload_not_epub` "Only EPUB books can be uploaded"
  - `upload_failed` "Upload failed: %1$s"
- [ ] **Step 7: Verify** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — all green. Then on a device against the integration server (`integration/docker-compose.yml`): share an EPUB from Files → it uploads and opens in the reader; share the same EPUB again → opens the existing book; share a PDF → Grimmdroid isn't offered.
- [ ] **Step 8: Commit** `feat(upload): open and share EPUBs into Grimmdroid`
