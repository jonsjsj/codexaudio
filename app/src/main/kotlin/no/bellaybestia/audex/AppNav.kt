package no.bellaybestia.audex

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.update.AboutViewModel
import no.bellaybestia.audex.update.UpdateViewModel
import no.bellaybestia.audex.feature.settings.UpdateUi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.feature.downloads.DownloadsScreen
import no.bellaybestia.audex.feature.home.HomeScreen
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.feature.library.AuthorDetailScreen
import no.bellaybestia.audex.feature.library.LibraryScreen
import no.bellaybestia.audex.feature.library.ReaderScreen
import no.bellaybestia.audex.feature.library.SeriesDetailScreen
import no.bellaybestia.audex.feature.library.WorkDetailScreen
import no.bellaybestia.audex.feature.player.MiniPlayer
import no.bellaybestia.audex.feature.player.PlayerScreen
import no.bellaybestia.audex.feature.settings.AboutScreen
import no.bellaybestia.audex.feature.settings.StatsScreen
import no.bellaybestia.audex.feature.settings.AddServerScreen
import no.bellaybestia.audex.feature.settings.CurrentScreen
import no.bellaybestia.audex.feature.settings.ReportFab
import no.bellaybestia.audex.feature.settings.ReportScreen
import no.bellaybestia.audex.feature.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val ADD_SERVER = "add_server"
    const val ABOUT = "about"
    const val REPORT = "report"
    const val STATS = "stats"
    const val PLAYER = "player"
    const val AUTHOR = "author/{id}?name={name}"
    const val SERIES = "series/{id}?name={name}"
    const val WORK = "work/{id}?title={title}&author={author}"
    const val READER = "reader/{serverId}/{itemId}?title={title}"

    fun author(id: String, name: String) = "author/${Uri.encode(id)}?name=${Uri.encode(name)}"
    fun series(id: String, name: String) = "series/${Uri.encode(id)}?name=${Uri.encode(name)}"
    fun work(id: String, title: String, author: String?) =
        "work/${Uri.encode(id)}?title=${Uri.encode(title)}&author=${Uri.encode(author.orEmpty())}"
    fun reader(serverId: String, itemId: String, title: String) =
        "reader/${Uri.encode(serverId)}/${Uri.encode(itemId)}?title=${Uri.encode(title)}"
}

private val bottomTabs = listOf("Home", "Library", "Downloads", "Settings")
private val bottomTabRoutes =
    listOf(Routes.HOME, Routes.LIBRARY, Routes.DOWNLOADS, Routes.SETTINGS)

/**
 * The single-activity shell: a flat bottom bar (FlatTabRow — Material3's
 * NavigationBar draws pill indicators, which the design rules forbid) over a
 * NavHost. Tab switches use saveState/restoreState so each tab keeps its own
 * back stack, scroll positions, and selected sub-tab (docs/02 §2.4).
 */
