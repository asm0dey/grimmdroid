# Authors and Series Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author pages, series pages and browsable Authors/Series lists that show what you've read, what you're reading, and what to read next. All of it comes from the local catalog cache.

**Architecture:** One pure Kotlin module (`catalog/ReadState.kt`) decides each book's read state, the next book to read, and how books are grouped. The Room catalog gains a `readStatus` column and a `BookAuthor` table, both filled by sync. DAO queries return book rows. ViewModels group and classify those rows in Kotlin through `ReadState.kt`, so SQL never duplicates the rule. New Compose screens follow the existing Library and Details patterns.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Navigation Compose (type-safe routes), Room (AutoMigration, exported schemas in `app/schemas/`), Paging, WorkManager, Kotest `FunSpec` and JUnit4/Robolectric tests.

**Spec:** `docs/superpowers/specs/2026-10-21-authors-series-design.md`

## Global Constraints

- Read: `readStatus == "READ"` or `readProgress >= 99`. Dismissed: `ABANDONED`/`WONT_READ` (and not Read). In progress: `READING`/`RE_READING`/`PARTIALLY_READ`/`PAUSED` or `readProgress > 0`. Not started: everything else, including unknown status strings.
- `readProgress` is a 0–100 percentage everywhere in `BookEntity`.
- Series grouping is by exact `seriesName`. Order is `seriesNumber` ascending. Unnumbered books come after the numbered ones, then by title (`COLLATE NOCASE` semantics: compare with `String.CASE_INSENSITIVE_ORDER`), then by id.
- Counts ("N of M read") count only Read books.
- The library filter only chooses which authors and series are listed. Counts and pages always cover the whole account.
- Every new route carries `accountId`, the same way `Details` does.
- Every new source file starts with the project's GPL header (copy it from any existing file).
- Gate: `./gradlew spotlessCheck lint testDebugUnitTest` (the same as `bundle exec fastlane test`).

## Review Focus

1. Author names with commas ("Tolkien, J.R.R."): the author page must find the book under the full name. Pinned in Task 3.
2. A book with no authors: no blank author entry in the Authors list. Pinned in Task 4.
3. A book removed from the server: its `BookAuthor` rows go with it, so author lists never show ghost books. Pinned in Task 3.
4. A series where every book is Read or Dismissed: nothing is highlighted, and no crash. Pinned in Task 1.
5. A book finished in the reader while offline: the series page moves on right away. Pinned in Task 5.

---

### Task 1: Read state, next-to-read and grouping (pure logic)

**Files:**
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/ReadState.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/catalog/ReadStateSpec.kt` (Kotest `FunSpec`, like `SmokeSpec.kt`)

**Interfaces:**
- Produces:
  - `enum class ReadState { READ, IN_PROGRESS, NOT_STARTED, DISMISSED }`
  - `fun readState(readStatus: String?, readProgress: Float?): ReadState`
  - `val BookEntity.readState: ReadState` (delegates to the above)
  - `val SeriesOrder: Comparator<BookEntity>` (number asc, nulls last, then title case-insensitive, then id)
  - `fun nextToRead(series: List<BookEntity>): BookEntity?` (input in any order; it sorts by `SeriesOrder`)
  - `data class Group(val name: String, val books: List<BookEntity>, val readCount: Int, val total: Int, val started: Boolean)`, where `started` = any book is READ or IN_PROGRESS
  - `fun List<BookEntity>.toGroup(name: String): Group`

- [ ] **Step 1: Write the failing tests** in `ReadStateSpec`. Helper: `b(id, no: Float?, status: String? = null, progress: Float? = null, title = "T$id")`, which builds a `BookEntity` with `seriesName = "S"`.
  - `readState("READ", 0f) == READ`; `readState(null, 99f) == READ`; `readState(null, 98.9f) == IN_PROGRESS`; `readState("WONT_READ", 50f) == DISMISSED`; `readState("ABANDONED", 99f) == READ`; `readState("PAUSED", null) == IN_PROGRESS`; `readState("UNSET", 0f) == NOT_STARTED`; `readState("SOMETHING_NEW", null) == NOT_STARTED`; `readState(null, null) == NOT_STARTED`.
  - Order: books numbered `[3, null "A", 1, null "b", 2]` sort as `1, 2, 3, A, b`.
  - next: `[1 READ, 2 none, 3 DISMISSED, 4 none]` → 4 (Dismissed counts as done; the last done numbered book is 3).
  - next: `[1 READ, 2 none, 3 READING]` → 3 (In progress wins).
  - next: `[1 none, 2 READ, 3 none]` → 3.
  - next: `[1 READ, 2 READ, unnumbered none]` → the unnumbered one (the fallback: first Not started).
  - next: `[1 READ, unnumbered READ, 2 none]` → 2 (an unnumbered Read book doesn't count as "last").
  - next: `[1 READ, 2 DISMISSED]` → `null`.
  - `toGroup`: `[READ, DISMISSED, none]` → `readCount = 1, total = 3, started = true`; `[DISMISSED, none]` → `started = false`.

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ReadStateSpec'`. Expected: compilation FAIL (unresolved `readState`).

