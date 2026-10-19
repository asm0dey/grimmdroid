# Grimmdroid — interface plan

Date: 2026-10-19
Companion to `2026-10-19-grimmdroid-design.md` (first-release scope only).
Produced with the ui-ux-pro-max skill (product match: "Book & Reading Tracker";
stack: jetpack-compose). Everything here is a proposal unless marked as the
author's.

## Principles

1. **Covers are the interface.** The library is a wall of book covers; chrome
   stays quiet so the covers carry the colour.
2. **Never wait on the network to show something.** Every screen renders from
   the local mirror first; network refresh is background and shown as a thin
   indicator, never a blocking spinner.
3. **Native Android, nothing web-like.** Material 3 components, predictive
   back, edge-to-edge, system font scaling.
4. **Actions match permissions.** A user who can't download never sees a
   Download button that fails.

## Navigation

Bottom navigation bar, 4 destinations (each keeps its own back stack):

| Tab | Purpose |
|---|---|
| **Home** | Rows: Continue reading · Recently added · On this phone (downloaded) |
| **Library** | All books, cover grid, filters, sort |
| **Shelves** | Shelves and magic shelves |
| **Downloads** | Books stored on this phone, storage used |

- **Search**: an M3 `SearchBar` at the top of Home and Library; opens a
  full-screen search over the local mirror (instant, works offline).
- **Account avatar** in the top-right of every tab → bottom sheet: list of
  accounts (server + user, tap to switch), "Add server", "Settings".
