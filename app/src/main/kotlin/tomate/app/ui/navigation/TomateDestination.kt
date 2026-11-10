package tomate.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.ui.graphics.vector.ImageVector

/** Primary navigation destinations. Order and wording are in French for v1. */
enum class TomateDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("home", "Accueil", Icons.Outlined.Home, Icons.Filled.Home),
    Messages("messages", "Messages", Icons.Outlined.ChatBubbleOutline, Icons.Filled.Chat),
    Nearby("nearby", "Proximité", Icons.Outlined.Radar, Icons.Filled.Radar),
    Communities("communities", "Communautés", Icons.Outlined.Groups, Icons.Filled.Groups),
    Profile("profile", "Profil", Icons.Outlined.PersonOutline, Icons.Filled.Person),
}

/** Secondary routes (full screen, reached from a primary destination). */
object TomateRoutes {
    const val POST_DETAIL = "post/{postId}"
    const val CHAT = "chat/{conversationId}"
    const val COMMUNITY_DETAIL = "community/{communityId}"
    const val SETTINGS = "settings"
    const val NOTIFICATIONS = "notifications"

    fun postDetail(postId: String) = "post/$postId"
    fun chat(conversationId: String) = "chat/$conversationId"
    fun communityDetail(communityId: String) = "community/$communityId"
}