- [ ] **Step 3: Implement** `ReadState.kt` to the interfaces above. `nextToRead` follows the spec rule: the first IN_PROGRESS book in `SeriesOrder`. Otherwise, the first NOT_STARTED book after the highest `seriesNumber` among numbered READ/DISMISSED books. Otherwise, the first NOT_STARTED book. Otherwise `null`.

- [ ] **Step 4: Run** the same command. Expected: PASS.

- [ ] **Step 5: Commit** `feat(catalog): read state and next-to-read rule`.

### Task 2: Schema v3 — readStatus column and BookAuthor table

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/Entities.kt` (add `readStatus: String? = null` to `BookEntity` after `readProgress`; add `BookAuthor`)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDb.kt` (entities += `BookAuthor::class`; `version = 3`; add `AutoMigration(from = 2, to = 3)`)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDao.kt` (`downloads()` lists every `b_` column explicitly; add `b.readStatus AS b_readStatus`)
- Generated: `app/schemas/io.github.asm0dey.grimmdroid.catalog.CatalogDb/3.json` (produced by the build; commit it)
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogMigrationTest.kt` (copy the `MigrationTestHelper` setup from `reader/ReaderMigrationTest.kt`)

**Interfaces:**
- Produces:
  - `@Entity(primaryKeys = ["accountId", "bookId", "name"], indices = [Index("accountId", "name")]) data class BookAuthor(val accountId: Long, val bookId: Long, val name: String)`
  - `BookEntity.readStatus: String?`

- [ ] **Step 1: Write the failing test** `v2ToV3KeepsBooksAndAddsAuthors`. Create v2 and insert a `BookEntity` row (same columns as the v1 test). Then run `runMigrationsAndValidate(3, emptyList())` and assert: the title is kept, `readStatus` is NULL, and `BookAuthor` has a count of 0.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CatalogMigrationTest'`. Expected: FAIL (there's no schema 3).
- [ ] **Step 3: Make the entity, DB and `downloads()` changes above.** If the existing `CatalogDaoTest.book(...)` helper or other positional `BookEntity(...)` calls break, give them the new argument.
- [ ] **Step 4: Run** the full `./gradlew testDebugUnitTest`. Expected: PASS, and `3.json` exists.
- [ ] **Step 5: Commit** `feat(catalog): schema v3 with readStatus and BookAuthor`, including `3.json`.

### Task 3: Sync stores readStatus and author rows

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/sync/LibrarySync.kt` (`toEntity()` sets `readStatus`; each page also writes authors)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDao.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/sync/LibrarySyncTest.kt`, `app/src/test/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDaoTest.kt`

**Interfaces:**
- Consumes: `BookAuthor`, `BookEntity.readStatus` (Task 2)
- Produces (CatalogDao):
  - `@Transaction open suspend fun upsertBooksWithAuthors(books: List<BookEntity>, authors: Map<Long, List<String>>)`: upserts the books, deletes `BookAuthor` rows for those book ids, then inserts the given names (trimmed, blanks and duplicates dropped). Sync uses this in place of `upsertBooks`.
  - `deleteBooksNotIn` and `deleteAccount` also delete `BookAuthor` rows whose book no longer exists (`deleteAccount`: all rows for the account).

