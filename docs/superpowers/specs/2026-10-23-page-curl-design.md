# Page curl — design

## Intent

The user wants page turns that feel like Apple Books: the page bends and
peels away under the finger, showing the next page beneath. Today the reader
either jumps (no animation) or plays Readium's built-in slide, whose speed
cannot be changed. The user also wants to set how fast the turns that play
by themselves (volume keys, taps, flicks) run.

"Smooth" means two things, in this order: nothing flickers, flashes or jumps
(no blank frame, no page shown twice, no snap at the start or end of a turn),
and the curl itself runs at the display's frame rate.

## What the spike settled

A throwaway spike (branch `spike/page-curl`, not to be merged) proved the
approach on the user's phone (Samsung SM-A566B, Android 16) and found the
traps below. Build fresh from this document; don't copy the spike.

- **No Readium fork.** A curl drawn in a view stacked over the stock
  Readium 3.4.0 navigator works. Readium never animates; the overlay does.
- **The curl renderer** is the OpenGL page curl from
  `com.irurueta:irurueta-android-gl-curl` 1.1.6 (Apache-2.0, a maintained
  port of harism/android-pagecurl): a real 3D cylinder with shadow and a
  page back. Its four source files (`CurlTextureView`, `CurlRenderer`,
  `CurlPage`, `CurlMesh`) are **vendored** into the app, because it needs
  changes no option exposes. It still depends on
  `com.irurueta:irurueta-android-glutils` 1.1.11 for `GLTextureView`.
- **Library changes the spike proved necessary:**
  1. *Fold position, one-page mode.* Stock one-page mode puts the fold at the
     finger, so the turned part of the page sticks out past it. The fold must
     sit halfway between the drag's start edge and the finger, so the page's
     edge stays under the finger. This holds for forward curls (right edge)
     and backward curls (the incoming page's edge, mirrored about the right
     edge). The release animation's target moves one page width further, or
     the turn ends half-done.
  2. *Catch-up animation.* On the first drag event the library animates the
     curl from the page edge to the finger over `animationDurationTime`,
     while every finger move also sets the position directly. The two fight
     and the page jitters. Remove the catch-up (the curl follows the finger
     from the first move).
  3. *Backward release.* Releasing a backward curl past the right edge runs a
     finishing animation that flashes the current page before settling.
  4. *Texture uploads.* Each page bitmap is copied into a power-of-two bitmap
     and uploaded on every `updatePages`, about 32 ms per page on the test
     phone; re-uploading mid-drag causes a visible hitch.
  5. *Settle signal.* `CurrentIndexChangedListener` fires after every settle,
     including snap-backs and the reset before a curl. The overlay needs a
     plain "turn finished: completed / cancelled" callback.
- **Waiting for the next page is the enemy.** Jumping Readium to the next
  page and waiting for it to paint takes 75–260 ms (up to ~780 ms with the
  whole-document switch below). A finger drag that waits for it freezes the
  page under the finger and then jumps. A blank stand-in page that is swapped
  in later flickers. Hence the core of this design: **neighbour pages are
  drawn before the finger touches the screen.**
- **Neighbour pages can be drawn without navigating.** With
  `WebView.enableSlowWholeDocumentDraw()` called before any WebView exists,
  calling `webView.draw(canvas)` directly (not through its parent) draws the
  chapter's document from its origin, ignoring the scroll. Translating the
  canvas by `-pageIndex * pageWidth` draws any page of the chapter: 9–35 ms
  per page on the test phone. Readium paginates a chapter as one horizontally
  scrolled WebView (`R2WebView`), so page *i* is at `scrollX = i * width`.
- **Readium internals relied on:** `R2WebView` is the chapter WebView;
  Readium's `R2ViewPager` keeps the neighbouring chapters' WebViews loaded
  (so the first page of the next chapter and the last page of the previous
  one can be drawn the same way); `WebView.postVisualStateCallback` tells
  when a jump has been painted.

## Goals

- Curl on every page turn in paginated mode: finger drag, flick, volume keys
  and edge taps, forward and backward.
- A finger drag curls from the first movement, with the page's edge under
  the finger, and can be held mid-turn, finished by releasing past the
  middle, or cancelled by releasing before it.
