# Upload local books — design

## Intent

The user gets book files from many places: browser downloads, files sent in
messengers, files copied from a computer. Today Grimmdroid can only read books
already on the Grimmory server. The user wants to take any local book file,
get it into their Grimmory library first (so it is available everywhere), and
then read it on the phone immediately.

Upload comes first; reading follows. The phone is not a second, local-only
library.

## Goals

- Accept a book file from any app: "Open with" (`ACTION_VIEW`) and "Share"
  (`ACTION_SEND`) target Grimmdroid.
- Accept a book file from inside the app: a "pick a file" action using the
  system document picker (`ACTION_OPEN_DOCUMENT`).
- Upload the file to the user's Grimmory library.
- Once the server has indexed it, open the book in the reader, exactly as a
  library book is opened today.

## Non-goals

- Reading a local file without uploading it.
- Choosing a library per book. The user uploads everything into one library.
- BookDrop (`POST /api/v1/files/upload/bookdrop`). Files there wait for admin
  review in the web UI, so they cannot be opened right after upload.
- Batch / multi-file upload.

## Anti-goals

- A spinner that never ends. If the book does not show up, the user is told
  plainly that the upload succeeded and the server has not picked it up yet.
- Silently uploading to a library the user never confirmed (except the
  single-library/single-folder case below).
- Losing the file: a failed upload must say so, never pretend success.

## Server facts (Grimmory backend, `FileUploadController` / `LibraryController`)

- `POST /api/v1/files/upload` — multipart `file`, `libraryId`, `pathId`.
  Requires admin or upload permission (`AppUserInfo.canUpload` already exists
  in `api/Dto.kt`). Returns **204 with no body** — no book id.
- The server only moves the file into the library folder (path chosen by the
  library's naming pattern from the file's metadata). It does **not** index
  it. Indexing happens through the library's folder watcher
  (`Library.watch`), or a rescan.
- It rejects formats the library does not allow (`validateAllowedFormat`).
- `PUT /api/v1/libraries/{id}/refresh` triggers a rescan; requires admin or
  library-manage permission.
- Library paths (`LibraryPath { id, libraryId, path }`) come with the library
  from the web endpoint `api/v1/libraries`; the app endpoint returns 500 for
  libraries on v3.5.0 (see the comment on `GrimmoryApi.libraries()`).
  `LibraryDto` currently has neither `paths` nor `watch`; both need adding.

## Decided with the user

- Sources: any — other apps and an in-app picker.
- Upload first, then open the book in the reader.
- One target library. The app remembers it; changeable in Settings.
- Server route: direct upload into the library (not BookDrop).
- Formats: accept only what the reader opens (EPUB, plus comic archives if
  the reader opens them). Every successful flow ends in the reader; no
  "uploaded, open elsewhere" ending.
- Duplicates: if the file is already in the library, upload anyway and open
  the existing book. The server's watcher re-points the existing book record
  at the new file (hash match in `BookFileTransactionalHandler` /
  `BookFilePersistenceService.updatePathIfChanged`) instead of creating a
  book, so a second copy stays on disk. Accepted.

## Builder's-eye calls (mine, not the user's)

- Account: upload to the current account (`AccountRepository.current`). The
  upload screen shows which server/account it goes to. The remembered
  library + folder is stored per account.
- Rescan: only when the library is not watched and the user may manage
  libraries. `PUT /refresh` rescans the whole library, so this branch gets a
  much longer timeout than the watched branch; on timeout it shows the
  "not picked up yet" message.

## Flow

1. File arrives (intent or picker) → if no account is signed in, send the
   user to sign-in, then continue.
2. If the user lacks upload permission → plain message, stop.
3. Resolve target library + folder:
   - remembered choice if set;
   - else if the server has exactly one library with exactly one folder,
     use it without asking (*my call*);
   - else ask once (library, then folder if more than one) and remember it.
4. Upload, with visible progress.
5. Find the book: poll for a book whose file matches the uploaded one
   (file name / size — builder's choice of rule). It may be a new book or an
   existing one now pointing at the new file (duplicate case); both count.
   - If the library is not watched and the user may manage libraries, call
     refresh first (*my call*).
6. Found → open in the reader via the existing path
   (`ReaderActivity.intent(context, accountId, bookId, file)`).
7. Not found within a reasonable time → "Uploaded. The server hasn't picked it
   up yet; it'll appear in your library once it does." No endless wait.

## Left to the builder

- How the uploaded book gets its local file for the reader: reuse the
  already-local file as the downloaded copy, or download it through the
  existing download path.
- Polling interval and timeout, and the exact matching rule for "the new
  book".
- Whether upload runs in a WorkManager job (survives the app closing) or in
  the foreground screen; how progress is shown.
- Exact MIME types / extensions for the intent filters, limited to the
  formats the reader opens.
- Where the in-app "pick a file" action lives in the UI.
- Error copy for: no upload permission, format not allowed by the library,
  network failure, duplicate file.