@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedTab = when (currentRoute) {
        Routes.HOME -> 0
        Routes.LIBRARY, Routes.AUTHOR, Routes.SERIES, Routes.WORK, Routes.READER -> 1
        Routes.DOWNLOADS -> 2
        Routes.SETTINGS, Routes.ADD_SERVER, Routes.ABOUT, Routes.REPORT -> 3
        else -> 0
    }

    // Record the content screen you're on so a report filed from the reporter can name the
    // window it's about (skips the report screen itself → it points at where you came from).
    LaunchedEffect(currentRoute) { labelForRoute(currentRoute)?.let { CurrentScreen.record(it) } }

    Scaffold(
        // "Report this screen" on detail pages, where Settings → Report is several taps away.
        floatingActionButton = {
            if (currentRoute in reportFabRoutes) {
                ReportFab(onClick = { navController.navigate(Routes.REPORT) })
            }
        },
        bottomBar = {
            // The reader is full-screen (no app chrome) so reading is immersive —
            // tapping the page then hides the reader's own bars for text only.
            if (currentRoute != Routes.READER) {
                // navigationBarsPadding lifts the tab row above the Android gesture
                // bar so its tap targets aren't stolen by the system (the inset is
                // 0 when not edge-to-edge, so this is safe on every device).
                Column(Modifier.navigationBarsPadding()) {
                    if (currentRoute != Routes.PLAYER) {
                        MiniPlayer(onExpand = { navController.navigate(Routes.PLAYER) })
                    }
                    FlatTabRow(
                        tabs = bottomTabs,
                        selectedIndex = selectedTab,
                        onSelect = { index ->
                            navController.navigateToTab(bottomTabRoutes[index], currentRoute)
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(onWorkClick = { work -> navController.navigateToWork(work) })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onAuthorClick = { id, name ->
                        navController.navigate(Routes.author(id, name))
                    },
                    onSeriesClick = { id, name ->
                        navController.navigate(Routes.series(id, name))
                    },
                    onWorkClick = { work -> navController.navigateToWork(work) },
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    onOpenWork = { id, title, author ->
                        navController.navigate(Routes.work(id, title, author))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                    appVersion = BuildConfig.VERSION_NAME,
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onStats = { navController.navigate(Routes.STATS) },
                    onReport = { navController.navigate(Routes.REPORT) },
                )
            }
            composable(Routes.ADD_SERVER) {
                AddServerScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.ABOUT) {
                val aboutVm: AboutViewModel = hiltViewModel()
                val updateState by aboutVm.state.collectAsState()
                AboutScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    update = updateState.toUi(),
                    onCheck = aboutVm::check,
                    onInstall = aboutVm::install,
                )
            }
            composable(Routes.REPORT) {
                ReportScreen(appVersion = BuildConfig.VERSION_NAME)
            }
            composable(
                route = Routes.AUTHOR,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                AuthorDetailScreen(
                    authorId = entry.arguments?.getString("id").orEmpty(),
                    authorName = entry.arguments?.getString("name"),
                    onWorkClick = { work -> navController.navigateToWork(work) },
                )
            }
            composable(
                route = Routes.SERIES,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SeriesDetailScreen(
                    seriesId = entry.arguments?.getString("id").orEmpty(),
                    seriesName = entry.arguments?.getString("name"),
                    onWorkClick = { work -> navController.navigateToWork(work) },
                )
            }
            composable(
                route = Routes.WORK,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("author") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                WorkDetailScreen(
                    onOpenReader = { serverId, itemId, title ->
                        navController.navigate(Routes.reader(serverId, itemId, title))
                    },
                    onAuthorClick = { id, name ->
                        navController.navigate(Routes.author(id, name))
                    },
                    onSeriesClick = { id, name ->
                        navController.navigate(Routes.series(id, name))
                    },
                    onOpenWork = { work -> navController.navigateToWork(work) },
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ReaderScreen()
            }
            composable(Routes.PLAYER) {
                PlayerScreen()
            }
        }
    }

    UpdateGate()
}

/**
 * Watches for a newer published APK on launch and, when one exists, offers a
 * one-tap in-app update (download + hand off to the system installer).
 */
@Composable
private fun UpdateGate(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    when (val s = state) {
        is UpdateViewModel.State.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Update available") },
            text = {
                val notes = s.info.notes
                Text("Audex ${s.info.versionName} is ready to install." +
                    if (notes.isNotBlank()) "\n\n$notes" else "")
            },
            confirmButton = { TextButton(onClick = vm::install) { Text("Update") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Later") } },
        )
        is UpdateViewModel.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
        )
        UpdateViewModel.State.Idle -> Unit
    }
}

/**
 * Tab navigation that preserves per-tab state: pop to the start destination
 * saving state, avoid duplicate copies, and restore the target tab's saved
 * stack (scroll positions included, via the saveable state holder).
 */
private fun NavHostController.navigateToWork(work: Work) {
    navigate(Routes.work(work.id, work.title, work.authorName))
}

/** Map the app-module update state onto the feature-local UI type. */
private fun AboutViewModel.State.toUi(): UpdateUi = when (this) {
    AboutViewModel.State.Checking -> UpdateUi.Checking
    AboutViewModel.State.UpToDate -> UpdateUi.UpToDate
    AboutViewModel.State.Unreachable -> UpdateUi.Unreachable
    is AboutViewModel.State.Available -> UpdateUi.Available(info.versionName, info.notes)
    is AboutViewModel.State.Downloading -> UpdateUi.Downloading(progress)
}

/** Detail/pushed pages that show the floating "report this screen" button. */
private val reportFabRoutes =
    setOf(Routes.WORK, Routes.AUTHOR, Routes.SERIES, Routes.STATS, Routes.ABOUT)

/**
 * Human label for the current route, attached to a report as "About: <screen>". Null means
 * don't record (the report screen itself + unknowns) so the context stays the screen the
 * user came from.
 */
private fun labelForRoute(route: String?): String? = when (route) {
    Routes.HOME -> "Home"
    Routes.LIBRARY -> "Library"
    Routes.DOWNLOADS -> "Downloads"
    Routes.SETTINGS -> "Settings"
    Routes.STATS -> "Listening stats"
    Routes.ABOUT -> "About"
    Routes.PLAYER -> "Player"
    Routes.WORK -> "Book detail"
    Routes.AUTHOR -> "Author"
    Routes.SERIES -> "Series"
    Routes.READER -> "Reader"
    Routes.ADD_SERVER -> "Add server"
    else -> null
}

/** The routes that belong to each bottom tab's section (root + its sub-pages). */
private fun sectionRoutesFor(tabRoot: String): Set<String> = when (tabRoot) {
    Routes.LIBRARY -> setOf(Routes.LIBRARY, Routes.AUTHOR, Routes.SERIES, Routes.WORK, Routes.READER)
    Routes.SETTINGS -> setOf(Routes.SETTINGS, Routes.ADD_SERVER, Routes.ABOUT, Routes.REPORT, Routes.STATS)
    Routes.HOME -> setOf(Routes.HOME)
    Routes.DOWNLOADS -> setOf(Routes.DOWNLOADS)
    else -> setOf(tabRoot)
}

/**
 * Tab navigation. Switching to a DIFFERENT tab restores that tab's saved stack
 * (scroll, sub-page) — the viewState rule. Re-tapping the tab you're ALREADY in
 * RESETS it to its root instead of restoring the last sub-page: pressing
 * Settings goes to the Settings menu (not the About page you last opened), and
 * Home/Library go back to their front page.
 */
private fun NavHostController.navigateToTab(route: String, currentRoute: String?) {
    val reselectingSameTab = currentRoute in sectionRoutesFor(route)
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = !reselectingSameTab
    }
}