- [ ] **Step 1: Write the failing tests.**
  - `LibrarySyncTest`: a fake page with `BookSummaryDto(id = 1, authors = listOf("Tolkien, J.R.R.", "Tolkien, Christopher"), readStatus = "READ")` → `BookEntity.readStatus == "READ"`, and `BookAuthor` names for book 1 are exactly those two strings.
  - `CatalogDaoTest`: authors `["A", " ", "A"]` → one row, "A". Re-upserting a book with authors `["B"]` replaces "A". A book dropped by `deleteBooksNotIn` leaves no `BookAuthor` rows. `deleteAccount(1)` clears account 1's rows and keeps account 2's.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*LibrarySyncTest' --tests '*CatalogDaoTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the DAO methods and switch `LibrarySync` to `upsertBooksWithAuthors(res.content.map { it.toEntity(id, generation) }, res.content.associate { it.id to it.authors })`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(sync): keep read status and author rows`.

### Task 4: Author and series queries

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDao.kt`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDaoTest.kt`

**Interfaces:**
- Produces (CatalogDao; all `Flow`, so screens update when sync or the reader writes):
  - `fun seriesBooks(accountId: Long, name: String): Flow<List<BookEntity>>`: exact `seriesName` match, all libraries.
  - `fun authorBooks(accountId: Long, name: String): Flow<List<BookEntity>>`: books joined through `BookAuthor` by exact name.
  - `fun seriesBooksAll(accountId: Long): Flow<List<BookEntity>>`: every book with a non-null `seriesName`.
  - `fun authorBookPairs(accountId: Long): Flow<List<AuthorBook>>`, with `data class AuthorBook(val name: String, @Embedded val book: BookEntity)` declared in `Entities.kt`.
  - `fun namesInLibrary(...)` is **not** added. The ViewModel applies the library filter itself: a group is listed when any of its books has that `libraryId`.

- [ ] **Step 1: Write the failing tests.** `seriesBooks` returns books from two libraries and excludes "Dune " (with a trailing space) when asked for "Dune". `authorBooks(1, "Tolkien, J.R.R.")` finds the book. `authorBookPairs` returns one pair per (author, book), and a book with no authors produces no pair. Account isolation: account 2's books never show up.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CatalogDaoTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the queries.
- [ ] **Step 4: Run** it again. Expected: PASS.
- [ ] **Step 5: Commit** `feat(catalog): author and series queries`.

### Task 5: Reader progress updates the cache

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/reader/ReaderDao.kt`
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/reader/PositionSync.kt` (`saveLocal`)
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/reader/ReaderDaoTest.kt`

**Interfaces:**
- Produces: `@Query("UPDATE BookEntity SET readProgress = :percent, lastReadTime = :at WHERE accountId = :accountId AND id = :bookId") abstract suspend fun setBookProgress(accountId: Long, bookId: Long, percent: Float, at: Long)` in `ReaderDao`. `ReaderDao.savePosition` is already a transactional `open` function; have it also call `setBookProgress(p.accountId, p.bookId, (p.totalProgression * 100).toFloat(), p.updatedAt)`, so `saveLocal` stays unchanged.

- [ ] **Step 1: Write the failing test** `savingPositionUpdatesBookProgress`. Insert a `BookEntity` with `readProgress = 10f`, then call `savePosition` with `totalProgression = 0.995`. Assert `readProgress == 99.5f` and that `readState` is `READ`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ReaderDaoTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** it.
- [ ] **Step 4: Run** it again. Expected: PASS.
- [ ] **Step 5: Commit** `feat(reader): reading progress updates the local catalog`.

### Task 6: Series and Author screens

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/Routes.kt`
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/group/GroupViewModel.kt`, `.../ui/group/GroupScreen.kt`
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/AppNav.kt` (two `composable<>` entries, opening books through `Details(id, route.accountId)`)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/ui/group/GroupViewModelSpec.kt`

**Interfaces:**
- Consumes: `seriesBooks`, `authorBooks` (Task 4); `toGroup`, `nextToRead`, `SeriesOrder`, `readState` (Task 1)
- Produces:
  - Routes: `@Serializable data class Series(val name: String, val accountId: Long)`, `@Serializable data class Author(val name: String, val accountId: Long)`
  - `data class GroupPage(val title: String, val readCount: Int, val total: Int, val sections: List<Section>)`, `data class Section(val seriesName: String?, val books: List<BookEntity>, val next: BookEntity?)`
  - `class GroupViewModel(books: Flow<List<BookEntity>>, title: String, byAuthor: Boolean) : ViewModel` with `val page: StateFlow<GroupPage?>` and a `factory(container, accountId, name, byAuthor)` built like `LibraryViewModel.factory`.
  - For the series page: one `Section(name, sorted, nextToRead)`. For the author page: one section per series, ordered by series name case-insensitively, each with its own `next`. Then a final `Section(null, rest sorted by title, next = null)` if any books are outside a series.

- [ ] **Step 1: Write the failing tests** (`GroupViewModelSpec`, with a `MutableStateFlow` source):
  - Series: books `[2 none, 1 READ]` → one section in order 1, 2, with `next` = book 2 and `readCount = 1, total = 2`.
  - Author: books in series "B", in series "a", and with no series → section order "a", "B", null.
  - Updating the source flow (book 2 becomes READ) updates `page` (next = null).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*GroupViewModelSpec'`. Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModel. Then build `GroupScreen(vm, baseUrl, onBook, onBack)`: a top app bar with the title and "N of M read" (a plural string), section headers on author pages, and rows built from the existing `BookListRow`/`Cover` in `ui/library/`. Each row shows the series number, a read-state marker and a "next" highlight; their look is up to the builder, but they should match Material 3. Wire both routes in `AppNav`.
