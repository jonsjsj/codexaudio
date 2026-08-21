package no.bellaybestia.audex.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.reader.ReaderBarPosition
import no.bellaybestia.audex.domain.reader.ReaderPrefs
import no.bellaybestia.audex.domain.reader.ReaderTheme
import no.bellaybestia.audex.domain.reader.SyncMap
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

private const val READER_FRAGMENT_TAG = "audex_epub_reader"
/** Min horizontal travel (px) to count as a page-turn swipe, not a tap wobble. */
private const val SWIPE_THRESHOLD = 60f
private const val MENU_LOOK_UP = 0x1E5D // arbitrary, unique within the selection menu
private const val MENU_HIGHLIGHT = 0x1E5E

/** Hand a selected word to a dictionary/translate app, else a web define search. */
private fun lookUp(context: android.content.Context, word: String) {
    val encoded = android.net.Uri.encode(word)
    // Try the system text-processing chooser first (Google, dictionaries,
    // Translate); fall back to a "define" web search if nothing handles it.
    val process = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, word)
        putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    val fired = runCatching {
        context.startActivity(
            android.content.Intent.createChooser(process, "Look up").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
    if (!fired) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.google.com/search?q=define+$encoded"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * Ebook reader: renders the downloaded EPUB with Readium's
 * [EpubNavigatorFragment] (embedded via FragmentContainerView) and reports each
 * locator change to [ReaderViewModel.onLocatorChanged] (debounced → offline-safe
 * ebook queue → ABS ebookProgress). The last Readium position is restored.
 */
@Composable
fun ReaderScreen(
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        ReaderUiState.Loading -> ReaderMessage(viewModel.title, "Opening…", modifier)
        ReaderUiState.NoEbook -> ReaderMessage(
            viewModel.title,
            "Download this ebook first (from its detail screen) to open it here.",
            modifier,
        )
        is ReaderUiState.Error -> ReaderMessage(viewModel.title, s.message, modifier)
        is ReaderUiState.Ready -> EpubReader(s, viewModel, modifier)
    }
}

@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
private fun EpubReader(
    ready: ReaderUiState.Ready,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findFragmentActivity() ?: return
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    // Read-time tracking for "Your activity": add 15s of reading time to the
    // ledger every 15s while this reader is in the foreground — paused when the
    // app is backgrounded so a reader left open doesn't inflate the numbers.
    var readerForeground by remember { mutableStateOf(true) }
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> readerForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> readerForeground = false
                else -> {}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (readerForeground) viewModel.recordReadSeconds(15.0)
        }
    }

    // "Look up" on the text-selection toolbar: reads the current selection and
    // hands the word to the system (a dictionary/translate app, else a web
    // "define" search). Purely additive — native Copy/Share stay put. Reads
    // the fragment by tag so it never captures a stale navigator reference.
    val scope = rememberCoroutineScope()
    val lookUpCallback = remember(activity, viewModel) {
        object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(android.view.Menu.NONE, MENU_HIGHLIGHT, 0, "Highlight")
                menu.add(android.view.Menu.NONE, MENU_LOOK_UP, 1, "Look up")
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                when (item.itemId) {
                    MENU_LOOK_UP -> scope.launch {
                        val nav = activity.supportFragmentManager
                            .findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                        val word = runCatching { nav?.currentSelection()?.locator?.text?.highlight }
                            .getOrNull()?.trim()
                        nav?.clearSelection()
                        if (!word.isNullOrBlank()) lookUp(activity, word)
                    }
                    MENU_HIGHLIGHT -> scope.launch {
                        val nav = activity.supportFragmentManager
                            .findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                        val locator = runCatching { nav?.currentSelection()?.locator }.getOrNull()
                        nav?.clearSelection()
                        if (locator != null) {
                            viewModel.addHighlight(
                                locatorJson = locator.toJSON().toString(),
                                text = locator.text.highlight?.trim().orEmpty(),
                            )
                        }
                    }
                    else -> return false
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
        }
    }

    val companion by viewModel.audioCompanion.collectAsState()
    val syncMap by viewModel.syncMap.collectAsState()
    val prefs by viewModel.readerPrefs.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val progressUnit by viewModel.progressUnit.collectAsState()
    val audioBookmarks by viewModel.audioBookmarks.collectAsState()
    val audioPositionS by viewModel.audioPositionS.collectAsState()
    var showAppearance by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    // Reading controls fold away by default (Kindle-style). A tap on the centre
    // of the page reveals them at the configured edge; tap again to fold.
    var chromeVisible by remember { mutableStateOf(false) }
    // Current page (1-based) / total, for the indicator when chrome is showing.
    var pageInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val barAtTop = prefs.barPosition == ReaderBarPosition.TOP
    // The ebook and audiobook stay position-synced; while the narration is
    // playing the read sentence is highlighted. Purely informational — the page
    // never jumps on its own (your read position is authoritative).
    val syncedWithAudio = companion != null || syncMap != null

    @Composable
    fun appearanceBar() {
        AppearanceBar(
            prefs = prefs,
            expanded = showAppearance,
            syncedWithAudio = syncedWithAudio,
            onToggle = { showAppearance = !showAppearance },
            onFontDelta = viewModel::adjustFontSize,
            onCycleTheme = viewModel::cycleTheme,
            onToc = { showToc = !showToc; if (showToc) showHighlights = false },
            tocOpen = showToc,
            onHighlights = { showHighlights = !showHighlights; if (showHighlights) showToc = false },
            highlightsOpen = showHighlights,
            onGoTo = { showGoTo = true },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (chromeVisible && barAtTop) appearanceBar()
        // In-reader table of contents (flat list, tap to jump) — the Kindle
        // affordance the reader was missing.
        if (showToc && chromeVisible) {
            val toc = remember(ready.publication) {
                flattenToc(ready.publication.tableOfContents)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (toc.isEmpty()) {
                    Text(
                        text = "This book has no table of contents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                toc.forEach { (link, depth) ->
                    Text(
                        text = link.title ?: link.href.toString().substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navigator?.go(link)
                                showToc = false
                            }
                            .padding(
                                start = (16 + depth * 16).dp,
                                end = 16.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                    )
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        // Highlights panel: saved passages, newest first — tap to jump, or delete.
        if (showHighlights && chromeVisible) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (highlights.isEmpty()) {
                    Text(
                        text = "No highlights yet — select text in the book and tap Highlight.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                highlights.forEach { mark ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = mark.text.ifBlank { "(highlight)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }
                                        .getOrNull()?.let { navigator?.go(it) }
                                    showHighlights = false
                                },
                        )
                        Text(
                            text = "Delete",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { viewModel.removeHighlight(mark.id) },
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        // Discoverability: a sync map exists but the audiobook isn't loaded — tell
        // the reader that starting it will highlight the narrated text in sync.
        if (chromeVisible && companion == null && syncMap != null) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Text(
                    text = "Audio-ebook sync ready — start the audiobook (Play on the book " +
                        "page) and the narrated text highlights here as you read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // weight(1f), NOT fillMaxSize: a fillMaxSize reader is measured first and
        // eats all remaining height, starving the weight(1f, fill=false) Contents
        // and Highlights panels above it to zero height (they toggled but never
        // showed). As the flexible child it yields the panel its share instead.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Turn exactly one RENDERED page via Readium's own paginator
            // (goForward/goBackward). These reflow to the current font size and
            // screen, advance one visible column, and cross chapter boundaries.
            //
            // The previous approach jumped between Readium "positions"
            // (ready.positions) — but for a reflowable EPUB a position is ~1 KB
            // of the source text, NOT a screen. Landing on the next position's
            // start skipped whatever of the current chunk hadn't been shown yet,
            // so every tap dropped a chunk of text ("missing information when
            // going next page"). goForward has no such gap: it steps one page.
            fun turnPage(delta: Int) {
                val nav = navigator ?: return
                if (delta > 0) nav.goForward(false) else nav.goBackward(false)
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FragmentContainerView(context).apply { id = R.id.reader_container }
                },
                update = { container ->
                    val fm = activity.supportFragmentManager
                    // The factory must be in place before the fragment is
                    // instantiated. Known limitation: if the process dies while
                    // the reader is open, restoration happens before this runs —
                    // the nav route re-opens the book from scratch instead.
                    fun attachReader() {
                        if (fm.findFragmentByTag(READER_FRAGMENT_TAG) != null) {
                            android.util.Log.i("AudexReader", "reader: attach skipped, fragment already present")
                            return
                        }
                        android.util.Log.i(
                            "AudexReader",
                            "reader: attaching fragment (container ${container.width}x${container.height}, laidOut=${container.isLaidOut})",
                        )
                        fm.fragmentFactory = ready.navigatorFactory.createFragmentFactory(
                            initialLocator = ready.initialLocator,
                            configuration = EpubNavigatorFragment.Configuration(
                                selectionActionModeCallback = lookUpCallback,
                            ),
                        )
                        runCatching {
                            fm.commitNow {
                                setReorderingAllowed(true)
                                add(
                                    R.id.reader_container,
                                    EpubNavigatorFragment::class.java,
                                    Bundle.EMPTY,
                                    READER_FRAGMENT_TAG,
                                )
                            }
                        }.onFailure { android.util.Log.e("AudexReader", "reader: fragment commit FAILED", it) }
                        navigator = fm.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                        android.util.Log.i("AudexReader", "reader: attached, navigator=${navigator != null}")
                        // Compose interop trap: a child added to an AndroidView AFTER
                        // composition never gets measured — Compose drives the holder's
                        // measurement and ignores the late child's requestLayout, so
                        // Readium's view stays 0x0 and the reader looks blank
                        // (runtime-verified: ConstraintLayout/R2ViewPager 0x0 inside a
                        // full-size container). Force one measure+layout pass at the
                        // container's real bounds.
                        container.post {
                            // forceLayout defeats View.measure()'s cache — without it
                            // the container "measures" via the cached pass and the
                            // late-added child is skipped entirely.
                            fun forceAll(v: android.view.View) {
                                v.forceLayout()
                                if (v is android.view.ViewGroup) {
                                    for (i in 0 until v.childCount) forceAll(v.getChildAt(i))
                                }
                            }
                            forceAll(container)
                            container.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(
                                    container.width, android.view.View.MeasureSpec.EXACTLY,
                                ),
                                android.view.View.MeasureSpec.makeMeasureSpec(
                                    container.height, android.view.View.MeasureSpec.EXACTLY,
                                ),
                            )
                            container.layout(
                                container.left, container.top, container.right, container.bottom,
                            )
                            val child = container.getChildAt(0)
                            android.util.Log.i(
                                "AudexReader",
                                "reader: forced layout, child=${child?.javaClass?.simpleName} " +
                                    "${child?.width}x${child?.height}",
                            )
                        }
                    }
                    val existing = fm.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                    when {
                        existing != null -> if (navigator == null) {
                            android.util.Log.i("AudexReader", "reader: reusing existing fragment")
                            navigator = existing
                        }
                        // Attach only after the container has its REAL size:
                        // committing during the first (unsized) layout pass made
                        // Readium's JS compute the column grid against a wrong
                        // viewport — pages misaligned and resource-boundary
                        // crossing dead (runtime-verified on the emulator).
                        container.isLaidOut -> attachReader()
                        else -> container.doOnLayout { attachReader() }
                    }
                },
            )

            // Swipe left/right anywhere to turn pages (Kindle-style), on top of
            // the edge-tap zones. Horizontal-drag is a separate pointerInput so
            // it coexists with the taps: a tap fires on release-without-movement,
            // a swipe fires on drag-end past the threshold.
            val swipe = Modifier.pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx <= -SWIPE_THRESHOLD) turnPage(1) // swipe left → next
                        else if (dx >= SWIPE_THRESHOLD) turnPage(-1) // swipe right → back
                    },
                ) { change, amount -> dx += amount; change.consume() }
            }
            // Edge tap zones (left = back, right = forward); center tap reveals or
            // folds the reading controls — the Kindle affordance.
            Row(Modifier.fillMaxSize().then(swipe)) {
                Box(
                    Modifier
                        .weight(0.22f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures { turnPage(-1) } },
                )
                Box(
                    Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures { chromeVisible = !chromeVisible } },
                )
                Box(
                    Modifier
                        .weight(0.22f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures { turnPage(1) } },
                )
            }

            // Page indicator (bottom-center) while chrome is showing.
            if (chromeVisible) {
                pageInfo?.let { (page, total) ->
                    Text(
                        text = "Page $page of $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp),
                    )
                }
            }
        }

        if (chromeVisible && !barAtTop) appearanceBar()
    }

    // "Go to…" jump (item 7): percent, or an exact page number, per the
    // Settings → Playback unit. Percent maps through the positions list; page is
    // a direct 1-based index into it.
    if (showGoTo) {
        // Cross-format: turn an audiobook second into the matching text position —
        // exact via the sync map's narration anchor, else proportional.
        val goToAudioSeconds: (Double) -> Unit = { seconds ->
            val map = syncMap
            val loc = if (map != null) {
                map.anchorAt(seconds)?.let { narrationLocator(ready.publication, map, it) }
                    ?: locatorForFraction(ready.positions, map.progressionAt(seconds) ?: 0.0)
            } else {
                null
            }
            loc?.let { navigator?.go(it) }
            showGoTo = false
        }
        ReaderGoToDialog(
            byPercent = progressUnit == no.bellaybestia.audex.domain.settings.ProgressUnit.PERCENT,
            totalPages = ready.positions.size,
            bookmarks = audioBookmarks,
            audioPositionS = audioPositionS,
            onDismiss = { showGoTo = false },
            onGoPercent = { pct ->
                locatorForFraction(ready.positions, pct)?.let { navigator?.go(it) }
                showGoTo = false
            },
            onGoPage = { pageIndex ->
                ready.positions.getOrNull(pageIndex)?.let { navigator?.go(it) }
                showGoTo = false
            },
            onGoAudioSeconds = goToAudioSeconds,
        )
    }

    // Stream locator changes (page turns, chapter jumps) into the debounced sync,
    // and keep the page indicator (1-based position / total) current.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect {
            viewModel.onLocatorChanged(it)
            it.locations.position?.let { pos -> pageInfo = pos to ready.positions.size }
        }
    }

    // Apply persisted appearance whenever it (or the navigator) changes.
    LaunchedEffect(prefs, navigator) {
        navigator?.submitPreferences(
            EpubPreferences(
                fontSize = prefs.fontSizePct / 100.0,
                theme = when (prefs.theme) {
                    ReaderTheme.LIGHT -> Theme.LIGHT
                    ReaderTheme.SEPIA -> Theme.SEPIA
                    ReaderTheme.DARK -> Theme.DARK
                },
            ),
        )
    }

    // Read-along: WHILE THE AUDIOBOOK IS PLAYING, the page follows the narration
    // and the spoken sentence is highlighted — word-exact via the sync map's
    // anchors, else proportional. Only while playing: pause (or close the book) and
    // your position is authoritative again — nothing yanks a paused/closed reader,
    // so reading ahead just means pausing the audio first. Jump only when the target
    // anchor CHANGES so per-second ticks don't thrash the navigator.
    val audioNow = companion
    // Read-along is user-toggleable (Settings → Reader → Read-along highlight).
    val readAlong = prefs.readAlong
    val followAnchor = if (readAlong && audioNow?.isPlaying == true) {
        syncMap?.takeIf { it.chapters.isNotEmpty() }?.anchorAt(audioNow.positionS)
    } else {
        null
    }
    val followTargetIndex = if (readAlong && audioNow?.isPlaying == true && followAnchor == null) {
        val progression = syncMap?.progressionAt(audioNow.positionS) ?: audioNow.fraction
        targetPositionIndex(ready.positions, progression)
    } else {
        null
    }
    LaunchedEffect(followAnchor?.c0, followTargetIndex, navigator) {
        val map = syncMap
        when {
            followAnchor != null && map != null ->
                narrationLocator(ready.publication, map, followAnchor)?.let { navigator?.go(it) }
            followTargetIndex != null ->
                ready.positions.getOrNull(followTargetIndex)?.let { navigator?.go(it) }
        }
    }

    // Sentence highlighting (map v1.1): tint the anchor currently being
    // narrated while the audiobook plays, so reading alongside the narration is
    // visibly in sync. Keyed on the anchor's char offset so a highlight is
    // applied once per sentence, not per playback tick.
    val narrationAnchor = if (readAlong && audioNow?.isPlaying == true) {
        syncMap?.anchorAt(audioNow.positionS)?.takeIf { !it.text.isNullOrBlank() }
    } else {
        null
    }
    LaunchedEffect(narrationAnchor?.c0, navigator) {
        val decorable = navigator as? DecorableNavigator ?: return@LaunchedEffect
        val map = syncMap
        val decorations = if (narrationAnchor != null && map != null) {
            narrationLocator(ready.publication, map, narrationAnchor)?.let { locator ->
                listOf(
                    Decoration(
                        id = "narration",
                        locator = locator,
                        style = Decoration.Style.Highlight(tint = 0x66FFC107.toInt()),
                    ),
                )
            } ?: emptyList()
        } else {
            emptyList()
        }
        decorable.applyDecorations(decorations, group = "readalong")
    }

    // Paint saved highlights (a separate decoration group from read-along, so
    // the two never clobber each other). Re-applied whenever the set changes.
    LaunchedEffect(highlights, navigator) {
        val decorable = navigator as? DecorableNavigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { mark ->
            runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }.getOrNull()?.let { loc ->
                Decoration(
                    id = mark.id,
                    locator = loc,
                    style = Decoration.Style.Highlight(tint = 0x59FFEB3B.toInt()),
                )
            }
        }
        decorable.applyDecorations(decorations, group = "highlights")
    }

    // Leaving the screen tears the fragment down; the VM keeps the publication.
    DisposableEffect(Unit) {
        onDispose {
            val fm = activity.supportFragmentManager
            fm.findFragmentByTag(READER_FRAGMENT_TAG)?.let { fragment ->
                if (!fm.isStateSaved) {
                    fm.commitNow { remove(fragment) }
                }
            }
            navigator = null
        }
    }
}

