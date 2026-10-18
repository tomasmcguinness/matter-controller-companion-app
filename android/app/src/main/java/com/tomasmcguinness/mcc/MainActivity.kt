package com.tomasmcguinness.mcc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tomasmcguinness.mcc.devices.DeviceListScreen
import com.tomasmcguinness.mcc.home.AddControllerScreen
import com.tomasmcguinness.mcc.home.HomeScreen
import com.tomasmcguinness.mcc.settings.SettingsScreen
import com.tomasmcguinness.mcc.ui.MccTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MccTheme { MccApp() }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("homeTab", "Home", Icons.Filled.Home),
    SETTINGS("settingsTab", "Settings", Icons.Filled.Settings),
}

private const val EDIT_CONTROLLER_ROUTE = "editController"

@Composable
private fun MccApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            // Adding or editing a controller covers the tabs, like the iOS sheet does.
            if (destination?.route?.startsWith(EDIT_CONTROLLER_ROUTE) != true) {
                NavigationBar {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Each screen has its own top bar, which handles the status bar itself.
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            navigation(startDestination = "home", route = Tab.HOME.route) {
                composable("home") {
                    HomeScreen(
                        onOpen = { navController.navigate("controller/${it.id}") },
                        onAdd = { navController.navigate(EDIT_CONTROLLER_ROUTE) },
                        onEdit = { navController.navigate("$EDIT_CONTROLLER_ROUTE?id=${it.id}") },
                    )
                }

                composable("controller/{id}") { entry ->
                    DeviceListScreen(
                        controllerId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    "$EDIT_CONTROLLER_ROUTE?id={id}",
                    arguments = listOf(navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }),
                ) { entry ->
                    AddControllerScreen(
                        editingId = entry.arguments?.getString("id"),
                        onDone = { navController.popBackStack() },
                    )
                }
            }

            navigation(startDestination = "settings", route = Tab.SETTINGS.route) {
                composable("settings") { SettingsScreen() }
            }
        }
    }
}