- Book details open over the current tab (keeps the tab's back stack).
- Deep links: `grimmory://oauth2-callback` (login only). Book deep links are
  not needed in v1.

## Screens

### 1. Onboarding (first launch, and "Add server")
1. **Server address**: one text field (visible label, `https://` prefilled,
   URL keyboard), Continue. On Continue the app probes `public-settings` and
   `version`; errors appear under the field ("Can't reach server",
   "This isn't a Grimmory server", "Server version X isn't supported").
   Plain `http://` or an untrusted certificate shows a warning card with an
   explicit "Continue anyway".
2. **Sign in**: username/password form and/or "Sign in with SSO" button,
   per server settings (SSO only if `oidcForceOnlyMode`). SSO opens a Custom
   Tab. If the server refuses the redirect URI, show a card that names
   `grimmory://oauth2-callback` and says the admin must allow it.
3. **First sync**: go straight to Home; the library fills in as pages
   arrive, with a progress line ("Syncing library · 1,240 of 8,310"). No
   blocking full-screen loader.

### 2. Home
- Horizontal rows of covers: Continue reading, Recently added, On this
  phone. Each row header has "See all".
- Empty states: no downloads → "Books you download appear here".

### 3. Library
- Cover grid, adaptive columns (min cell ~110dp, cover ratio 2:3), title
  and author under each cover (2 lines max, ellipsis). Toggle to a list view
  (small cover, title, author, format, size) in the top bar.
- Library filter chips under the search bar (one chip per Grimmory library,
  plus "All").
- Sort and filter in a bottom sheet: sort (title, author, added, series);
  filter (format, downloaded only).
- Big libraries: a fast-scroll thumb with a letter/date bubble.
- Each cover shows small badges: downloaded (check icon), audiobook
  (headphones icon + "Not supported yet" on details).

### 4. Book details
- Large cover (shared-element transition from the grid), title, authors,
  series + number, format chips, file size, page count, description
  (collapsible), tags/categories, library.
- **Primary action button**, one at a time:
  - `Download` → becomes a progress bar with Cancel → `Open with…`
  - Downloaded: `Open with…` (system chooser) + secondary `Save to folder`.
  - Several files/formats: Download opens a picker of the files.
  - No download permission: no button; a line of text "Your account can't
    download books".
  - Audiobook: disabled state with "Audiobooks aren't supported yet".
- **Share** icon in the top bar (hidden only without download permission or
  for audiobooks; if not downloaded yet, it downloads first): sends the file
  through the Android share sheet (`ACTION_SEND`) to Telegram, email, Nearby
  Share, etc. Different from Open with… (`ACTION_VIEW`, reader apps).
- Overflow menu: Delete from this phone, Open in web UI (browser).
- **When a download finishes**: the button area switches to Open with… +
  Save to folder with an "On this phone · EPUB · 1.2 MB" line; the cover
  gets the downloaded badge everywhere; if the app is in the background, a
  system notification "<title> downloaded" with Open and Share actions.
- **"After a download finishes" setting** (author approved): Do nothing /
  Open in my reader / Save to my folder; plus the remembered folder and
  reader app. Default: Do nothing (my call). Mockups: canvas
  https://claude.ai/artifact/WhTGXCz9UYhv8TST9tdywJ
- "Save to folder" first time → system folder picker; later saves go
  straight there with a snackbar "Saved to Books/KOReader · Change".

### 5. Shelves
- Two sections: Shelves, Magic shelves (with count). Tap → the Library grid
  scoped to that shelf (same component, same sort/filter).

### 6. Downloads
- List: cover, title, format, size; header with total size used.
- Multi-select (long-press) for delete / save to folder. No swipe-to-delete
  (conflicts with back gesture and is easy to trigger by accident).

### 7. Settings (from the account sheet)
- Per account: server, user, last synced + "Sync now", Sign out (keeps or
  deletes downloads — ask).
- Export folder (current, Change).
- Theme: System / Light / Dark; Dynamic colour on/off.
- Storage: downloads size, cover cache size, Clear cover cache.
- About: version, licences, source link.

## Global states

| State | How it shows |
|---|---|
| Background sync | Thin progress line under the top bar |
| Offline | Small "Offline" chip in the top bar; downloads still open; download buttons disabled with reason |
| Session expired | Banner "Signed out of <server> · Sign in" — cached library stays visible |
| Unsupported server (after an update) | Full-width banner with the server version and what doesn't work |
| Errors on actions | Snackbar with cause and Retry, never a dialog for transient errors |

## Visual direction

- **Colour**: Material You dynamic colour by default (my call: native
  Android apps are expected to follow the wallpaper). Fallback / "dynamic
  colour off" palette is warm paper and ink, from the skill's Book & Reading
  match: primary `#92400E` (ink brown), secondary `#78716C`, accent
  `#D97706` (amber), background `#FFFBEB` (paper), foreground `#0F172A`,
  error `#DC2626`. Generate the full M3 tonal scheme from the primary seed;
  verify 4.5:1 text contrast in light and dark.
- **Dark theme**: required; checked separately, not inferred from light.
- **Typography**: M3 type scale on the system font (Roboto), respecting
  system font scaling up to 200%. (The skill suggested Atkinson Hyperlegible —
  rejected as a web-oriented pick; system font is more native. Builder may
  revisit.)
- **Icons**: Material Symbols, outlined, one style throughout; icon-only
  buttons have content descriptions.
- **Cover placeholders**: before a cover loads, show a 2:3 tile tinted from
  the title hash with the title text, so the layout never jumps.
- **Motion**: subtle (skill dial 3/10). Shared-element cover transition
  grid → details; M3 default durations; honour "Remove animations".
- **Density**: standard; 8dp spacing grid; touch targets ≥ 48dp.

## Compose implementation notes (from the skill's jetpack-compose guidance)

- `LazyVerticalGrid` / `LazyColumn` everywhere, with stable `key = book.id`.
- Immutable UI state (persistent/immutable lists) to avoid unstable
  recomposition.
- Thumbnails (not full covers) in grids; full cover only on details.
- Check recomposition counts on the Library grid with a 10k-book mirror.

## Pre-delivery checks (per screen, during implementation)

- Small phone width (360dp) and landscape; tablet gets more grid columns.
- Largest font size; TalkBack focus order; reduced motion.
- Light and dark contrast measured separately.
- Pressed feedback on every tappable element; disabled states non-tappable.
