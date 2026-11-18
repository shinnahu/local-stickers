package com.localstickers.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localstickers.app.ui.editor.EditorScreen
import com.localstickers.app.ui.home.HomeScreen
import com.localstickers.app.ui.pack.PackScreen

private object Routes {
    const val HOME = "home"
    const val PACK = "pack/{packId}"
    const val EDITOR = "editor?uri={uri}&packId={packId}"

    fun pack(packId: String) = "pack/$packId"

    fun editor(uri: Uri, packId: String?): String =
        "editor?uri=${Uri.encode(uri.toString())}" + (packId?.let { "&packId=${Uri.encode(it)}" } ?: "")
}

/**
 * True while [entry] is the top screen. Used to ignore a second "back" fired while a screen is already leaving
 * (which would pop two screens). It checks the back stack, not the lifecycle: results from the photo/file picker
 * arrive before the app is RESUMED again, and they must still be handled.
 */
private fun NavController.isOnTop(entry: NavBackStackEntry) = currentBackStackEntry?.id == entry.id

@Composable
fun AppNavigation(sharedUri: Uri?, onSharedConsumed: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            nav.navigate(Routes.editor(sharedUri, null))
            onSharedConsumed()
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPack = { nav.navigate(Routes.pack(it)) },
                onEditImage = { nav.navigate(Routes.editor(it, null)) },
            )
        }
        composable(Routes.PACK, arguments = listOf(navArgument("packId") { type = NavType.StringType })) { entry ->
            val packId = entry.arguments?.getString("packId").orEmpty()
            PackScreen(
                packId = packId,
                onBack = { if (nav.isOnTop(entry)) nav.popBackStack() },
                onEditImage = { if (nav.isOnTop(entry)) nav.navigate(Routes.editor(it, packId)) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("packId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            EditorScreen(
                onClose = { if (nav.isOnTop(entry)) nav.popBackStack() },
                onSaved = { packId ->
                    if (!nav.isOnTop(entry)) return@EditorScreen
                    nav.popBackStack()
                    val current = nav.currentBackStackEntry
                    val alreadyThere = current?.destination?.route == Routes.PACK &&
                        current.arguments?.getString("packId") == packId
                    if (!alreadyThere) nav.navigate(Routes.pack(packId)) { launchSingleTop = true }
                },
            )
        }
    }
}
