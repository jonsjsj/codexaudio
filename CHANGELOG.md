# Audex changelog

Source of truth for release notes: the in-app update page and the OTA
manifest's notes derive from this file — never hand-maintain copies.

## 0.4.8.0

- Cover tinting is now a clear switch in Settings → Appearance: "Tint from
  cover art" (On/Off). On, the whole app takes its colours from the book you're
  on; off, it holds still on a fixed accent (Mono / Blue / Gold / Cyan), which
  now only appear when the switch is off. Previously "Cover" was buried as one
  swatch among the fixed colours, so there was no obvious way to turn it off.

## 0.4.7.0

- Bug reports now work off your home network: "Report a problem" falls back to
  the public codex host, so the reporter reaches the service on mobile data too
  (it used to only work on the LAN).
## 0.4.6.0

- Book page redesigned (respecting the new Settings): a full-bleed cover header
  with series / title / narrator and small FORMAT ICONS (headphones, book, and a
  W when word-sync is ready) instead of "Audio + EPUB" text; ONE combined
  progress line with tappable bookmark ticks, a book-% (furthest read) and a
  headphones-% (furthest listened); and the description now fills the space the
  old rows used. Downloads moved into the 3-dot menu.
## 0.4.5.0

- Automatic cross-format follow (with Merge progress on): pressing Listen now
  resumes the audiobook at your furthest EBOOK spot instead of an older audio
  position — so you don't re-listen what you already read, and the combined
  progress moves as you go. (Reading already jumps to your listening spot.)
## 0.4.4.0

