package tomate.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import tomate.app.ui.home.ComposerActions
import tomate.app.ui.navigation.NavigationTabLabel
import tomate.app.ui.navigation.TomateDestination
import tomate.core.designsystem.components.TomateCard
import tomate.core.designsystem.theme.TomateSpacing
import tomate.core.designsystem.theme.TomateTheme

/**
 * Rend les deux zones qui cassaient sur l'appareil de l'utilisateur (barre d'onglets et ligne
 * d'actions du composeur) sur un écran de 360 dp, hors appareil, avec de vrais pixels.
 *
 * Les PNG sont écrits dans `app/build/ui-screenshots/` : ils sont regardés, pas seulement générés.
 * Les assertions comparent la hauteur des libellés entre eux : un libellé qui passe à la ligne
 * double sa hauteur, ce qui est exactement le défaut constaté.
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
                NavigationBar {
                    TomateDestination.entries.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = index == 0,
                            onClick = {},
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { NavigationTabLabel(destination.label) },
                        )
                    }
                }
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
}
