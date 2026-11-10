package tomate.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tomate.app.ui.chat.ChatScreen
import tomate.app.ui.communities.CommunitiesScreen
import tomate.app.ui.communities.CommunityDetailScreen
import tomate.app.ui.home.HomeScreen
import tomate.app.ui.messages.MessagesScreen
import tomate.app.ui.nearby.NearbyScreen
import tomate.app.ui.post.PostDetailScreen
import tomate.app.ui.profile.ProfileScreen
import tomate.app.ui.profile.SettingsScreen
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.theme.ThemeMode
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.NetworkStatus

@Composable
fun TomateApp(
    network: NetworkStatus,
    pendingOperations: Int,
    onSyncNow: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    relayEnabled: Boolean,
    onRelayEnabledChange: (Boolean) -> Unit,
    batterySaver: Boolean,
    onBatterySaverChange: (Boolean) -> Unit,
    turnUrl: String? = null,
    onSaveTurn: (String, String, String) -> Unit = { _, _, _ -> },
    onClearTurn: () -> Unit = {},
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoutes = TomateDestination.entries.map { it.route }
    val showBottomBar = currentRoute == null || currentRoute in topLevelRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TomateDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { NavigationTabLabel(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (notice != null) {
                TomateInlineNotice(
                    text = notice,
                    tone = NoticeTone.Info,
                    modifier = Modifier.padding(
                        horizontal = TomateSpacing.l,
                        vertical = TomateSpacing.s,
                    ),
                )
            }
            NavHost(
                navController = navController,
                startDestination = TomateDestination.Home.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(TomateDestination.Home.route) {
                    HomeScreen(
                        network = network,
                        pendingOperations = pendingOperations,
                        onSyncNow = onSyncNow,
                        onOpenPost = { navController.navigate(TomateRoutes.postDetail(it)) },
                    )
                }
                composable(
                    route = TomateRoutes.POST_DETAIL,
                    arguments = listOf(navArgument("postId") { type = NavType.StringType }),
                ) { entry ->
                    PostDetailScreen(
                        postId = entry.arguments?.getString("postId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { navController.navigate(TomateDestination.Profile.route) },
                    )
                }
                composable(TomateDestination.Messages.route) {
                    MessagesScreen(
                        onOpenConversation = { navController.navigate(TomateRoutes.chat(it)) },
                    )
                }
                composable(
                    route = TomateRoutes.CHAT,
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                ) { entry ->
                    ChatScreen(
                        conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(TomateDestination.Nearby.route) {
                    NearbyScreen(network = network, relayEnabled = relayEnabled, batterySaver = batterySaver)
                }
                composable(TomateDestination.Communities.route) {
                    CommunitiesScreen(
                        onOpenCommunity = { navController.navigate(TomateRoutes.communityDetail(it)) },
                    )
                }
                composable(
                    route = TomateRoutes.COMMUNITY_DETAIL,
                    arguments = listOf(navArgument("communityId") { type = NavType.StringType }),
                ) { entry ->
                    CommunityDetailScreen(
                        communityId = entry.arguments?.getString("communityId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenPost = { navController.navigate(TomateRoutes.postDetail(it)) },
                    )
                }
                composable(TomateDestination.Profile.route) {
                    ProfileScreen(
                        onOpenSettings = { navController.navigate(TomateRoutes.SETTINGS) },
                    )
                }
                composable(TomateRoutes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = onDynamicColorChange,
                        relayEnabled = relayEnabled,
                        onRelayEnabledChange = onRelayEnabledChange,
                        batterySaver = batterySaver,
                        onBatterySaverChange = onBatterySaverChange,
                        turnUrl = turnUrl,
                        onSaveTurn = onSaveTurn,
                        onClearTurn = onClearTurn,
                    )
                }
            }
        }
    }
}
