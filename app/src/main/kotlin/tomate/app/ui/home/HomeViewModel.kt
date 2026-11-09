package tomate.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
import tomate.core.model.Story
import tomate.data.container.TomateContainer
import tomate.data.repo.StoryRepository

class HomeViewModel(private val container: TomateContainer) : ViewModel() {

    data class Composer(val text: String = "", val posting: Boolean = false)

    data class StoryDraft(
        val text: String = "",
        val background: String = StoryRepository.BACKGROUNDS.first(),
        val posting: Boolean = false,
    )

    /** Base state, before the stories flows are merged in. */
    private data class Base(
        val posts: List<Post>,
        val loading: Boolean,
        val refreshing: Boolean,
        val composerText: String,
        val posting: Boolean,
        val error: AppError?,
        val network: NetworkStatus,
        val pendingOperations: Int,
        val hasProfile: Boolean,
        val myId: String?,
    )

    data class UiState(
        val posts: List<Post> = emptyList(),
        val stories: List<Story> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val composerText: String = "",
        val posting: Boolean = false,
        val error: AppError? = null,
        val network: NetworkStatus = NetworkStatus.Checking,
        val pendingOperations: Int = 0,
        val hasProfile: Boolean = false,
        val myId: String? = null,
        val storyDraft: StoryDraft? = null,
    )

    private val loading = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val composer = MutableStateFlow(Composer())
    private val error = MutableStateFlow<AppError?>(null)
    private val storyDraft = MutableStateFlow<StoryDraft?>(null)
    private val now = MutableStateFlow(System.currentTimeMillis())

    private val base = combine(
        container.feed.observeFeed(),
        combine(loading, refreshing) { l, r -> l to r },
        combine(composer, error) { c, e -> c to e },
        combine(container.networkMonitor.status(), container.database.outboxDao().observeCount()) { n, p -> n to p },
        container.database.profileDao().observeMe(),
    ) { posts, loadingPair, composerPair, networkPair, profile ->
        Base(
            posts = posts,
            loading = loadingPair.first,
            refreshing = loadingPair.second,
            composerText = composerPair.first.text,
            posting = composerPair.first.posting,
            error = composerPair.second,
            network = networkPair.first,
            pendingOperations = networkPair.second,
            hasProfile = profile?.handle?.isNotBlank() == true,
            myId = profile?.id,
        )
    }

    private val activeStories = combine(container.stories.observeStories(0), now) { stories, instant ->
        // Expiry is enforced here as well as in the DAO query: the list can sit on screen for a
        // long time, and a 24-hour story must vanish at the right minute, not on next launch.
        stories.filterNot { it.isExpired(instant) }
    }

    val state: StateFlow<UiState> = combine(base, activeStories, storyDraft) { b, stories, draft ->
        UiState(
            posts = b.posts,
            stories = stories,
            loading = b.loading,
            refreshing = b.refreshing,
            composerText = b.composerText,
            posting = b.posting,
            error = b.error,
            network = b.network,
            pendingOperations = b.pendingOperations,
            hasProfile = b.hasProfile,
            myId = b.myId,
            storyDraft = draft,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                now.value = System.currentTimeMillis()
            }
        }
    }

    fun onComposerChange(text: String) {
        composer.value = composer.value.copy(text = text.take(2000))
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            if (container.session.isLocalAccount()) {
                // Local account: nothing to fetch yet, the cached feed is the whole truth.
                loading.value = false
                refreshing.value = false
                return@launch
            }
            val result = container.feed.refreshFeed()
            if (result is Outcome.Failure) error.value = result.error
            val stories = container.stories.refresh()
            if (stories is Outcome.Failure) error.value = stories.error
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

    // ----------------------------------------------------------------- stories

    fun openStoryComposer() {
        storyDraft.value = StoryDraft()
    }

    fun closeStoryComposer() {
        storyDraft.value = null
    }

    fun onStoryDraftChange(text: String) {
        storyDraft.value = storyDraft.value?.copy(text = text.take(StoryRepository.MAX_STORY_LENGTH))
    }

    fun selectStoryBackground(background: String) {
        storyDraft.value = storyDraft.value?.copy(background = background)
    }

    fun publishStory() {
        val draft = storyDraft.value ?: return
        if (draft.text.isBlank()) return
        viewModelScope.launch {
            storyDraft.value = draft.copy(posting = true)
            when (val result = container.stories.create(draft.text, draft.background)) {
                is Outcome.Success -> {
                    storyDraft.value = null
                    now.value = System.currentTimeMillis()
                    runCatching { container.sync.drain() }
                }
                is Outcome.Failure -> {
                    // Offline creation is a success from the user's point of view: the outbox
                    // holds it. Only a real refusal is surfaced.
                    if (result.error.isOffline) {
                        storyDraft.value = null
                    } else {
                        error.value = result.error
                        storyDraft.value = draft.copy(posting = false)
                    }
                }
            }
        }
    }

    /** Marks a story as seen as soon as it is displayed. */
    fun markStoryViewed(story: Story) {
        if (story.viewedByMe) return
        viewModelScope.launch { container.stories.markViewed(story.id) }
    }

    fun deleteStory(story: Story) {
        viewModelScope.launch {
            container.stories.delete(story.id)
            runCatching { container.sync.drain() }
        }
    }

    fun dismissError() {
        error.value = null
    }
}
