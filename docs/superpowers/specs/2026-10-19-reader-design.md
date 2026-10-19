# Grimmdroid — built-in EPUB reader with position sync

Date: 2026-10-19
Builds on `2026-10-19-grimmdroid-design.md` and `2026-10-19-grimmdroid-ui.md`.
Marked **author** = decided by the author in conversation; **my call** = proposed
by Claude, not explicitly agreed; everything else is left to the builder.

## Why

Using v1, the author kept losing their place. They read the same books in
KOReader and in Grimmory's web reader, and Grimmdroid can only hand a file to
another app, so the phone never knows where they stopped and never tells the
others. They want to open a book on the phone and continue where they left off,
and have the other readers continue from the phone.

(The second gap they found, browsing by authors and series as Grimmory does, is
a separate project and is not part of this document.)

## What success looks like

The author finishes a chapter in KOReader, opens Grimmdroid, taps **Read**, sees
"Further ahead on another device: 60% — Jump", taps it, and keeps reading at the same spot.
Later the web reader opens where the phone stopped. Reading offline on a plane
catches up on its own once the phone is online.

## Goals

1. Read EPUB books inside Grimmdroid using the **Readium Kotlin Toolkit** (author).
2. Two-way position sync with Grimmory, so the web reader and (through the
   server's own bridge) KOReader stay in step (author).
3. Bookmarks, shared with Grimmory's web reader (author).
4. A rich, phone-local set of reading settings (author: "more settings is better").

## Non-goals

- Formats other than EPUB in the reader. The collection is mostly EPUB; PDF,
  comics, FB2 etc. keep today's "Open with…" path (author).
- Format conversion on the server. Grimmory can only do EPUB→KEPUB and CBX→EPUB
  (Kobo paths); not needed (author).
- Highlights and notes. Out unless trivial; they need selection UI and range
  CFIs in both directions, which is not trivial, so they are out (author + my call).
- Syncing reader settings with the web reader's server-stored preferences (author:
  "keep its own").
- KOReader bookmarks. Grimmory does not sync them.
- Authors / series browsing (separate project).

## Anti-goals (would make this a failure even if it "works")

- Landing in the wrong place: the reader opens on a position from another
  device that is wrong by more than a page, silently.
- Throwing away reading progress: a jump from another device must never
  happen without the user agreeing, and local progress must survive offline
  use, app kills and failed uploads.
- Moving the user *backwards* automatically.

## Book page: the Read button (author, from mockups)

