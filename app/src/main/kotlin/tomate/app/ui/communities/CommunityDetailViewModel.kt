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
import tomate.core.model.MembershipStatus
import tomate.core.model.Outcome
import tomate.core.model.Post
import tomate.data.container.TomateContainer

class CommunityDetailViewModel(
    private val container: TomateContainer,
    private val communityId: String,
) : ViewModel() {

    data class UiState(
        val community: Community? = null,
        val posts: List<Post> = emptyList(),
        val loading: Boolean = true,
        val composerText: String = "",
        val posting: Boolean = false,
        val busy: Boolean = false,
        val error: AppError? = null,
        val canPost: Boolean = false,
        val isModerator: Boolean = false,
        val members: List<tomate.data.repo.CommunityMember> = emptyList(),
        val membersLoading: Boolean = false,
    )

    private val loading = MutableStateFlow(true)
    private val composer = MutableStateFlow("")
    private val posting = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)
    private val members = MutableStateFlow<List<tomate.data.repo.CommunityMember>>(emptyList())
    private val membersLoading = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.communities.observe(communityId),
        container.feed.observeFeed(communityId),
        combine(loading, composer) { l, c -> l to c },
        combine(posting, combine(busy, error) { b, e -> b to e }) { p, be -> Triple(p, be.first, be.second) },
        combine(members, membersLoading) { list, loadingMembers -> list to loadingMembers },
    ) { community, posts, loadingPair, actionTriple, membersPair ->
        val membership = community?.myMembership
        UiState(
            community = community,
            posts = posts,
            loading = loadingPair.first,
            composerText = loadingPair.second,
            posting = actionTriple.first,
            busy = actionTriple.second,
            error = actionTriple.third,
            canPost = membership == MembershipStatus.Active,
            isModerator = community?.myRole == tomate.core.model.CommunityRole.Owner ||
                community?.myRole == tomate.core.model.CommunityRole.Moderator,
            members = membersPair.first,
            membersLoading = membersPair.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            loadMembers()
            runCatching { container.communities.refresh() }
            val result = container.feed.refreshFeed(communityId)
            if (result is Outcome.Failure) error.value = result.error
            loading.value = false
        }
    }

    fun onComposerChange(text: String) {
        composer.value = text.take(2000)
    }

    fun publish() {
        val body = composer.value
        if (body.isBlank()) return
        viewModelScope.launch {
            posting.value = true
            when (val result = container.feed.createPost(body, communityId)) {
                is Outcome.Success -> composer.value = ""
                is Outcome.Failure -> error.value = result.error
            }
            posting.value = false
        }
    }

    fun join() {
        val community = state.value.community ?: return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            when (val result = container.communities.join(community.id, community.visibility)) {
                is Outcome.Success -> {
                    runCatching { container.communities.refresh() }
                    // L'adhésion part aussi dans la file : on la vide tout de suite.
                    runCatching { container.sync.drain() }
                }
                is Outcome.Failure -> error.value = result.error
            }
            busy.value = false
        }
    }

    fun leave() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            when (val result = container.communities.leave(communityId)) {
                is Outcome.Success -> runCatching { container.communities.refresh() }
                is Outcome.Failure -> error.value = result.error
            }
            busy.value = false
        }
    }

    /** Membres visibles côté serveur (RLS) : aucune liste inventée. */
    private suspend fun loadMembers() {
        membersLoading.value = true
        when (val result = container.communities.members(communityId)) {
            is Outcome.Success -> members.value = result.value
            is Outcome.Failure -> if (!result.error.isOffline) error.value = result.error
        }
        membersLoading.value = false
    }

    fun reloadMembers() {
        viewModelScope.launch { loadMembers() }
    }

    /** Réaction réelle à une publication de la communauté (même chemin que le fil). */
    fun react(post: Post) {
        viewModelScope.launch {
            val result = container.feed.toggleReaction(post.id, post.reactedByMe)
            if (result is Outcome.Failure && !result.error.isOffline) error.value = result.error
        }
    }

    fun dismissError() {
        error.value = null
    }
}
