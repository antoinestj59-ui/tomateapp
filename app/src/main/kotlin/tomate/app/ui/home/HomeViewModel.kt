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
import tomate.data.extras.EventCodec
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import tomate.data.repo.StoryRepository

class HomeViewModel(private val container: TomateContainer) : ViewModel() {

    data class Composer(
        val text: String = "",
        val posting: Boolean = false,
        val media: ByteArray? = null,
        val mediaType: String? = null,
        val mimeType: String? = null,
        val extension: String = "jpg",
        val mediaLabel: String? = null,
    )

    /**
     * Brouillon d'événement. La date et l'heure sont saisies en clair (AAAA-MM-JJ / HH:MM)
     * puis converties en instant ISO : aucun fuseau n'est deviné côté serveur.
     */
    data class EventDraft(
        val title: String = "",
        val date: String = "",
        val time: String = "19:00",
        val place: String = "",
        val details: String = "",
        val posting: Boolean = false,
        val error: String? = null,
    )

    data class StoryDraft(
        val text: String = "",
        val background: String = StoryRepository.BACKGROUNDS.first(),
        val posting: Boolean = false,
        val media: ByteArray? = null,
        val mediaType: String? = null,
        val mimeType: String? = null,
        val extension: String = "jpg",
        val mediaLabel: String? = null,
    )

    /** Base state, before the stories flows are merged in. */
    private data class Base(
        val posts: List<Post>,
        val loading: Boolean,
        val refreshing: Boolean,
        val composerText: String,
        val posting: Boolean,
        val composerMediaLabel: String? = null,
        val error: AppError?,
        val network: NetworkStatus,
        val pendingOperations: Int,
        val failedOperations: Int,
        val lastFailure: String?,
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
        val composerMediaLabel: String? = null,
        val error: AppError? = null,
        val network: NetworkStatus = NetworkStatus.Checking,
        val pendingOperations: Int = 0,
        val failedOperations: Int = 0,
        val lastFailure: String? = null,
        val hasProfile: Boolean = false,
        val myId: String? = null,
        val storyDraft: StoryDraft? = null,
        val eventDraft: EventDraft? = null,
    )

    private val loading = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val composer = MutableStateFlow(Composer())
    private val error = MutableStateFlow<AppError?>(null)
    private val storyDraft = MutableStateFlow<StoryDraft?>(null)
    private val eventDraft = MutableStateFlow<EventDraft?>(null)
    private val now = MutableStateFlow(System.currentTimeMillis())

    /** Attente réelle (encore vivante) et refus définitifs, tenus séparés à l'écran. */
    private data class OutboxState(val pending: Int, val failed: Int, val lastFailure: String?)

    private val outboxFlow = combine(
        container.database.outboxDao().observeCount(),
        container.database.outboxDao().observeDeadCount(),
        container.database.outboxDao().observeLastFailure(),
    ) { pending, failed, reason -> OutboxState(pending, failed, reason) }

    private val base = combine(
        combine(container.feed.observeFeed(), combine(loading, refreshing) { l, r -> l to r }) { posts, lr ->
            posts to lr
        },
        combine(composer, error) { c, e -> c to e },
        combine(container.networkMonitor.status(), outboxFlow) { n, o -> n to o },
        container.database.profileDao().observeMe(),
    ) { feedPair, composerPair, networkPair, profile ->
        Base(
            posts = feedPair.first,
            loading = feedPair.second.first,
            refreshing = feedPair.second.second,
            composerText = composerPair.first.text,
            posting = composerPair.first.posting,
            composerMediaLabel = composerPair.first.mediaLabel,
            error = composerPair.second,
            network = networkPair.first,
            pendingOperations = networkPair.second.pending,
            failedOperations = networkPair.second.failed,
            lastFailure = networkPair.second.lastFailure,
            hasProfile = profile?.handle?.isNotBlank() == true,
            myId = profile?.id,
        )
    }

    private val activeStories = combine(container.stories.observeStories(0), now) { stories, instant ->
        // Expiry is enforced here as well as in the DAO query: the list can sit on screen for a
        // long time, and a 24-hour story must vanish at the right minute, not on next launch.
        stories.filterNot { it.isExpired(instant) }
    }

