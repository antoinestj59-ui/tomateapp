package tomate.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tomate.app.ui.components.PostCard
import tomate.app.ui.home.ComposerActions
import tomate.app.ui.navigation.TomateBottomBar
import tomate.app.ui.navigation.TomateDestination
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.designsystem.theme.TomateTheme
import tomate.app.ui.home.StoriesStrip
import tomate.core.model.Post
import tomate.core.model.Story
import tomate.core.model.Visibility

/**
 * Rend les zones refondues sur un écran de 360 dp, hors appareil, avec de vrais pixels.
 *
 * Les PNG sont écrits dans `app/build/ui-screenshots/` : ils sont regardés, pas seulement générés.
 * Les assertions comparent la hauteur des libellés entre eux : un libellé qui passe à la ligne
 * double sa hauteur, ce qui était exactement le défaut constaté sur téléphone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class UiScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/ui-screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun heightOf(text: String): Int =
        compose.onNodeWithText(text).fetchSemanticsNode().size.height

    @Test
    fun bottom_navigation_labels_stay_on_one_line() {
        compose.setContent {
            TomateTheme(dynamicColor = false) {
                TomateBottomBar(
                    currentRoute = TomateDestination.Home.route,
                    unreadMessages = 4,
                    pendingOperations = 0,
                    onSelect = {},
                    onCompose = {},
                )
            }
        }
        save("navigation.png")

        val reference = heightOf("Messages")
        val long = heightOf("Communautés")
        assertTrue(
            "« Communautés » ($long px) ne doit pas être plus haut que « Messages » ($reference px)",
            long <= reference + 4,
        )
    }

    @Test
    fun composer_buttons_keep_their_whole_label() {
        compose.setContent {
            TomateTheme(dynamicColor = false) {
                Column(modifier = Modifier.fillMaxWidth().padding(TomateSpacing.l)) {
                    TomateCard {
                        Column(modifier = Modifier.padding(TomateSpacing.m)) {
                            ComposerActions(
                                length = 0,
                                canPublish = false,
                                posting = false,
                                onPhoto = {},
                                onVideo = {},
                                onEvent = {},
                                onPublish = {},
                            )
                        }
                    }
                }
            }
        }
        save("composer.png")

        val photo = heightOf("Photo")
        val event = heightOf("Événement")
        assertTrue(
            "« Événement » ($event px) ne doit pas passer à la ligne face à « Photo » ($photo px)",
            event <= photo + 4,
        )
    }

    /** Rangée de stories : tuile « + » et avatars cerclés, comme les maquettes. */
    @Test
    fun stories_strip_renders_add_tile_and_rings() {
        val now = System.currentTimeMillis()
        val stories = listOf(
            Story(id = "s1", authorId = "u1", authorHandle = "lea", authorDisplayName = "Léa", body = "", createdAt = now, expiresAt = now + 3_600_000),
            Story(id = "s2", authorId = "u2", authorHandle = "mathis", authorDisplayName = "Mathis", body = "", createdAt = now, expiresAt = now + 3_600_000, viewedByMe = true),
            Story(id = "s3", authorId = "u3", authorHandle = "emma", authorDisplayName = "Emma", body = "", createdAt = now, expiresAt = now + 3_600_000),
        )
        compose.setContent {
            TomateTheme(dynamicColor = false) {
                StoriesStrip(stories = stories, myId = "me", onAdd = {}, onOpen = {})
            }
        }
        save("stories.png")

        val add = heightOf("Ajouter une story")
        val name = heightOf("Léa")
        assertTrue(
            "Le libellé « Ajouter une story » ($add px) doit tenir sur deux lignes maximum " +
                "(référence « Léa » : $name px)",
            add <= name * 2 + 8,
        )
    }

    /** Carte de publication refondue : identité, texte, rangée d'actions sur une ligne. */
    @Test
    fun post_card_renders_identity_and_actions() {
        val post = Post(
            id = "post-1",
            authorId = "user-1",
            authorHandle = "lucas",
            authorDisplayName = "Lucas",
            body = "Quelle belle journée ! ☀️",
            visibility = Visibility.Public,
            createdAt = System.currentTimeMillis() - 12 * 60_000L,
            reactionCount = 248,
            commentCount = 32,
        )
        compose.setContent {
            TomateTheme(dynamicColor = false) {
                Column(modifier = Modifier.fillMaxWidth().padding(TomateSpacing.l)) {
                    PostCard(post = post, onOpen = {}, onReact = {})
                }
            }
        }
        save("post-card.png")

        val share = heightOf("Partager")
        val reaction = heightOf("248")
        assertTrue(
            "« Partager » ($share px) et le compteur ($reaction px) doivent tenir sur une ligne",
            share <= reaction + 6,
        )
    }
}
