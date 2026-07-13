package no.bellaybestia.audex

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import no.bellaybestia.audex.feature.library.AuthorDetailScreen
import no.bellaybestia.audex.feature.library.LibraryScreen
import no.bellaybestia.audex.feature.library.SeriesDetailScreen
import no.bellaybestia.audex.feature.player.PlayerScreen
import no.bellaybestia.audex.feature.settings.AddServerScreen
import no.bellaybestia.audex.feature.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val ADD_SERVER = "add_server"
    const val PLAYER = "player"
    const val AUTHOR = "author/{id}?name={name}"
    const val SERIES = "series/{id}?name={name}"

    fun author(id: String, name: String) = "author/${Uri.encode(id)}?name=${Uri.encode(name)}"
    fun series(id: String, name: String) = "series/${Uri.encode(id)}?name=${Uri.encode(name)}"
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
        Routes.LIBRARY, Routes.AUTHOR, Routes.SERIES -> 1
        Routes.DOWNLOADS -> 2
        Routes.SETTINGS -> 3
        else -> 0
    }

    Scaffold(
        bottomBar = {
            FlatTabRow(
                tabs = bottomTabs,
                selectedIndex = selectedTab,
                onSelect = { index -> navController.navigateToTab(bottomTabRoutes[index]) },
            )
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
                HomeScreen()
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onAuthorClick = { id, name ->
                        navController.navigate(Routes.author(id, name))
                    },
                    onSeriesClick = { id, name ->
                        navController.navigate(Routes.series(id, name))
                    },
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                )
            }
            composable(Routes.ADD_SERVER) {
                AddServerScreen(onDone = { navController.popBackStack() })
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
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen()
            }
        }
    }
}

/**
 * Tab navigation that preserves per-tab state: pop to the start destination
 * saving state, avoid duplicate copies, and restore the target tab's saved
 * stack (scroll positions included, via the saveable state holder).
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