Today the details screen shows `Download` (not on phone) or `Open with…` +
`Save to folder` (on phone). For EPUB books this becomes **one split button**
(Material 3 split button, or a filled button + attached dropdown if the
project's Material 3 version lacks it):

| State | Main part | ▾ menu | Below |
|---|---|---|---|
| Not on phone | **Read** — downloads, then opens the reader | Open with… (downloads, then opens externally) · Download only | — |
| Downloading | progress + cancel, as today | — | — |
| On phone | **Read** | Open with… | Save to folder |

Non-EPUB books keep exactly today's buttons. Offline / no-permission /
audiobook states are unchanged. Downloading reuses the existing
`DownloadRepository` / `DownloadWorker`; "Read" on a not-downloaded book
queues the download and opens the reader when it finishes (author: "it
downloads and opens"), even if the user navigated to another screen in the
app meanwhile. If the app is in the background by then, Android does not let
it open a screen on its own, so the download-complete notification opens the
reader on tap instead (my call).

## Reader

- Full-screen Readium EPUB navigator, paginated by default.
- **Immersive** (author: ok): while reading, Android's status and navigation
  bars are hidden; only the page and the reader's own status bar show.
  Tapping the centre shows the reader chrome and the system bars together,
  tapping again hides both; an edge swipe reveals the system bars briefly.
  Text stays clear of display cutouts. A reader setting keeps Android's
  status bar always visible instead.
- Tap the centre to show/hide chrome: top bar (back, title, bookmark toggle,
  bookmarks list, settings, overflow with **Sync position**), bottom bar with
  progress (percentage / chapter) and a seek slider (my call on layout).
  Overflow also holds **Make this position current** (see Position sync).
- **Status bar, tunable like KOReader's footer** (author). A thin line at
  the bottom edge, always visible, also when the chrome is hidden, drawn in
  the reader theme's colours. The user picks which items it shows, in the
  reader settings (author: "battery, pages of pages, percentage,
  progressbar, whatever"). Items, at least:
  - progress bar through the book (author: theme-appropriate: track from the
    theme's background/foreground, fill from its link colour; optionally
    with chapter tick marks, my call)
  - percentage read
  - page X of Y (Readium "positions", which are fixed-size chunks, not
    screen pages; builder picks what "page" means and labels it honestly)
  - pages left in chapter
  - chapter title
  - clock
  - battery level
  - estimated time left in chapter / book (my call)
  Default set: progress bar + percentage (my call; clock off by default,
  author). Whether items can be
  reordered, and whether the bar can be hidden entirely, is the builder's
  call; hiding should be possible.
- All reader chrome (bars, menus, footnote popup, jump bar) follows the
  reader theme's colours too, not the app theme (my call).
- Table of contents (Readium gives it).
- Bookmarks list: tap to go there, swipe or long-press to delete.

### Settings (phone-local, apply to every book — author; per-book overrides are not wanted unless the author asks later, my call)

Everything Readium's EPUB preferences support, at least:

- **Theme**: all of Grimmory's web reader themes, each in light and dark
  (`frontend/src/app/features/readers/ebook-reader/state/themes.constant.ts`:
  Default, Gray, Sepia, Crimson, Meadow, Rosewood, Azure, Dawnlight, Ember,
  Aurora, Ocean, Mist, …) using their exact `fg`/`bg`/`link` colours. Dark,
  light and a warm "paper" look are the ones the author named. An option to
  follow the system light/dark is the builder's call.
- **Custom fonts** (author: Grimmory + file import):
  - Fonts the user uploaded to Grimmory (`GET /api/v1/custom-fonts`, file at
    `GET /api/v1/custom-fonts/{fontId}/file`) appear in the font list
    automatically; downloaded once per account, kept on the phone so they
    work offline.
  - "Add font…" imports a `.ttf` / `.otf` from the phone (system file picker);
    stored in app storage, available to every account. Delete from the list.
  - Builder, first: confirm how the Readium version used loads a font face
    from app storage (not only from APK assets) — e.g. serving the file
    through the navigator's resource server or a font-family declaration
    with a file URL. If it can't, stop and report before building the UI.
- Font family (Readium's set + custom fonts; publisher default), font size, page margins,
  line height, letter spacing, word spacing, text alignment, hyphenation,
  paginated vs scroll, publisher styles on/off.
- **Footnotes**: popup over the page vs follow the link normally (author).
  Readium reports when a tapped link is a footnote and hands over its content;
  the app draws the popup. Builder: confirm the exact API in the Readium
  version used (`HyperlinkNavigator` / footnote link context).

Stored in the existing settings store (DataStore), not on the server.

## Position sync

### What the server offers (Grimmory source, local checkout 9e19c5e, Aug 2026 — builder: re-check against the pinned v3.5.0 image)

- `GET /api/v1/app/books/{id}/progress` → `AppBookProgressResponse` with
  `epubProgress {cfi, href, percentage, updatedAt}` and
  `koreaderProgress {percentage, device, deviceId, lastSyncTime}`.
- `PUT /api/v1/app/books/{id}/progress` with `UpdateProgressRequest.fileProgress
  {bookFileId, positionData (CFI), positionHref, progressPercent}`; the
  per-format fields are deprecated.
- The web reader is foliate-js and stores positions as EPUB CFIs.
- KOReader stores xpointers. When the user has "sync with web reader" enabled
  for KOReader in Grimmory, the server converts xpointer ↔ CFI both ways
  (`KoreaderService`, `EpubCfiService`). Builder: verify that a phone `PUT`
  reaches `ReadingProgressService` → `syncProgressToKoreader`, i.e. KOReader
  sees the phone's progress.
- Bookmarks: `/api/v1/bookmarks` — `GET /book/{bookId}`, `POST`, `PUT /{id}`,
  `DELETE /{id}`; EPUB bookmarks carry a `cfi` and a `title`.

### The core technical problem: CFI ↔ Readium locator

Readium positions are `Locator`s (href + progression within the resource +
total progression, optional fragments). Grimmory speaks EPUB CFI. Both
progress sync and bookmarks depend on translating:

- **Locator → CFI** for uploads and new bookmarks.
- **CFI → Locator** for opening at a server position and for showing server
  bookmarks.

How is the builder's call (parse the CFI against the EPUB's spine + XHTML
offline, or compute it in the navigator's WebView with JS). Requirements:

- Must round-trip: a CFI written by the web reader opens on the same page on
  the phone, and a CFI written by the phone opens on the same page in the web
  reader. Test with the fixture EPUBs in `integration/fixtures`.
- When a CFI cannot be resolved, fall back to the percentage (see the rules
  below), never to the start of the book silently.

### Rules (author)

Sync compares **how far** each position is, not when it was saved (author:
"doesn't make sense to sync by time, makes sense to sync by pages"). Compare by
total progression; the builder picks the tolerance below which two positions
count as "the same place" (about one page).

- **Opening a book**: open at the phone's local position immediately (no
  waiting on the network), then fetch the server position.
  - Server further ahead → show a bar: "Further ahead on another device:
    60% — Jump". Never jump without the tap (author: "still show jump").
    The server does not record which device wrote a position, and every
    write refreshes KOReader's timestamp, so the bar names no device and no
    age (author: app-only, no Grimmory change).
  - Server behind or equal → stay; upload the phone's position.
  - First open with no local position → just open at the server position.
- **Reading**: save the locator locally on every page turn; upload debounced
  (every few seconds of no movement) and when leaving the reader / app goes to
  background (my call on timings). Offline: keep the pending upload and send
  it when connectivity returns (WorkManager, like the existing sync).
- **Never overwrite a further-ahead server position automatically** (author).
  Every upload, including late offline ones and WorkManager retries, first
  reads the server position; if the server is further ahead, the upload is
  held back (phone keeps its position locally) instead of sent. This covers
  ignoring "Jump" and reading on behind, and an old offline upload arriving
  after another reader moved ahead.
- **Sync position** (reader overflow menu, author): fetch now and offer the
  jump even when the server position is *behind* — this is how the user
  deliberately goes back after rereading elsewhere.
- **Make this position current** (reader overflow menu, author: "a way to
  override position from client explicitly"): upload the phone's position
  unconditionally, even when the server is further ahead. The web reader and
  KOReader then follow the phone. Wording is my call.
- Percentage-only positions: when the server has no usable CFI (KOReader's
  "sync with web reader" off, or conversion failed) but has a KOReader
  percentage further ahead, offer the jump labelled approximate ("about
  60%") and go to that total progression (my call). Readium progression and
  KOReader percentage are measured differently, so this can be off by more
  than a page; the label is what keeps it within the anti-goal.
- Don't trust the server's `epubProgress.href`: the server's xpointer→CFI
  path updates only the CFI and percentage, so `href` can belong to an older
  write. Fallback when a CFI can't be resolved is percentage only (replaces
  "href + percentage" above).
- Finishing a book: leave Grimmory's read-status logic to the server (it
  derives status from percentage); don't add a separate "mark finished" in
  this version.

### Bookmarks

- Toggle on the current page; list per book; delete.
- Stored on the server via `/api/v1/bookmarks` so the web reader sees them, and
  cached locally so they show offline. Offline adds/deletes queue and upload
  later (my call).
- Bookmark title: chapter title + short text snippet (my call).

## Constraints

- Android, Kotlin, Compose, minSdk 26, the existing stack (Ktor, Room,
  DataStore, WorkManager, kotest). Readium is a new dependency (author).
- Multi-account: positions, pending uploads and bookmarks are per account and
  per book, like the rest of the mirror.
- Permissions: reading needs the downloaded file, so the existing download
  permission rules apply unchanged.
- Tests follow the project's existing standing rules: no test-only interfaces;
  fake at the HTTP boundary (Ktor MockEngine); the CFI translation gets
  thorough unit tests on the fixture EPUBs.

## Left to the builder

- Readium version and modules; navigator hosting (Fragment in Compose).
- CFI translation approach (see above).
- Reader chrome layout details, slider, TOC presentation.
- Room schema for local positions, pending uploads and cached bookmarks.
- Debounce timings, "same place" tolerance, retry/backoff.
