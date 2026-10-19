# Grimmdroid — native Android client for Grimmory

Date: 2026-10-19
Status: draft, awaiting approval

## Intent

Grimmory (https://grimmory.org, https://github.com/grimmory-tools/grimmory) is a
self-hosted library for ebooks, comics and audiobooks (community fork of
Booklore; Spring Boot + Angular, AGPL-3.0). It has a web UI and OPDS, but no
native Android client exists for Grimmory or Booklore.

The author wants a phone "mirror" of their Grimmory library: see what books
are there, download them, and hand them to whatever reader they like. It
should be publishable (F-Droid, later possibly Play) for any Grimmory user,
not just the author's server.

## Goals (first release)

1. **Connect** to any Grimmory server: enter URL, log in with
   username/password or OIDC (SSO). Several servers/accounts, with a
   switcher.
2. **Browse** the library natively: libraries, shelves, magic shelves,
   search, continue reading, recently added; cover grid / list; book details
   page.
3. **Download** a book file to the phone; downloaded books are marked and
   available offline.
4. **Export** a downloaded book, both ways:
   - **Open with…** — hand the file to a reader app of the user's choice
     (Play Books, KOReader, Moon+, …).
   - **Save to folder** — copy the file into a user-picked folder (e.g. a
     KOReader books folder). The folder is picked once and remembered.
5. **Respect permissions**: actions the user's Grimmory permissions don't
   allow (e.g. download) are hidden, not shown-then-failing.

## Later (explicitly not first release)

- In-app reader with reading-position sync to the web reader.
- Metadata fetch/apply.
- Audiobooks.
- Upload, delete, admin/library management, task manager.

## Anti-goals (what would make the author uninstall it on day one)

1. **"It's just a web view."** Every screen is native Compose. Grimmory's web
   UI is never embedded.
2. **Sluggish.** Opening the app must show the cached library instantly and
   refresh in the background. Never use the "return all books" endpoint.
3. **Terrible UX.** Material 3, proper Android back/gestures, layouts designed
   for a phone, not a shrunk desktop UI.

## Constraints

- Android only. No iOS or desktop target planned (so no Compose
  Multiplatform — unlike the author's other Android app, kmwazi).
- Must be publishable on F-Droid: fully FOSS dependencies, no proprietary
  SDKs (no Firebase, no Play Services), fastlane metadata.
- Works against servers the author doesn't control, of varying versions; the
  API is not formally documented or versioned (springdoc is off by default).
- The app is a separate program talking to Grimmory over HTTP; it contains no
  Grimmory code. AGPL of the server does not bind the client's license.

## Decided

| Topic | Decision | Source |
|---|---|---|
| Scope of v1 | Browse + download + export (open-with and save-to-folder) | author |
| Publishing | Publishable for other Grimmory users | author |
| Export | Both open-with and save-to-folder | author |
| Platform | Android only, Kotlin, Jetpack Compose, Material 3 | my call, accepted framing |
| Tooling | Same as kmwazi: Gradle version catalog as single version source, Kotest, ktlint, Android lint, license headers, fastlane (F-Droid metadata + Play), signing in Gradle from env vars, Renovate with grouped non-major updates, Liberica 21 for CI | my call, from the author's precedent |
| License | GPL-3.0-or-later with enforced license headers | my call, from precedent |
| UI planning | Done with the `ui-ux-pro-max` skill after this doc is approved, before the implementation plan | author |
| App name | Grimmdroid (project folder name; avoids using "Grimmory" as the app name) | my call |
| Offline cache | Mirror the **whole catalogue** (book info, not files) per account; every sync sweeps `/api/v1/app/books` in pages of 50 (server max) with a generation marker and deletes unseen rows only after a complete sweep (book summaries have no updated-at, so an `/ids` diff can't detect metadata edits; `/ids` is used for shelf membership). Revised during planning. Offline browse and search work over the local mirror | author |
| Accounts | **Multiple servers/accounts** with a switcher from day one. Cache rows, downloaded files, tokens and the save-to-folder choice are all scoped per account | author |
| Supported servers | Current Grimmory releases that have `/api/v1/app/*` and the mobile OIDC callback. No Booklore, no legacy-endpoint fallback. Older servers get a plain "not supported" message. Builder pins the oldest tested version and the docker image used in integration tests | my call |
| Network | Plain HTTP allowed with a warning. Self-signed certificates work once the user installs the cert as a user CA in Android settings (no in-app trust-this-certificate pinning in v1; revised during planning). Auth proxies in front of the API (Authelia, Cloudflare Access) not supported in v1 | my call |
| After download | Setting: do nothing (default) / open in my reader / save to my folder; system notification with Open and Share when the app is in the background; Share button (Android share sheet) on book details | author (default is my call) |
| Audiobooks in v1 | Shown in browse/search with a "not supported yet" label and no download button | my call |

## What the server offers (researched in Grimmory source, commit c11582e)

Paths below are relative to the Grimmory repo.

### Auth
- `POST /api/v1/auth/login {username,password}` →
  `{accessToken, refreshToken, expires, isDefaultPassword}`.
  `POST /api/v1/auth/refresh {refreshToken}`. Access JWT ~2h, refresh 30 days.
  Header `Authorization: Bearer <jwt>`; media/download URLs also accept
  `?token=`.
- `GET /api/v1/public-settings` tells the client whether OIDC is enabled,
  whether it is OIDC-only (`oidcForceOnlyMode`), and gives
  `oidcProviderDetails` (issuer, clientId).
- **OIDC has a mobile path.** Client does PKCE (S256) with the IdP via the
  system browser / Custom Tab, redirect `grimmory://oauth2-callback`
  (server default allow-list; admin setting `oidcRedirectUris`; legacy
  `booklore://oauth2-callback` also accepted). Flow: `GET
  /api/v1/auth/oidc/state` (one-time, 5 min) → IdP → `POST
  /api/v1/auth/oidc/mobile/callback` with `code, code_verifier, redirect_uri,
  nonce, state` → Grimmory JWTs. No client secret in the app.
  See `backend/.../controller/OidcAuthController.java`,
  `backend/.../config/security/oidc/OidcAuthService.java`.
- Remote-auth (forward-auth header) mode exists server-side; not targeted by
  the app.

### Mobile API (`/api/v1/app/*`) — use this first
- `GET /api/v1/app/books` (paginated, filterable: `content, page, size,
  totalElements, hasNext`), `/search?q=`, `/{id}`, `/continue-reading`,
  `/recently-added`.
- `GET /api/v1/app/libraries`, `/api/v1/app/shelves`,
  `/api/v1/app/shelves/magic`, `/api/v1/app/shelves/magic/{id}/books`.
- `GET /api/v1/app/users/me` (includes permissions).
- See `backend/.../app/controller/`.

### Other endpoints needed
- Covers: `GET /api/v1/media/book/{id}/thumbnail` and `/cover`.
- Download: `GET /api/v1/books/{id}/download` (needs `canDownload`).
- Server version: the probe reads it from the public `GET /api/v1/healthcheck` (`data.version`);
  `GET /api/v1/version` needs auth.

### Permissions
Flags on the user (`UserPermissionsEntity`): admin, upload, download,
editMetadata, manageLibrary, emailBook, deleteBook, syncKoreader, accessOpds,
… For v1 only `download` matters (and `admin`, which implies everything).

### For later releases (recorded so we don't re-research)
- **Reading progress:** `PUT /api/v1/app/books/{id}/progress` /
  `POST /api/v1/books/progress` with `fileProgress {bookFileId, positionData,
  positionHref, progressPercent}`. EPUB position is an **EPUB CFI**; PDF/CBX a
  page number; audiobook `positionMs`. The web reader uses **foliate-js**
  (vendored in `frontend/src/assets/foliate/`). KOReader and Kobo progress
  are cross-synced into the same web position. Implication for the future
  in-app reader: rendering EPUB with foliate-js inside a WebView gives exact
  CFI parity with the web reader; Readium uses its own locator format and
  would need conversion.
- **Metadata:** `POST /api/v1/books/{id}/metadata/prospective` returns a
  Server-Sent Events stream of candidates; apply with `PUT
  /api/v1/books/{id}/metadata`. Needs `editMetadata`.
- **Realtime:** STOMP over `/ws` (book-add/update/remove, metadata updates,
  session-revoked). Optional; polling/refresh is enough for v1.
- **Audiobooks:** `/api/v1/audiobooks/{id}/info`, `/stream`,
  `/track/{idx}/stream` (Range supported).

## Behaviour details

### Connecting
- Onboarding: enter server URL → app calls `public-settings` and `version`
  → shows a username/password form, an SSO button, or both, per server
  settings. If the server is OIDC-only, only the SSO button.
- If OIDC fails because the server's admin hasn't allowed the app's redirect
  URI (or the IdP client lacks it), show a plain explanation naming the URI
  `grimmory://oauth2-callback` that the admin must add.
- Tokens stored encrypted on device; refresh transparently; on refresh
  failure, return to login for that server keeping cached library visible.
- If the server version is older than the oldest version tested, or a needed
  endpoint returns 404, say so plainly ("this server version isn't
  supported: X") instead of failing silently.
- `isDefaultPassword: true`: tell the user to change it in the web UI; no
  in-app password change in v1.

### Browsing
- Library data (book summaries, shelves) is cached locally so cold start
  shows the last-known library immediately; refresh in the background and on
  pull-to-refresh.
- Covers use thumbnails in lists, full cover on details; cached on disk.
- Search runs over the local mirror (works offline); the builder may also
  use `/app/books/search` when online if it gives better results.

### Downloading and export
- Download goes to app-private storage; the book shows a "downloaded" state;
  downloaded books remain openable offline; user can delete the local copy.
- Downloads survive app backgrounding (system download/work scheduling).
- **Open with…**: share the file via a content URI to the system chooser
  (`ACTION_VIEW` with the right MIME type).
- **Save to folder**: user picks a folder via the system folder picker the
  first time; the app keeps that permission and copies there on later saves.
  Folder changeable in settings.
- Books with multiple files/formats: let the user pick which file.
  Grimmory exposes alternate files via `AdditionalFileController`
  (`/{fileId}/download`) and `/api/v1/books/{id}/download-all`; builder to
  confirm exact shapes.

## Left to the builder

- Architecture inside the app (modules, DI choice, navigation library).
- HTTP client and serialization library (must be FOSS).
- Local cache/database and image-loading libraries (must be FOSS).
- Minimum SDK level.
- Exact screen designs — come from the `ui-ux-pro-max` pass.
- How to test against a real server (e.g. a Grimmory docker-compose for
  integration tests).
