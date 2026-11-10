package tomate.app.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tomate.core.model.AppError
import tomate.core.model.Comment
import tomate.core.model.Outcome
import tomate.core.model.Post
import tomate.core.model.ReportDraft
import tomate.core.model.ReportReason
import tomate.core.model.ReportTargetType
import kotlinx.coroutines.flow.firstOrNull
import tomate.data.container.TomateContainer
import tomate.data.extras.EventCodec
import tomate.data.repo.FeedRepository

class PostDetailViewModel(
    private val container: TomateContainer,
    private val postId: String,
) : ViewModel() {

    data class UiState(
        val post: Post? = null,
        val comments: List<Comment> = emptyList(),
        val loading: Boolean = true,
        val commentText: String = "",
        val sending: Boolean = false,
        val error: AppError? = null,
        val reported: Boolean = false,
        val blockedAuthor: Boolean = false,
        val isMine: Boolean = false,
        /** Participants réels d'un événement, lus sur le serveur. Null hors événement. */
        val participation: FeedRepository.Participation? = null,
    )

    private val loading = MutableStateFlow(true)
    private val commentText = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val reported = MutableStateFlow(false)
    private val blocked = MutableStateFlow(false)
    private val participation = MutableStateFlow<FeedRepository.Participation?>(null)

    val state: StateFlow<UiState> = combine(
        container.feed.observePost(postId),
        container.feed.observeComments(postId),
        combine(loading, combine(commentText, sending) { t, s -> t to s }) { l, pair -> Triple(l, pair.first, pair.second) },
        combine(error, combine(reported, blocked) { r, b -> r to b }) { e, pair -> Triple(e, pair.first, pair.second) },
        participation,
    ) { post, comments, loadingTriple, errorTriple, eventParticipation ->
        val (isLoading, text, isSending) = loadingTriple
        val (currentError, isReported, isBlocked) = errorTriple
        UiState(
            post = post,
            comments = comments,
            loading = isLoading,
            commentText = text,
            sending = isSending,
            error = currentError,
            reported = isReported,
            blockedAuthor = isBlocked,
            isMine = post != null && post.authorId == container.session.currentUserId,
            participation = eventParticipation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            container.feed.refreshComments(postId)
            runCatching { container.sync.drain() }
            loadParticipation()
            loading.value = false
        }
    }

    fun onCommentChange(text: String) {
        commentText.value = text.take(1000)
    }

    fun sendComment() {
        if (commentText.value.isBlank()) return
        viewModelScope.launch {
            sending.value = true
            val result = container.feed.addComment(postId, commentText.value)
            when (result) {
                is Outcome.Success -> commentText.value = ""
                is Outcome.Failure -> error.value = result.error
            }
            sending.value = false
        }
    }

    /** Ne lit la participation que pour un vrai événement : aucun appel inutile ailleurs. */
    private suspend fun loadParticipation() {
        val post = container.feed.observePost(postId).firstOrNull() ?: return
        if (EventCodec.decodeEvent(post.body) == null) return
        when (val result = container.feed.participation(postId)) {
            is Outcome.Success -> participation.value = result.value
            is Outcome.Failure -> Unit
        }
    }

    fun toggleParticipation() {
        val current = participation.value ?: return
        viewModelScope.launch {
            when (val result = container.feed.setParticipation(postId, !current.mine)) {
                is Outcome.Success -> loadParticipation()
                is Outcome.Failure -> {
                    if (result.error.isOffline) {
                        // L'opération est dans la file locale : le compteur reste celui du
                        // serveur, mais on dit clairement qu'elle partira plus tard.
                        error.value = AppError.Offline("Participation enregistrée hors ligne : elle partira au retour du réseau.")
                    } else {
                        error.value = result.error
                    }
                }
            }
        }
    }

    fun react() {
        val post = state.value.post ?: return
        viewModelScope.launch { container.feed.toggleReaction(post.id, post.reactedByMe) }
    }

    private val editing = MutableStateFlow(false)
    private val editText = MutableStateFlow("")

    fun editingFlows(): kotlinx.coroutines.flow.Flow<Pair<Boolean, String>> =
        kotlinx.coroutines.flow.combine(editing, editText) { e, t -> e to t }

    fun startEdit() {
        editText.value = state.value.post?.body.orEmpty()
        editing.value = true
    }

    fun onEditChange(value: String) {
        editText.value = value.take(2000)
    }

    fun cancelEdit() {
        editing.value = false
    }

    fun confirmEdit() {
        viewModelScope.launch {
            when (val result = container.feed.editPost(postId, editText.value)) {
                is Outcome.Success -> editing.value = false
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            when (val result = container.feed.deletePost(postId)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> error.value = result.error
            }
        }
    }

    fun report(reason: ReportReason) {
        viewModelScope.launch {
            val result = container.moderation.report(
                ReportDraft(ReportTargetType.Post, postId, reason),
            )
            when (result) {
                is Outcome.Success -> reported.value = true
                is Outcome.Failure -> {
                    // A queued report is still a recorded report locally: be explicit in the UI.
                    reported.value = result.error.isOffline
                    if (!result.error.isOffline) error.value = result.error
                }
            }
        }
    }

    fun blockAuthor() {
        val post = state.value.post ?: return
        viewModelScope.launch {
            val result = container.moderation.block(post.authorId, post.authorHandle)
            if (result is Outcome.Success) blocked.value = true
            else error.value = (result as Outcome.Failure).error
        }
    }

    fun dismissError() {
        error.value = null
    }
}
