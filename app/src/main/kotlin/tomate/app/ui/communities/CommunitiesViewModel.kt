package tomate.app.ui.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.Community
import tomate.core.model.Outcome
import tomate.core.model.Visibility
import tomate.data.container.TomateContainer

class CommunitiesViewModel(private val container: TomateContainer) : ViewModel() {

    data class UiState(
        val communities: List<Community> = emptyList(),
        val loading: Boolean = true,
        val dialogOpen: Boolean = false,
        val name: String = "",
        val slug: String = "",
        val description: String = "",
        val visibility: Visibility = Visibility.Public,
        val creating: Boolean = false,
        val error: AppError? = null,
    )

    private val loading = MutableStateFlow(true)
    private val dialogOpen = MutableStateFlow(false)
    private val name = MutableStateFlow("")
    private val slug = MutableStateFlow("")
    private val description = MutableStateFlow("")
    private val visibility = MutableStateFlow(Visibility.Public)
    private val creating = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    val state: StateFlow<UiState> = combine(
        container.communities.observeAll(),
        combine(loading, dialogOpen) { l, d -> l to d },
        combine(name, slug) { n, s -> n to s },
        combine(description, visibility) { d, v -> d to v },
        combine(creating, error) { c, e -> c to e },
    ) { list, loadingPair, namePair, descPair, actionPair ->
        UiState(
            communities = list,
            loading = loadingPair.first,
            dialogOpen = loadingPair.second,
            name = namePair.first,
            slug = namePair.second,
            description = descPair.first,
            visibility = descPair.second,
            creating = actionPair.first,
            error = actionPair.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            val result = container.communities.refresh()
            if (result is Outcome.Failure) error.value = result.error
            loading.value = false
        }
    }

    fun openDialog() {
        dialogOpen.value = true
    }

    fun closeDialog() {
        dialogOpen.value = false
        name.value = ""
        slug.value = ""
        description.value = ""
    }

    fun onNameChange(value: String) {
        name.value = value.take(60)
        if (slug.value.isBlank()) slug.value = tomate.data.repo.CommunityRepository.slugify(value)
    }

    fun onSlugChange(value: String) {
        slug.value = tomate.data.repo.CommunityRepository.slugify(value)
    }

    fun onDescriptionChange(value: String) {
        description.value = value.take(500)
    }

    fun onVisibilityChange(value: Visibility) {
        visibility.value = value
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            creating.value = true
            val result = container.communities.create(
                name = name.value,
                slugInput = slug.value,
                description = description.value,
                visibility = visibility.value,
            )
            creating.value = false
            when (result) {
                is Outcome.Success -> {
                    closeDialog()
                    onCreated(result.value.id)
                }
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun dismissError() {
        error.value = null
    }
}
