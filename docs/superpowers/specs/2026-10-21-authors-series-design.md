# Authors and series

## Why

The reader uses Grimmdroid to answer three questions:

1. What's the next book in a series I'm reading?
2. Which series haven't I started yet?
3. Which books by this author haven't I read yet?

Today the app only shows authors and series as text on a book, and as Library sort orders. There's no way to open a series or an author, and no way to see what you have or haven't read.

## Goals

- **Series page.** Every book in the series, in series-number order. Each book is clearly marked as read, in progress, or not started, and the next book to read stands out.
- **Author page.** Every book by that author, with the same markers. Books in a series are grouped under the series name, in series order. Books outside any series come after them.
- **Browsing.** Lists of all authors and all series, filterable to the ones you haven't started.
- **Ways in.** On a book's Details page, the author names and the series name can be tapped.
- **Works offline.** Everything is built from the local catalog cache that sync already fills.

## Non-goals

- Author photos, bios or ASINs (Grimmory's `/api/v1/authors`). Could come later.
- Changing a book's read status from the app.
- Grimmory's server-side facet API (`/api/v1/books/page?facet=…`). It isn't in any release yet (it came after v3.3.1).
- Showing gaps in a series that the library doesn't have (`seriesTotal`, missing numbers).

## Anti-goals

- A finished book showing up as "next" or "not started". That's the failure that matters most.
- New screens that look out of place next to Library and Details.

## Read state (decided with the user)

Every book is in exactly one of these states. The state comes from the book's Grimmory `readStatus` and `readProgress` (a 0–100 percentage, as the app already uses it):

| State | Rule |
|---|---|
| **Read** | `readStatus == READ`, or `readProgress >= 99` |
| **Dismissed** | `readStatus` is `ABANDONED` or `WONT_READ` (and the book isn't Read) |
| **In progress** | not Read or Dismissed, and either `readStatus` is `READING`, `RE_READING`, `PARTIALLY_READ` or `PAUSED`, or `readProgress > 0` |
| **Not started** | everything else (`UNREAD`, `UNSET`, null, no progress) |

Read and In progress each get a clear visual marker. *(My call: Dismissed books count as "done" for working out what's next, and get a quiet marker of their own. The user didn't object.)*

**Order within a series:** by `seriesNumber` ascending. Books without a number come after all numbered books, sorted by title. *(My call.)*

**Next to read** in a series: the first In-progress book in series order if there is one. Otherwise, the first Not-started book that comes after the last numbered book that is Read or Dismissed. Otherwise, the first Not-started book. If there's nothing left, nothing is highlighted. Unnumbered books never count as "the last Read" book. *(My call: Dismissed counts as done here, the same as Read.)*

**Counts** ("3 of 7 read", the read count in lists) count only Read books. Dismissed books are not counted as read. *(My call.)*

**A series or author "not started"**: none of its books are Read or In progress.

**Progress made in this app** must update the local cache right away, not wait for the next sync. When the reader records a position, it also writes `readProgress` (and `lastReadTime`) to the book's `BookEntity`. That way a book finished on the phone immediately counts as Read, and the series moves on to the next one. *(My call. Otherwise a book you just finished would keep showing as "next", which breaks the first anti-goal.)*

## Data changes

- Store `readStatus` on `BookEntity`. Its values are Grimmory's `ReadStatus` enum names (`backend/.../model/enums/ReadStatus.java`). Treat unknown values as Not started. `LibrarySync.toEntity()` currently drops it. Bump `CatalogDb` from version 2 to 3. The existing `exportSchema = true` / AutoMigration setup should be able to handle a new nullable column.
- `BookEntity.authors` is a single string joined with ", ", so it can't be split back reliably: author names like "Tolkien, J.R.R." contain commas. Add a cross-ref table `BookAuthor(accountId, bookId, name)`, filled during sync from `BookSummaryDto.authors` and cleaned up together with books. Author pages and the author list query this table. The joined string stays as it is, for display and full-text search.
- Series are grouped on `seriesName`, matched exactly. That's how Grimmory's own web UI builds its series list. Books with the same `seriesNumber` (or none) are sorted by title.
- Read state is worked out in one place: a pure function that the tests cover. Any SQL used for filtering has to match that function.
- After the upgrade, existing installs have no `readStatus` and no `BookAuthor` rows until the next sync. *(My call: the next normal sync fills them in, and right after the migration, a sync is forced for the current account so the new screens aren't empty. Other accounts fill in on their next normal sync.)*

## Screens

Matching the existing app: Compose with Material 3, type-safe Navigation routes, ViewModels reading from Room, and manual DI through `AppContainer`.

- **Routes:** add `Author(name, accountId)` and `Series(name, accountId)`. These follow the same per-account rule as `Details`.
- **Series screen:** the series name and a count ("3 of 7 read"), then the book list in series order. Each row shows the series number, cover, title, and a read-state marker. The next-to-read row is highlighted. Tapping a book opens Details.
- **Author screen:** the author name and a count, then series groups (each a header plus its books in order), then the books outside any series, sorted by title. It uses the same row and markers as the Series screen. Each series group highlights its own next-to-read book. *(My call.)*
- **Library:** *(my call, approved by the user)* add a way to switch between Books, Authors and Series. The Authors and Series lists show the name, book count and read count. Each has a "Not started" filter. The library filter decides which authors and series are listed: those with at least one book in that library. Counts, "not started", and the Author and Series pages always cover every book the account has, in any library. *(My call, so a list and the page it opens never disagree.)*
- **Details:** each author name and the series name become separate tap targets, opening the Author or Series screen.

## Left to the builder

- What the markers and the "next" highlight look like, and how the Library switch works (segmented button, tabs or chips), as long as they match the existing style.
- Whether Authors and Series lists use paging or a plain flow.
- How the next-to-read rule is split between SQL and Kotlin, as long as the tested function stays the single source of truth.

## Testing

- Unit tests for the read-state function and the next-to-read rule, covering boundaries: progress at 98.9 and 99, Read status with 0% progress, Dismissed books between Read and Not-started ones, and books with no series number.
- A test that a reader position update changes the cached `readProgress`.
- `CatalogDaoTest`-style tests for the author and series queries and the Not-started filter.
- A migration test from v2 to v3, following the `ReaderMigrationTest` pattern.
- A sync test showing `readStatus` and author rows are stored and cleaned up.