- A flick (finger lifted fast) plays the whole turn by itself.
- A backward turn is the mirror of a forward one: the current page peels
  from its left edge towards the right and reveals the previous page. The
  overlay plays a forward curl in a horizontally mirrored curl view
  (`scaleX = -1`, mirrored copies of the current and previous pictures,
  events at `width - x`).
- Turns that play by themselves start near a bottom corner and move left and
  slightly up (forward) or the mirror (backward), so the fold leans instead
  of standing vertical.
- A setting for the speed of turns that play by themselves.
- No flicker, no blank frame, no page shown twice, before, during or after a
  turn.

## Non-goals

- Scroll mode. It keeps today's behaviour.
- Fixed-layout EPUBs, PDFs, comics. Only reflowable EPUB paginated mode.
- Two-page spreads (landscape/tablet). One page at a time.
- Changing the speed of Readium's slide animation (it runs in Readium's
  JavaScript; out of reach without a fork).
- Pressure-sensitive curls.
- Right-to-left and vertical books. *Claude's call:* they fall back to
  **Slide** (or None, if None is chosen) while Curl is selected.
- Two columns on screen. Readium may show two columns on a wide landscape
  screen (the app leaves `spread` at its default). *Claude's call:* whenever
  more than one column is visible, turns fall back to **Slide**; the user's
  layout is not changed to make the curl fit.

## Anti-goals

- A curl that makes reading worse through the overlay itself: dropped taps,
  broken text selection, battery drain while idle. (The whole-document
  switch's drawing cost is accepted; see Constraints.)
- Any state where the overlay stays on screen covering the real page (a
  stuck cover means the reader looks frozen and taps go nowhere).
- A wrong reading position: the overlay must never leave Readium on a page
  other than the one the user sees, and must never record fake page visits.

## How it works

The overlay is a `FrameLayout` around the navigator container in
`ReaderActivity`, holding the navigator, an `ImageView` cover, and the
vendored `CurlTextureView` on top. It owns touch routing for paginated mode.

### Neighbour pictures

The overlay keeps up to three bitmaps: previous, current, next. They are
refreshed after every settle, after the navigator reports a new locator
(jumps from the table of contents, bookmarks, search), after preference
changes (font, size, theme, margins — anything that reflows), after resize
and rotation, and after a chapter load finishes. A refresh:

1. Finds the visible `R2WebView` and its current page index
   (`scrollX / width`) and page count.
2. Draws current, next and previous by translating the canvas. At the last
   page of a chapter, "next" is page 0 of the next chapter's WebView; at the
   first page, "previous" is the last page of the previous chapter's WebView.
   If that neighbour WebView isn't loaded or painted yet, that picture is
   missing.
3. Draws the navigator's own chrome around the page too, so the picture
   matches what is on screen: the container's padding and background. The
   picture must be pixel-identical to the screen; a mismatch shows as a jump
   when the cover appears. Verify this as an acceptance test, not by eye.

Bitmaps are uploaded to the curl's textures as they are drawn, before any
touch, so a turn starts with no upload. Upload only what changed.

### A turn

1. Start: show the curl over the screen with current + neighbour pictures,
   from the first finger movement (or immediately for keys/taps). The
   navigator underneath is not touched yet.
2. While the curl runs, jump Readium to the target page without animation
   (`goForward(false)` / `goBackward(false)`), under the curl.
3. Finish: when the curl completes and Readium's jump has painted
   (`postVisualStateCallback`), remove the curl. If the jump hasn't painted
   yet, keep showing the finished curl frame (the target page picture) until
   it has.
4. Cancel: if the finger releases before the middle, the curl falls back; if
   Readium already jumped, jump it back, wait for paint, then remove the curl.
   Better: start the jump only once the drag passes the middle, and undo it
   if the finger comes back. Builder's choice, as long as the screen never
   shows the wrong page.
5. If a neighbour picture is missing (chapter not loaded, first turn after
   opening), fall back to the spike's method for that turn: jump first, wait
   for paint, picture, then curl. A short wait before a key turn is
   acceptable; a finger drag in that case may wait too.
6. At the first page of the book going back, or the last going forward,
   there is no turn: the finger drag does nothing (or a slight resistance —
   builder's call), keys do nothing, as today.

### Status line and chrome

*The user decided:* the reader's own status line (page number, progress,
chapter, clock — drawn by the Compose layer above the navigator) stays fixed
on top during a curl; only the page curls under it. Its values must not
change before the turn settles: Readium jumps under the curl mid-turn and
reports the new locator then, so the status line (and anything else fed by
the locator — position sync, reading pace) shows the page the user sees, not
the one Readium is on. On cancel, the jump forth and back must not be
recorded as page visits.

### Touch routing

Down events go to the navigator as today (taps, long-press selection, links,
footnotes). The overlay takes over once a move is clearly horizontal (beyond
touch slop and more horizontal than vertical): it sends `ACTION_CANCEL` to
the navigator, then drives the curl. Readium's own pager swipe threshold is
`scaledPagingTouchSlop`, larger than the overlay's `scaledTouchSlop`, so
Readium never starts its own swipe. No drag takeover while text is selected.
A gesture that went vertical first stays with the navigator until it is
clearly horizontal (beyond twice the touch slop and more horizontal than
vertical overall); then the overlay takes it over the same way, so it never
turns into Readium's own page slide.

### Edge taps and volume keys

Today `DirectionalNavigationAdapter` turns pages on edge taps and
`ReaderActivity.turnPage` on volume keys. With the curl on, both call the
overlay's automatic turn instead. A key or tap during a running turn is
ignored (as today, a held key turns once).

## Settings

*Claude's call, for the user to check:* the existing "Animate page turns"
switch in Settings becomes a choice:

- **None** — instant turns.
- **Slide** — today's Readium slide. Default for new installs (*the user
  decided*).