- A book's progress bar now follows your LIVE listening position instead of
  sitting at the last-synced spot, so it moves as you listen (and never shows
  less than the furthest you've reached across audio + ebook).
## 0.4.3.0

- Fixed stale progress: a book whose progress you discarded/reset/finished on
  another client no longer keeps showing its old percent — the sync now removes
  progress the server no longer has (it previously only ever added/updated).
- Home now shows how far you are in EITHER format: a book you've read further on
  ebook than audio (or vice versa) shows that furthest spot.
## 0.4.2.0

- Fixed bottom-tab navigation: every tab now lands on its own root. Home goes to
  Home (not "resume the last book"), Downloads goes to Downloads (not Library),
  and the active-tab underline always follows.
- A book's page: the primary button now reads Listen / Read, and the duplicate
  Listen/Read actions were removed from the progress rows (which now just show
  per-format progress and a Save toggle).
## 0.4.1.0

- The rest of the app is brought to the new design: Home's shelves are now flat
  rows with dual listen/read bars, the Library "All" tab is a flat author-grouped
  list (not a grid), Downloads shows cover + a live transfer bar, and a book's
  page leads with a cover tile beside its details plus Resume / Read buttons.
- Your requested touches stay: the single switchable skip, Settings tabs, the
  book-page 3-dot menu, merge progress, go-to and format badges.
## 0.4.0.0

- The Now Playing screen is rebuilt to the new design: a full cover banner that
  fades into the page, the title set in the display type, and a waveform under
  the scrubber. Time-remaining now sits alongside the position and total length.
- Cleaner controls: a large circular play button flanked by your chosen skip
  amount, and one utility strip for Speed, Sleep, Bookmark and Go-to.
- Chapters and Bookmarks are now tabs on the player.
## 0.3.12.0

- A book's page has a 3-dot menu (top right) for its playback settings — the
  skip amount and Discard progress now live there, instead of Discard sitting in
  a big prominent row.
- New "Merge progress" toggle (in that menu, and in Settings → Playback): show a
  book's audiobook and ebook as one combined progress with both Listen and Read,
  since the two formats now follow each other — one percent is enough.

## 0.3.11.0

- The audiobook and ebook now follow each other. "Follow audio" jumps the ebook
  to the audiobook's spot even when the audio isn't playing (before it did
  nothing and left you where you were), and the read-along bar now appears
  whenever a book has an audiobook — not only while it's playing.
- Reading the ebook now moves the audiobook's saved position to match, so
  switching to listening picks up where you read — no manual jump. (Proportional
  for books without a word-sync map.)

## 0.3.10.0

- Fixed "Send report" failing with an error: reporting no longer requires the
  optional word-sync server URL to be set — it falls back to the app's own
  report host, so filing a bug, idea or feedback works out of the box.

## 0.3.9.0

- Skip is now one amount you switch between 10 and 30 seconds in Settings →
  Playback → Skip, used the same for skip-back and skip-forward — on the player,
  the mini bar and the lock screen.
- Fixed the in-app update button doing nothing: if the version-stamped download
  address hiccuped it now falls back to the plain download, so "Update now"
  always works.

## 0.3.8.0

- Skip buttons are back everywhere: 10 seconds back and 30 seconds forward now
  show on the lock screen, the notification and Android Auto, and on the mini
  player — not just the full player. The back skip is 10 seconds now, and it's
  the same on every screen.

## 0.3.7.0

- Reading in Audex no longer resets a book to the title page in the official
  Audiobookshelf app: Audex now saves an ABS-compatible position to the server
  (while keeping your exact spot locally), so your percent and page stay right
  across apps. A position you set in the ABS app now also resumes roughly in
  place here.

## 0.3.6.0

- New "Go to…" jump: in the audiobook player and the ebook reader, jump straight
  to a spot in the book. Settings → Playback → "Go to uses" chooses whether it
  takes a percentage or the exact unit — an audiobook timestamp or an ebook page
  number.

## 0.3.5.0

- Book covers now show a small badge in the corner telling you at a glance
  whether a title has an audiobook, an ebook, or both.
- Settings is split into tabs — Servers, Appearance, Playback, About — so each
  screen stays short (Codex sync lives under Servers, word sync under Playback).

## 0.3.4.0

- The book page is calmer and denser: the title, author and length sit on the
  cover, the audiobook and ebook show as two compact progress rows with small
  buttons instead of tall cards, and "continue in the other format", "next in
  series" and "furthest listened" are tidy single lines.

## 0.3.3.0

- Progress you're making now shows up now: as you listen, the audiobook's own
  slider and the book's percent update live, instead of freezing until the next
  server sync.
- Discard progress actually clears a book now (it was leaving a sliver behind
  that kept the book in Continue).
- Tapping a tab you're already on takes you to its top: Settings opens the
  Settings menu, Home and Library return to their front page, instead of dropping
  you back on the last sub-page.
- Open the ebook of a book you're listening to and it starts where the narration
  is — no need to turn on "Follow audio" first.
- Turn pages by swiping, not just tapping.
- Tap anywhere on a book's Audiobook/Ebook row to open it, not only the small
  Play/Read text.
- Tidied a book's page: word sync only appears when it's actually set up, and the
  update status is a plain line instead of a big box.

## 0.3.2.2

- Fixed "Follow audio" doing nothing when the audiobook was paused: turning it
  on now jumps the reader to where the narration is right away, even before you
  press play, then keeps following once it's playing.

## 0.3.2.1

- Fixed updates never appearing: the app checked one address, and when your
  reverse proxy answers that address with its own placeholder page instead of
  the app's data, the check quietly found nothing — so no update ever showed. It
  now also checks the Codex address, which serves the very same files, and only
  trusts a reply that's actually the app's data.

## 0.3.2.0

- The mini player now shows the chapter you're in rather than the author — where
  you are is what you want at a glance.
- Servers in Settings say where they stand: "Connected", or "Needs login" when
  one wants you to sign in again.
- The Library header counts what you're looking at ("297 works"), so it also
  tells you how many results a search or filter left.

## 0.3.1.0

- Opening a book now colours the whole app around it — browse to a book and the
  app takes on its cover's palette, then goes back to what you're playing when
  you leave.
- A book's page tells you more: the narrator, the series it belongs to over the
  title, and a line like "2025 · 14h 6m · Audio + EPUB".
- Continue in the other format: on a book you have as both audiobook and ebook,
  a new option picks up whichever one you're further along in — start the
  audiobook where you read to, or open the ebook where you listened to. It only
  appears when the two have actually drifted apart.
- Downloads now shows how much is really on your device and which servers it
  came from, with each row's live state (On device / 62% / Queued). Removing a
  download asks once first.

## 0.3.0.0

- A new look: Audex now takes its colours from the book you're on. The
  background, surfaces, and accent are all derived from the current book's cover
  art, so the whole app re-tints as you move between books — and glides between
  palettes rather than snapping.
- New typeface: headings are now set in Space Grotesk, with Roboto for reading
  text.
- Prefer the app to hold still? Settings → Appearance → Accent still offers
  fixed Mono / Blue / Gold / Cyan.

## 0.2.16.1

- Home now features the book you listened to most recently: the big hero (and
  the Continue shelf order) follow your last-played book instead of an arbitrary
  pick, so "keep going" lands on what you actually had open.

## 0.2.16.0

- The bolder look now runs through the whole app, not just Home: Library,
  Downloads, Settings, your listening stats, and author/series pages all get the
  big headings and cleaner spacing, and each book's page opens with a full-bleed
  cover hero with the title, author, and series over it.
- Discard progress: a book's page now has a "Discard progress" option (once
  you've started it) that resets it to the beginning on all your devices. Your
  furthest-listened bookmark survives, so an accidental reset is recoverable.

## 0.2.15.1

- Fixed the ebook reader skipping text between pages: page turns now advance one
  actual screen at a time (reflowed to your font size and screen), instead of
  jumping by a fixed chunk of the file that could leap past whatever hadn't been
  shown yet. No more missing lines when you tap to the next page.

## 0.2.15.0

- Check for updates from the About page: Settings → Version now checks your
  server live and, when a newer version exists, shows it and what changed with a
  one-tap Update — right there, no waiting for a background check. It also tells
  you when you're already on the latest, or when it couldn't reach the update
  server.
- Update notifications are more reliable: the app now also checks the moment it
  opens, instead of only on a slow background timer — so "a new version is ready"
  actually reaches you.

## 0.2.14.0

- Two looks for Home (Settings → Appearance → Look):
  - Nightfall — a big, full-bleed hero of the book you're mid-way through, with
    a Resume button, then shelves of your covers.
  - Stacks — large cover art and bold headings on the dark theme you already
    have, with a two-up library grid.
- New Cyan accent to go with the bolder look.

## 0.2.13.0

- Update notifications now work away from home: the app checks the public
  address too, not only your local network — so "a new version is ready" reaches
  you on cellular, not just at home.
- Downloaded updates are named by version (e.g. audex-0.2.13.0.apk) instead of
  an internal number, so a saved APK tells you which release it is.

## 0.2.12.0

- Per-book speed: each audiobook now remembers its own playback speed, and a
  new book starts at whatever speed you last used.
- Your listening: Settings → Your listening shows your total time, today, this
  week, days listened, and books started (from your server, across all your
  devices).

## 0.2.11.0

- Skip silence: Settings → Playback → Skip silence trims long pauses in the
  narration so audiobooks move along a little faster. Off by default; takes
  effect immediately.

## 0.2.10.0

- Codex sync: connect Audex to your Codex instance (Settings → Codex sync: URL +
  an API token you generate in Codex) and your listening position is pushed to
  Codex as you go, so it updates right away instead of waiting for Codex's
  periodic Audiobookshelf sync. Off by default.

## 0.2.9.0

- Next in series: a book that's part of a series now shows the next book right
  on its page — tap to jump straight to it and keep going.

## 0.2.8.0

- Series now show up even when a book's own metadata is missing them: Audex
  reads your server's series index, so books grouped into a series on
  Audiobookshelf (but without the series written into each book) finally show
  "Series #N" under the title and link to the rest of the series.

## 0.2.7.0

- Progress that matches reality: each format now shows its own position, so the
  ebook no longer claims the audiobook's percent and then opens somewhere else —
  it shows where Read actually opens. And "Jump" to your furthest-listened point
  now sticks instead of snapping back to the older saved spot.
- Open books from Downloads: tap any downloaded book to go straight to it (it
  was view-only before).
- Immersive reading: tap the middle of the page to hide everything for a
  text-only view; tap again to bring the controls back. The current page ("Page
  X of Y") shows at the bottom while the controls are visible.

## 0.2.6.1

- Fixed the reader's Contents and Highlights panels: tapping them highlighted
  the button but never opened the list (the book view was covering it). They
  now open properly above the page.

## 0.2.6.0

- Highlights: select any passage while reading and tap "Highlight" to keep it.
  Highlights are painted in the book and collected under a new "Highlights"
  button in the reader's top bar — tap one to jump back to it, or delete it.
  They're saved on your device.

## 0.2.5.0

- A real library, not a list: the All tab is now a cover grid, and every cover
  shows a thin progress bar along the bottom so you can see how far into each
  book you are at a glance.
- Home got a facelift: Recently added is a swipeable shelf of covers, and the
  Continue list shows the same progress ribbon on each cover.

## 0.2.4.0

- Android Auto: Audex now shows up on your car's screen. Browse Continue (books
  you're partway through) and Downloads (everything saved offline), pick one,
  and it plays — resuming where you left off, with progress syncing back to your
  server. Steering-wheel and dashboard controls work through the car.

## 0.2.3.0

- Look up words while reading: select any word in an ebook and tap "Look up"
  to open it in your dictionary or translate app (falls back to a web search).

## 0.2.2.0

- Table of contents in the reader: tap "Contents" in the top bar to see the
  book's chapters and jump anywhere.

## 0.2.1.0

- Manual bookmarks: tap "Add bookmark" in the player to save the current
  moment with an optional note; bookmarks list under the chapters, tap to
  jump back, and they're stored on your server so they sync with every
  Audiobookshelf client.

## 0.2.0.2

- Your reports: the report page now lists everything you've filed from this
  device with live status — Open, Resolved, or "Fixed in <version>" — and taps
  through to the tracker entry. Same closed loop as Codex.

## 0.2.0.1

- New-version notification: the app now checks in the background and posts a
  tappable "Audex X is ready" notification when an update is published — the
  same pattern as the Codex Companion.
- Versions gained a fourth digit (0.MAJOR.FEATURE.PATCH) so numbers stop
  burning through so fast.
- Update downloads are version-stamped: the file saves as
  audex-<version>.apk instead of a generic name.

## 0.2.0

- Smart rewind: resuming after a pause backs up a little (5–30s, scaled by how
  long you were away) so you catch the thread again — works for pauses from
  the notification and headset buttons too.
- Sleep timer gained "end of chapter": tap the sleep control to cycle
  off → chapter end → 15 → 30 → 45 → 60 minutes.

## 0.1.9

- Library filters: show All, Audio only, Ebooks only, or In progress in the
  All tab — alongside the existing sort options.
- The All tab groups books under author headers when sorted by author, like
  Codex's browse pages.

## 0.1.8

- Word sync is discoverable now: the row appears on every book that has both
  an audiobook and an ebook — with instructions — even before the service is
  set up; Settings → Word sync gained a one-tap "Use default" URL; and the
  reader explains how to start read-along when the audiobook isn't playing.

## 0.1.7

- Furthest-listened bookmark: the work page now shows the furthest point you
  ever reached in an audiobook (from your server's listening history — it
  survives progress resets) with a one-tap Jump to resume there.

## 0.1.6

- Work detail got a proper hero: larger cover, series and position, year and
  duration, and the full book description (tap More/Less) pulled live from
  your server with HTML cleaned away. The page scrolls now.
- Author and series on the detail page are tappable — jump straight to that
  author's or series' books.
- Update channel choice (Settings → Updates): Stable follows releases, Beta
  gets fixes for in-app reports early.
- Covers got softly rounded corners app-wide.

## 0.1.5

- Chapters where none exist: audiobooks without chapter markers now get them
  synthesized from the word-sync alignment — the ebook's chapter boundaries
  projected onto the audio timeline. Works offline once the sync map is cached.

## 0.1.4

- New look: dark theme by default with a choice of accent color (mono, blue,
  gold — the same palette themes as Codex) under Settings → Appearance.
- Update page: Settings → Version now opens the full release history with
  notes for every version.
- Report a problem: file a bug, idea, or feedback straight from Settings —
  reports go to the developer and fixed ones are referenced on the update page.

## 0.1.3

- Fixed the blank ebook reader: the book view was never given a size when the
  reader opened without audio playing, so every ebook looked like an empty
  page. The reader now lays the book out correctly on open.

## 0.1.2

- Downloads no longer get stuck: a download interrupted by the app being
  killed now shows Retry instead of "Downloading…" forever, tapping Download
  again always restarts it, and retries stop after a few attempts instead of
  looping against an unreachable server.
- Long downloads survive the app being backgrounded (they now run as a
  foreground job with a notification).
- Download progress moves during a file, not only between files — a
  single-file audiobook no longer sits at 0% until it finishes.
- Ebooks: the Read action now explains itself — "Get to read" starts the
  download, "Fetching…" while it saves, "Read" opens the reader.
- Stalled connections time out instead of hanging the download forever.
- Versioning renumbered to the alpha scheme (this app is pre-1.0): earlier
  builds "1.0.0"/"1.1.0" are 0.1 and 0.1.1.

## 0.1.1

- Fixed the layout so buttons no longer sit under the Android system bars
  (edge-to-edge insets).
- Adopted the Codex color palette (near-black background, monochrome accent).
- In-app updates: the app checks for a newer build on launch, downloads it,
  and hands it to the installer.

## 0.1

- First working build: Audiobookshelf login (OIDC), library browsing with
  covers, search and sorting, streaming and offline audio playback, per-file
  downloads, EPUB reader (Readium) with fonts/themes, read-along with
  sentence highlighting (word-sync via the self-hosted alignment service),
  progress sync with the server, offline session upload.
