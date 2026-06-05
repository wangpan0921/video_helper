package com.wangpan.videohelper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wangpan.videohelper.R

private sealed class Dest(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Home : Dest("home", R.string.tab_home, Icons.Filled.FiberManualRecord)
    data object Tasks : Dest("tasks", R.string.tab_tasks, Icons.AutoMirrored.Filled.List)
    data object Settings : Dest("settings", R.string.tab_settings, Icons.Filled.Settings)
}

@Composable
fun AppRoot(
    onStartRecording: (includeMic: Boolean) -> Unit,
    onStopRecording: () -> Unit
) {
    val navController = rememberNavController()
    val tabs = listOf(Dest.Home, Dest.Tasks, Dest.Settings)

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            NavigationBar {
                tabs.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Home.route) {
                HomeScreen(onStartRecording = onStartRecording, onStopRecording = onStopRecording)
            }
            composable(Dest.Tasks.route) {
                TasksScreen(onOpen = { id -> navController.navigate("detail/$id") })
            }
            composable(Dest.Settings.route) { SettingsScreen() }
            composable("detail/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                TaskDetailScreen(taskId = id, onBack = { navController.popBackStack() })
            }
        }
    }
}