    val state: StateFlow<UiState> = combine(base, activeStories, storyDraft, eventDraft) { b, stories, draft, event ->
        UiState(
            posts = b.posts,
            stories = stories,
            loading = b.loading,
            refreshing = b.refreshing,
            composerText = b.composerText,
            posting = b.posting,
            composerMediaLabel = b.composerMediaLabel,
            error = b.error,
            network = b.network,
            pendingOperations = b.pendingOperations,
            failedOperations = b.failedOperations,
            lastFailure = b.lastFailure,
            hasProfile = b.hasProfile,
            myId = b.myId,
            storyDraft = draft,
            eventDraft = event,
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

    fun onPostMediaSelected(
        bytes: ByteArray,
        mediaType: String,
        mimeType: String,
        extension: String,
        label: String,
    ) {
        composer.value = composer.value.copy(
            media = bytes,
            mediaType = mediaType,
            mimeType = mimeType,
            extension = extension,
            mediaLabel = label,
        )
    }

    fun clearPostMedia() {
        composer.value = composer.value.copy(
            media = null,
            mediaType = null,
            mimeType = null,
            mediaLabel = null,
        )
    }

    fun publish() {
        val draft = composer.value
        if (draft.text.isBlank() && draft.media == null) return
        viewModelScope.launch {
            composer.value = draft.copy(posting = true)
            val result = container.feed.createPost(
                body = draft.text,
                media = draft.media,
                mediaType = draft.mediaType,
                mimeType = draft.mimeType,
                extension = draft.extension,
            )
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

    // -------------------------------------------------------------- événements

    fun openEventComposer() {
        eventDraft.value = EventDraft()
    }

    fun closeEventComposer() {
        eventDraft.value = null
    }

    fun onEventTitleChange(value: String) {
        eventDraft.value = eventDraft.value?.copy(title = value.take(120), error = null)
    }

    fun onEventDateChange(value: String) {
        eventDraft.value = eventDraft.value?.copy(date = value.take(10), error = null)
    }

    fun onEventTimeChange(value: String) {
        eventDraft.value = eventDraft.value?.copy(time = value.take(5), error = null)
    }

    fun onEventPlaceChange(value: String) {
        eventDraft.value = eventDraft.value?.copy(place = value.take(120))
    }

    fun onEventDetailsChange(value: String) {
        eventDraft.value = eventDraft.value?.copy(details = value.take(400))
    }

    /** Un événement est une publication : même stockage, mêmes règles de visibilité. */
    fun publishEvent() {
        val draft = eventDraft.value ?: return
        if (draft.title.isBlank()) {
            eventDraft.value = draft.copy(error = "Un titre est nécessaire.")
            return
        }
        val startsAt = runCatching {
            LocalDate.parse(draft.date.trim())
                .atTime(LocalTime.parse(draft.time.trim()))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString()
        }.getOrNull()
        if (startsAt == null) {
            eventDraft.value = draft.copy(error = "Date au format AAAA-MM-JJ et heure HH:MM.")
            return
        }
        val body = EventCodec.encodeEvent(draft.title, startsAt, draft.place, draft.details)
        if (body == null) {
            eventDraft.value = draft.copy(error = "Événement invalide.")
            return
        }
        viewModelScope.launch {
            eventDraft.value = draft.copy(posting = true, error = null)
            when (val result = container.feed.createPost(body = body)) {
                is Outcome.Success -> {
                    eventDraft.value = null
                    runCatching { container.sync.drain() }
                }
                is Outcome.Failure -> {
                    // Hors ligne, la publication est déjà dans la file : on ferme sans mentir.
                    if (result.error.isOffline) {
                        eventDraft.value = null
                    } else {
                        error.value = result.error
                        eventDraft.value = draft.copy(posting = false, error = result.error.userMessage)
                    }
                }
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

    fun attachStoryMedia(
        bytes: ByteArray,
        mediaType: String,
        mimeType: String,
        extension: String,
        label: String,
    ) {
        storyDraft.value = storyDraft.value?.copy(
            media = bytes,
            mediaType = mediaType,
            mimeType = mimeType,
            extension = extension,
            mediaLabel = label,
        )
    }

    fun clearStoryMedia() {
        storyDraft.value = storyDraft.value?.copy(
            media = null,
            mediaType = null,
            mimeType = null,
            mediaLabel = null,
        )
    }

    /** Public URL of a story's photo or video, or null for a text-only story. */
    fun mediaUrl(story: Story): String? = container.stories.mediaUrl(story.mediaPath)

    fun publishStory() {
        val draft = storyDraft.value ?: return
        if (draft.text.isBlank() && draft.media == null) return
        viewModelScope.launch {
            storyDraft.value = draft.copy(posting = true)
            when (
                val result = container.stories.create(
                    body = draft.text,
                    background = draft.background,
                    media = draft.media,
                    mediaType = draft.mediaType,
                    mimeType = draft.mimeType,
                    extension = draft.extension,
                )
            ) {
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

    /**
     * Remet en file les opérations que le serveur avait refusées, puis les renvoie tout de suite.
     * Sans cette action, un refus définitif restait invisible et définitif.
     */
    fun retryFailedOperations() {
        viewModelScope.launch {
            val revived = runCatching { container.sync.retryFailed() }.getOrDefault(0)
            if (revived > 0) error.value = null
        }
    }

    /** Abandonne les opérations refusées, sur décision explicite de l'utilisateur. */
    fun discardFailedOperations() {
        viewModelScope.launch {
            runCatching { container.sync.discardFailed() }
        }
    }
}