/**
 * Flat appearance strip: a collapsed "Aa" affordance expanding to font-size
 * steppers and a theme cycler. Values persist app-wide (ReaderSettingsStore).
 */
@Composable
private fun AppearanceBar(
    prefs: ReaderPrefs,
    expanded: Boolean,
    syncedWithAudio: Boolean,
    onToggle: () -> Unit,
    onFontDelta: (Int) -> Unit,
    onCycleTheme: () -> Unit,
    onToc: () -> Unit = {},
    tocOpen: Boolean = false,
    onHighlights: () -> Unit = {},
    highlightsOpen: Boolean = false,
    onGoTo: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (syncedWithAudio) {
            Text(
                text = "Synced with audio",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "Contents",
                style = MaterialTheme.typography.labelLarge,
                color = if (tocOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onToc)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "Go to",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onGoTo)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "Highlights",
                style = MaterialTheme.typography.labelLarge,
                color = if (highlightsOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onHighlights)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (expanded) {
                Text(
                    text = "A−",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onFontDelta(-10) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    text = "${prefs.fontSizePct}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "A+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onFontDelta(+10) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (prefs.theme) {
                        ReaderTheme.LIGHT -> "Light"
                        ReaderTheme.SEPIA -> "Sepia"
                        ReaderTheme.DARK -> "Dark"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onCycleTheme)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                color = if (expanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun targetPositionIndex(positions: List<Locator>, fraction: Double): Int? {
    if (positions.isEmpty()) return null
    return (fraction * (positions.size - 1)).roundToInt().coerceIn(0, positions.size - 1)
}

/**
 * Locator for a narration anchor: the anchor's chapter link + within-chapter
 * progression, with the anchored book string as the text highlight (Readium's
 * decorator resolves the DOM range by text-quote matching). Null when the map
 * predates v1.1 or the href can't be matched to the publication.
 */
private fun narrationLocator(
    publication: Publication,
    map: SyncMap,
    anchor: no.bellaybestia.audex.domain.reader.SyncAnchor,
): Locator? {
    val href = anchor.href ?: return null
    val link = Url(href)?.let { publication.linkWithHref(it) }
        ?: publication.readingOrder.firstOrNull {
            it.href.toString().endsWith(href) || href.endsWith(it.href.toString())
        }
        ?: return null
    val base = publication.locatorFromLink(link) ?: return null
    return base.copy(
        locations = base.locations.copy(progression = map.chapterProgression(anchor)),
        text = anchor.text?.let { Locator.Text(highlight = it) } ?: base.text,
    )
}

private fun locatorForFraction(positions: List<Locator>, fraction: Double): Locator? =
    targetPositionIndex(positions, fraction)?.let { positions[it] }

/** TOC tree → flat (link, depth) list, two levels deep (chapters + sections). */
private fun flattenToc(links: List<Link>): List<Pair<Link, Int>> = buildList {
    for (link in links) {
        add(link to 0)
        for (child in link.children) add(child to 1)
    }
}

/**
 * "Go to…" dialog for the reader: a percentage (0–100) or a page number
 * (1–[totalPages]); plus cross-format jumps — straight to where the audiobook is,
 * and to any [bookmarks] (including the auto "you were here" markers), converted to
 * the matching text position via the sync map.
 */
@Composable
private fun ReaderGoToDialog(
    byPercent: Boolean,
    totalPages: Int,
    bookmarks: List<no.bellaybestia.audex.domain.playback.Bookmark>,
    audioPositionS: Double?,
    onDismiss: () -> Unit,
    onGoPercent: (Double) -> Unit,
    onGoPage: (Int) -> Unit,
    onGoAudioSeconds: (Double) -> Unit,
) {
    var field by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text(if (byPercent) "Percent" else "Page") },
                    placeholder = { Text(if (byPercent) "0–100" else "1–$totalPages") },
                )
                if (audioPositionS != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        text = "Jump to the audiobook (${hms(audioPositionS)})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGoAudioSeconds(audioPositionS) }
                            .padding(vertical = 10.dp),
                    )
                }
                if (bookmarks.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        text = "Bookmarks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bookmarks.sortedByDescending { it.createdAt }.take(12).forEach { bm ->
                        Text(
                            text = bm.title.ifBlank { "Bookmark" } + "  ·  ${hms(bm.timeS.toDouble())}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onGoAudioSeconds(bm.timeS.toDouble()) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (byPercent) {
                        field.trim().toDoubleOrNull()
                            ?.let { onGoPercent((it / 100.0).coerceIn(0.0, 1.0)) }
                    } else {
                        field.trim().toIntOrNull()
                            ?.let { onGoPage((it - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))) }
                    }
                },
            ) { Text("Go") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Seconds → "h:mm:ss" (or "m:ss" under an hour). */
private fun hms(seconds: Double): String {
    val s = seconds.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun ReaderMessage(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
