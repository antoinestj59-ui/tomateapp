package tomate.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.NetworkStatus
import tomate.core.model.Outcome
import tomate.core.model.Post
import tomate.data.container.TomateContainer

class HomeViewModel(private val container: TomateContainer) : ViewModel() {

    data class Composer(val text: String = "", val posting: Boolean = false)

    data class UiState(
        val posts: List<Post> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val composerText: String = "",
        val posting: Boolean = false,
        val error: AppError? = null,
        val network: NetworkStatus = NetworkStatus.Checking,
        val pendingOperations: Int = 0,
        val hasProfile: Boolean = false,
    )

    private val loading = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val composer = MutableStateFlow(Composer())
    private val error = MutableStateFlow<AppError?>(null)

    val state: StateFlow<UiState> = combine(
        container.feed.observeFeed(),
        combine(loading, refreshing) { l, r -> l to r },
        combine(composer, error) { c, e -> c to e },
        combine(container.networkMonitor.status(), container.database.outboxDao().observeCount()) { n, p -> n to p },
        container.database.profileDao().observeMe(),
    ) { posts, loadingPair, composerPair, networkPair, profile ->
        UiState(
            posts = posts,
            loading = loadingPair.first,
            refreshing = loadingPair.second,
            composerText = composerPair.first.text,
            posting = composerPair.first.posting,
            error = composerPair.second,
            network = networkPair.first,
            pendingOperations = networkPair.second,
            hasProfile = profile?.handle?.isNotBlank() == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun onComposerChange(text: String) {
        composer.value = composer.value.copy(text = text.take(2000))
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            val result = container.feed.refreshFeed()
            if (result is Outcome.Failure) error.value = result.error
            runCatching { container.sync.drain() }
            loading.value = false
            refreshing.value = false
        }
    }

    fun publish() {
        val text = composer.value.text
        if (text.isBlank()) return
        viewModelScope.launch {
            composer.value = composer.value.copy(posting = true)
            val result = container.feed.createPost(text)
            when (result) {
                is Outcome.Success -> composer.value = Composer()
                is Outcome.Failure -> {
                    error.value = result.error
                    composer.value = composer.value.copy(posting = false)
                }
            }
        }
    }

    fun react(post: Post) {
        viewModelScope.launch {
            val result = container.feed.toggleReaction(post.id, post.reactedByMe)
            if (result is Outcome.Failure && !result.error.isOffline) {
                error.value = result.error
            }
        }
    }

    fun dismissError() {
        error.value = null
    }
}
