package tomate.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import tomate.app.TomateApplication
import tomate.data.container.TomateContainer

val container: TomateContainer
    get() = TomateApplication.instance.container

/**
 * Tiny factory helper: each screen declares exactly which repositories its ViewModel needs.
 * No service locator, no reflection.
 */
inline fun <reified VM : ViewModel> tomateViewModelFactory(
    crossinline create: (TomateContainer) -> VM,
) = viewModelFactory {
    initializer { create(container) }
}
