package tomate.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.map
import tomate.app.ui.chat.ChatScreen
import tomate.app.ui.communities.CommunitiesScreen
import tomate.app.ui.communities.CommunityDetailScreen
import tomate.app.ui.friends.FriendsScreen
import tomate.app.ui.home.HomeScreen
import tomate.app.ui.messages.MessagesScreen
import tomate.app.ui.nearby.NearbyScreen
import tomate.app.ui.notifications.NotificationsScreen
import tomate.app.ui.post.PostDetailScreen
import tomate.app.ui.profile.ProfileScreen
import tomate.app.ui.profile.SettingsScreen
import tomate.app.ui.vm.container
import tomate.core.designsystem.components.NoticeTone
import tomate.core.designsystem.components.TomateCountBadge
import tomate.core.designsystem.components.TomateInlineNotice
import tomate.core.designsystem.theme.ThemeMode
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.model.NetworkStatus

/**
 * Coquille de l'application : barre de navigation expressive (pilule sur l'onglet actif,
 * bouton central « + ») et hôtes d'écrans.
 *
 * Le bouton central n'ouvre que des actions réelles : story, publication, conversation,
 * communauté, proximité. Aucun élément décoratif sans effet.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        .filter { it != TomateDestination.Nearby.route }
    val showBottomBar = currentRoute == null || currentRoute in topLevelRoutes
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sheetOpen by remember { mutableStateOf(false) }
    // Intentions ponctuelles transmises aux écrans onglets (elles sont consommées une fois).
    var openStoryRequest by remember { mutableStateOf(false) }
    var openConversationRequest by remember { mutableStateOf(false) }
    var openCommunityRequest by remember { mutableStateOf(false) }

    val unreadMessages by remember {
        container.messaging.observeConversations().map { list -> list.sumOf { it.unreadCount } }
    }.collectAsStateWithLifecycle(initialValue = 0)

    fun goTo(destination: TomateDestination) {
        if (currentRoute != destination.route) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                TomateBottomBar(
                    currentRoute = currentRoute,
                    unreadMessages = unreadMessages,
                    pendingOperations = pendingOperations,
                    onSelect = { goTo(it) },
                    onCompose = { sheetOpen = true },
                )
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
                    onDismiss = onDismissNotice,
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
                        onOpenProfile = { goTo(TomateDestination.Profile) },
                        onOpenNotifications = { navController.navigate(TomateRoutes.NOTIFICATIONS) },
                        openStoryRequest = openStoryRequest,
                        onStoryRequestConsumed = { openStoryRequest = false },
                    )
                }
                composable(
                    route = TomateRoutes.POST_DETAIL,
                    arguments = listOf(navArgument("postId") { type = NavType.StringType }),
                ) { entry ->
                    PostDetailScreen(
                        postId = entry.arguments?.getString("postId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { goTo(TomateDestination.Profile) },
                    )
                }
                composable(TomateRoutes.NOTIFICATIONS) {
                    NotificationsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenConversation = { navController.navigate(TomateRoutes.chat(it)) },
                        onOpenPost = { navController.navigate(TomateRoutes.postDetail(it)) },
                        onOpenFriends = { navController.navigate(TomateRoutes.FRIENDS) },
                    )
                }
                composable(TomateRoutes.FRIENDS) {
                    FriendsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenConversation = { navController.navigate(TomateRoutes.chat(it)) },
                    )
                }
                composable(TomateDestination.Messages.route) {
                    MessagesScreen(
                        onOpenConversation = { navController.navigate(TomateRoutes.chat(it)) },
                        openComposerRequest = openConversationRequest,
                        onComposerRequestConsumed = { openConversationRequest = false },
                        onOpenFriends = { navController.navigate(TomateRoutes.FRIENDS) },
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
                    NearbyScreen(
                        network = network,
                        relayEnabled = relayEnabled,
                        batterySaver = batterySaver,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(TomateDestination.Communities.route) {
                    CommunitiesScreen(
                        onOpenCommunity = { navController.navigate(TomateRoutes.communityDetail(it)) },
                        openCreateRequest = openCommunityRequest,
                        onCreateRequestConsumed = { openCommunityRequest = false },
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
                        onOpenNotifications = { navController.navigate(TomateRoutes.NOTIFICATIONS) },
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

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "Créer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.s),
            )
            ComposeAction(
                icon = Icons.Filled.PhotoCamera,
                title = "Nouvelle story",
                subtitle = "Visible 24 h, photo ou texte",
                onClick = {
                    sheetOpen = false
                    openStoryRequest = true
                    goTo(TomateDestination.Home)
                },
            )
            ComposeAction(
                icon = Icons.Filled.Send,
                title = "Nouvelle conversation",
                subtitle = "Choisir un ou plusieurs contacts",
                onClick = {
                    sheetOpen = false
                    openConversationRequest = true
                    goTo(TomateDestination.Messages)
                },
            )
            ComposeAction(
                icon = Icons.Filled.Groups,
                title = "Créer une communauté",
                subtitle = "Publique ou privée",
                onClick = {
                    sheetOpen = false
                    openCommunityRequest = true
                    goTo(TomateDestination.Communities)
                },
            )
            ComposeAction(
                icon = Icons.Filled.Radar,
                title = "Proximité",
                subtitle = "Écran réseau local et relais",
                onClick = {
                    sheetOpen = false
                    navController.navigate(TomateDestination.Nearby.route)
                },
            )
            Spacer(Modifier.height(TomateSpacing.xl))
        }
    }
}

@Composable
private fun ComposeAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.l, vertical = TomateSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(TomateSpacing.m))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Barre de navigation : deux onglets, bouton central surélevé, deux onglets.
 * L'onglet actif reçoit une pilule colorée derrière son icône, comme dans les maquettes.
 */
@Composable
internal fun TomateBottomBar(
    currentRoute: String?,
    unreadMessages: Int,
    pendingOperations: Int,
    onSelect: (TomateDestination) -> Unit,
    onCompose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TomateSpacing.xs)
                .height(74.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val left = listOf(TomateDestination.Home, TomateDestination.Messages)
            val right = listOf(TomateDestination.Communities, TomateDestination.Profile)

            left.forEach { destination ->
                NavTab(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    badge = if (destination == TomateDestination.Messages) unreadMessages else 0,
                    onClick = { onSelect(destination) },
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .offset(y = (-6).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onCompose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Créer",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
                if (pendingOperations > 0) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        Surface(color = Color.White, shape = CircleShape) {
                            TomateCountBadge(count = pendingOperations, modifier = Modifier.padding(1.dp))
                        }
                    }
                }
            }

            right.forEach { destination ->
                NavTab(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    badge = 0,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    destination: TomateDestination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = TomateSpacing.xs, vertical = TomateSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 28.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ),
            )
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.icon,
                contentDescription = destination.label,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = 14.dp, y = (-4).dp),
                ) {
                    TomateCountBadge(count = badge)
                }
            }
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Petite icône d'action réutilisable (recherche, réglages) dans un en-tête. */
@Composable
internal fun HeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
