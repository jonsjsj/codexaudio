# Audex changelog

Source of truth for release notes: the in-app update page and the OTA
manifest's notes derive from this file — never hand-maintain copies.

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