- [ ] **Step 4: Run** `./gradlew spotlessCheck lint testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): series and author pages`.

### Task 7: Details links to Author and Series

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/details/DetailsScreen.kt` (`Header`, around lines 317–335; `DetailsScreen` signature)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/AppNav.kt` (`composable<Details>`)
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/ui/details/` (an existing Compose test file there; add the case)

**Interfaces:**
- Consumes: the `Author` and `Series` routes (Task 6)
- Produces: `DetailsScreen(..., onAuthor: (String) -> Unit, onSeries: (String) -> Unit, onBack)`. Author names come from `vm` (`BookAuthor` rows for the book via a new `CatalogDao.bookAuthors(accountId, bookId): Flow<List<String>>`, ordered by rowid), not by splitting `BookEntity.authors`.

- [ ] **Step 1: Write the failing test.** Render Details for a book with authors "Tolkien, J.R.R." and "X", and series "LotR". Clicking the text "Tolkien, J.R.R." calls `onAuthor("Tolkien, J.R.R.")`; clicking the series line calls `onSeries("LotR")`.
- [ ] **Step 2: Run** the test. Expected: FAIL.
- [ ] **Step 3: Implement** it. Each author is its own clickable `Text` with `Role.Button` (in a `FlowRow` or separated by ", "), and so is the series line.
- [ ] **Step 4: Run** `./gradlew spotlessCheck lint testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(details): open author and series pages`.

### Task 8: Library Books / Authors / Series switch

**Files:**
- Create: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/library/GroupsViewModel.kt`, `.../ui/library/GroupsList.kt`
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/library/LibraryScreen.kt` (`LibraryContent`: a switch above the content, only on the Library route, not ShelfBooks)
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/AppNav.kt` (`composable<Library>` passes `onAuthor` and `onSeries`)
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/ui/library/GroupsViewModelSpec.kt`

**Interfaces:**
- Consumes: `seriesBooksAll`, `authorBookPairs` (Task 4); `toGroup` (Task 1)
- Produces:
  - `enum class LibraryMode { BOOKS, AUTHORS, SERIES }`
  - `class GroupsViewModel(seriesBooks: Flow<List<BookEntity>>, authorPairs: Flow<List<AuthorBook>>)`, with `val mode`, `val notStartedOnly`, `val libraryId: MutableStateFlow<Long?>` (kept in sync with `LibraryViewModel.query.libraryId`), and `val groups: StateFlow<List<Group>>`, sorted by name case-insensitively.
  - The filter: with a library selected, keep groups where any book has that `libraryId`. Counts still come from all the group's books. With `notStartedOnly`, keep only groups where `!started`.

- [ ] **Step 1: Write the failing tests.** The series "S" has book 1 (lib 1, READ) and book 2 (lib 2). With `libraryId = 2`, "S" is listed with `readCount = 1, total = 2`. With `notStartedOnly`, "S" is hidden, and a series with only Dismissed and unread books is shown. In AUTHORS mode, the groups come from pairs and use the full names.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*GroupsViewModelSpec'`. Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModel and `GroupsList` (each row shows the name, "N of M read" and a book count; tapping opens the page). Add the switch to `LibraryContent`; the control is up to the builder (a segmented button is suggested). In AUTHORS and SERIES modes, hide the sort, format and downloaded filters and show a "Not started" `FilterChip`. Keep the library chips. Persist the mode with `rememberSaveable`.
- [ ] **Step 4: Run** `./gradlew spotlessCheck lint testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(library): browse by author and series`.

### Task 9: Backfill sync after upgrade

**Files:**
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDao.kt`
- Modify: `app/src/main/kotlin/io/github/asm0dey/grimmdroid/ui/nav/AppNav.kt` (a `LaunchedEffect(current?.id)`)
- Test: `app/src/test/kotlin/io/github/asm0dey/grimmdroid/catalog/CatalogDaoTest.kt`

**Interfaces:**
- Produces: `@Query("SELECT EXISTS(SELECT 1 FROM BookEntity b WHERE b.accountId = :accountId AND b.authors != '' AND NOT EXISTS (SELECT 1 FROM BookAuthor a WHERE a.accountId = b.accountId AND a.bookId = b.id))") abstract suspend fun needsAuthorBackfill(accountId: Long): Boolean`

- [ ] **Step 1: Write the failing test.** A book with authors "A" and no `BookAuthor` rows → `true`. After `upsertBooksWithAuthors` → `false`. A book with an empty authors string → `false`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CatalogDaoTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** it. In `AppNav`, when the current account is signed in and `needsAuthorBackfill` is true, call `scheduleSync(context, id, now = true)`.
- [ ] **Step 4: Run** `./gradlew spotlessCheck lint testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(sync): backfill authors and read status after upgrade`.
