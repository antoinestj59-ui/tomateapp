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
import tomate.data.container.TomateContainer

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
    )

    private val loading = MutableStateFlow(true)
    private val commentText = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val reported = MutableStateFlow(false)
    private val blocked = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.feed.observePost(postId),
        container.feed.observeComments(postId),
        combine(loading, combine(commentText, sending) { t, s -> t to s }) { l, pair -> Triple(l, pair.first, pair.second) },
        combine(error, combine(reported, blocked) { r, b -> r to b }) { e, pair -> Triple(e, pair.first, pair.second) },
    ) { post, comments, loadingTriple, errorTriple ->
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

    fun react() {
        val post = state.value.post ?: return
        viewModelScope.launch { container.feed.toggleReaction(post.id, post.reactedByMe) }
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