- **Curl** — this design. Opt-in.

Existing users with the switch on get **Slide**, off get **None**: nobody's
reader changes without them choosing it. *Claude's call:* the stored switch
key is absent both for fresh installs and for users who never touched it, so
"existing install" means a signed-in account exists when the migration runs;
those users get **None** unless the key says on.

A **Turn speed** slider appears when Curl is selected: the duration of a
turn that plays by itself, from 200 ms to 1000 ms, default 450 ms. Finger
drags follow the finger and are not affected, except the finishing animation
after release, which uses a fixed short duration (~150 ms, builder's call).

## Constraints

- Kotlin, the app's existing stack (Compose UI, Readium 3.4.0, minSdk 26).
- Vendored files keep their Apache-2.0 headers; add a NOTICE entry, and mark
  each change with a `grimmdroid:` comment.
- `WebView.enableSlowWholeDocumentDraw()` is process-wide and must run before
  the first WebView: call it in `Application.onCreate`, for everyone. *The
  user decided:* if it makes normal reading slower, accept the cost — a
  smooth curl matters more. Still measure it (time to open a book, time for
  a non-animated turn to paint, memory, switch on vs off) and report the
  numbers, so the cost is known.
- The project's conventions: Kotest for tests, no test-only production
  interfaces.

## Tests

- The library's curl geometry (fold position for forward and backward, the
  release target, completed/cancelled decisions) is plain math: unit-test it
  without OpenGL.
- Touch routing (tap passes through, vertical move passes through,
  horizontal move takes over and cancels the navigator) with synthetic
  `MotionEvent`s, Robolectric.
- Neighbour picture correctness can't run in CI (real WebView). Write a
  manual check: open a book mid-chapter, compare the drawn next-page picture
  with the screen after a turn, pixel diff zero, also at a chapter's last
  page and after changing the font size. The spike did this with PNGs pulled
  via adb.

## Left to the builder

- Class structure, file names, how the overlay is wired into
  `ReaderActivity`.
- Whether the Readium jump starts at turn start or when the drag passes the
  middle (step 4 above).
- How the curl's page back looks (mirrored faded page vs plain paper).
- Exact easing of automatic turns and their start/end points (spike used
  start 97 %/88 % of width/height, end −90 %/72 % forward, mirrored back,
  accelerate-decelerate).
- Memory budget for three full-screen bitmaps plus textures; downscale
  textures if needed, as long as text stays sharp.

## Open risks

- **A black screen** appeared once during a volume-up turn in the spike and
  couldn't be reproduced or found in logs. Guard: never upload or show a
  picture that is blank (all one colour) — fall back to an instant turn.
- **The whole-document switch's cost** is unmeasured (accepted in advance;
  measure and report it).
- **Chapter neighbours:** whether `R2ViewPager`'s neighbouring WebViews are
  scrolled to their first/last page before they're shown is unverified.
