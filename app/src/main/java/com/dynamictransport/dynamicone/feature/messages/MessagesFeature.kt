package com.dynamictransport.dynamicone.feature.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.layout.readableWidth
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.push.MessageNotifier
import com.dynamictransport.dynamicone.core.push.OpenConversationTracker
import com.dynamictransport.dynamicone.domain.model.ActionRejection
import com.dynamictransport.dynamicone.domain.model.ActionResult
import com.dynamictransport.dynamicone.domain.model.Conversation
import com.dynamictransport.dynamicone.domain.model.DriverMessage
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.PendingCommandState
import com.dynamictransport.dynamicone.domain.model.PendingMessage
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.domain.repository.TripRepository
import com.dynamictransport.dynamicone.domain.rules.DriverInputRules
import com.dynamictransport.dynamicone.feature.common.ActionBar
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.MessageEffect
import com.dynamictransport.dynamicone.feature.common.SingleFlight
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.messageRes
import com.dynamictransport.dynamicone.feature.tripdetail.TRIP_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads a message screen while it is on screen: the flow runs only while the UI collects the state, so reading
 * stops in the background. Failures back off; a rate limit is honoured by the API layer. NOT_SUPPORTED ends it.
 */
internal fun pollWhileVisible(read: suspend () -> RefreshResult): Flow<Unit> = flow {
    var interval = MessagesViewModel.POLL_INTERVAL_MILLIS
    while (true) {
        val result = read()
        if (result == RefreshResult.NOT_SUPPORTED) return@flow
        interval = if (result == RefreshResult.SUCCESS) {
            MessagesViewModel.POLL_INTERVAL_MILLIS
        } else {
            (interval * 2).coerceAtMost(MessagesViewModel.MAX_BACKOFF_MILLIS)
        }
        emit(Unit)
        delay(interval)
    }
}.onStart { emit(Unit) }

data class MessagesUiState(
    /** Null: the general conversation with the office. */
    val tripId: String? = null,
    val tripNo: String? = null,
    val conversation: Conversation = Conversation.empty(null),
    val draft: String = "",
    val refreshing: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    val stale: Boolean = false,
    val unavailable: Boolean = false,
    val isDemoMode: Boolean = false,
    val sending: Boolean = false,
    @param:StringRes val message: Int? = null,
) {
    val canSend: Boolean get() = !sending && !unavailable && DriverInputRules.validateMessage(draft) == null
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    tripRepository: TripRepository,
    private val appInfo: AppInfo,
    private val openConversations: OpenConversationTracker,
    private val notifier: MessageNotifier,
) : ViewModel() {

    private val tripId: String? = savedStateHandle[TRIP_ID_ARG]
    private val draft = MutableStateFlow(savedStateHandle.get<String>(KEY_DRAFT).orEmpty())
    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<Int?>(null)
    private val singleFlight = SingleFlight()

    private val polling: Flow<Unit> = pollWhileVisible { refreshOnce() }

    /** From the trip list, or the number the conversation list looked up for a trip that is not in it. */
    private val tripNo: Flow<String?> = tripId?.let { id ->
        combine(
            tripRepository.observeTrip(id).map { it?.tripNo },
            messageRepository.observeConversations().map { list -> list.conversations.firstOrNull { it.tripId == id }?.tripNo },
        ) { fromTrips, fromConversations -> fromTrips ?: fromConversations }
    } ?: flowOf(null)

    private val status = combine(lastRefresh, refreshing, singleFlight.busy, message) { refresh, isRefreshing, busy, currentMessage ->
        Status(refresh, isRefreshing, busy, currentMessage)
    }

    private data class Status(val refresh: RefreshResult?, val refreshing: Boolean, val sending: Boolean, val message: Int?)

    val uiState: StateFlow<MessagesUiState> = combine(
        messageRepository.observeConversation(tripId),
        draft,
        status,
        tripNo,
        polling,
    ) { conversation, currentDraft, current, number, _ ->
        val failed = current.refresh?.takeIf { it != RefreshResult.SUCCESS && it != RefreshResult.NOT_SUPPORTED }
        MessagesUiState(
            tripId = tripId,
            tripNo = number,
            conversation = conversation,
            draft = currentDraft,
            refreshing = current.refreshing,
            loadError = failed?.takeIf { !conversation.loaded }?.messageRes(),
            stale = failed != null && conversation.loaded,
            unavailable = current.refresh == RefreshResult.NOT_SUPPORTED,
            isDemoMode = appInfo.isDemoMode,
            sending = current.sending,
            message = current.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState(tripId = tripId, isDemoMode = appInfo.isDemoMode))

    fun onDraftChanged(value: String) {
        val limited = value.take(DriverInputRules.MESSAGE_MAX_LENGTH)
        draft.value = limited
        savedStateHandle[KEY_DRAFT] = limited
    }

    /** The message is stored with its command id before sending; a stored message is resent from the list. */
    fun onSend() {
        val body = draft.value
        DriverInputRules.validateMessage(body)?.let {
            message.value = it.messageRes()
            return
        }
        singleFlight.launch(viewModelScope) {
            val result = messageRepository.send(tripId, body)
            if (result is ActionResult.Rejected) message.value = result.reason.messageRes()
            if (result == ActionResult.Queued || (result as ActionResult.Rejected).reason !in NOT_STORED) onDraftChanged("")
        }
    }

    fun onResend(commandId: String) {
        singleFlight.launch(viewModelScope) {
            (messageRepository.resend(commandId) as? ActionResult.Rejected)?.let { message.value = it.reason.messageRes() }
        }
    }

    fun onDiscard(commandId: String) {
        viewModelScope.launch { messageRepository.discard(commandId) }
    }

    fun onLoadOlder() {
        viewModelScope.launch { messageRepository.loadOlder(tripId).messageRes()?.let { message.value = it } }
    }

    /** Read receipts only for incoming messages that are actually displayed. */
    fun onMessageVisible(item: DriverMessage) {
        if (item.direction != MessageDirection.TO_DRIVER || item.readAt != null) return
        viewModelScope.launch { messageRepository.markRead(tripId, item.id) }
    }

    fun onRetry() {
        viewModelScope.launch { refreshOnce() }
    }

    fun onMessageShown() {
        message.value = null
    }

    /** On screen the conversation is read by polling: no notification for it, and its notification goes away. */
    fun onVisible() {
        openConversations.open(tripId)
        notifier.cancel(tripId)
    }

    fun onHidden() {
        openConversations.close(tripId)
    }

    private suspend fun refreshOnce(): RefreshResult {
        refreshing.value = true
        val result = messageRepository.refresh(tripId)
        lastRefresh.value = result
        refreshing.value = false
        return result
    }

    companion object {
        /** Client choice, not a backend guarantee: the driver API allows 120 requests per minute per user. */
        const val POLL_INTERVAL_MILLIS = 25_000L
        const val MAX_BACKOFF_MILLIS = 120_000L
        private const val KEY_DRAFT = "message_draft"

        /** Rejections that happen before the message is stored, or that remove it: the text stays in the field. */
        private val NOT_STORED = setOf(
            ActionRejection.NOTE_REQUIRED,
            ActionRejection.NOTE_TOO_LONG,
            ActionRejection.NOT_LOGGED_IN,
            ActionRejection.NOT_SUPPORTED,
            ActionRejection.CONFLICT,
            ActionRejection.NOT_FOUND,
            ActionRejection.FAILED,
        )
    }
}

@Composable
fun MessagesRoute(onBack: (() -> Unit)?, viewModel: MessagesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    MessagesScreen(
        state = state,
        onBack = onBack,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::onSend,
        onResend = viewModel::onResend,
        onDiscard = viewModel::onDiscard,
        onLoadOlder = viewModel::onLoadOlder,
        onMessageVisible = viewModel::onMessageVisible,
        onRetry = viewModel::onRetry,
        onMessageShown = viewModel::onMessageShown,
    )
}

@Composable
fun MessagesScreen(
    state: MessagesUiState,
    onBack: (() -> Unit)?,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onResend: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onMessageVisible: (DriverMessage) -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHostState, onMessageShown)
    val conversation = state.conversation
    val listState = rememberLazyListState()
    val itemCount = conversation.messages.size + conversation.pending.size
    // Newest at the bottom: follow new messages.
    LaunchedEffect(itemCount) {
        if (itemCount == 0) return@LaunchedEffect
        // The effect starts before the new items are laid out: wait for them, then follow the newest one.
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= itemCount }
        listState.animateScrollToItem(total - 1)
    }
    // Read receipts only for incoming messages the driver can actually see: the screen is resumed, the list is not
    // scrolling past them, and at least half of the message stays visible for a moment. A notification is no read.
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages by rememberUpdatedState(conversation.messages)
    val reportVisible by rememberUpdatedState(onMessageVisible)
    LaunchedEffect(listState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { if (listState.isScrollInProgress) emptySet() else listState.layoutInfo.mostlyVisibleKeys() }
                .collectLatest { keys ->
                    if (keys.isEmpty()) return@collectLatest
                    delay(MESSAGE_SEEN_AFTER_MILLIS)
                    messages.filter { it.id in keys }.forEach { reportVisible(it) }
                }
        }
    }

    Scaffold(
        topBar = {
            DriverTopBar(
                title = if (state.tripId == null) {
                    stringResource(R.string.messages_general_title)
                } else {
                    stringResource(R.string.messages_trip_title, state.tripNo ?: stringResource(R.string.value_unknown))
                },
                onBack = onBack,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
        bottomBar = {
            if (!state.unavailable) {
                ActionBar {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.draft,
                            onValueChange = onDraftChanged,
                            label = { Text(stringResource(R.string.messages_input)) },
                            supportingText = {
                                Text(stringResource(R.string.counter_of, state.draft.length, DriverInputRules.MESSAGE_MAX_LENGTH))
                            },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input"),
                        )
                        Spacer(Modifier.size(8.dp))
                        FilledIconButton(
                            onClick = onSend,
                            enabled = state.canSend,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("message_send"),
                        ) {
                            if (state.sending) {
                                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.messages_send))
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .readableWidth(),
        ) {
            if (state.stale) {
                item(key = "stale") {
                    StatusBanner(
                        message = stringResource(R.string.data_stale),
                        icon = Icons.Filled.SyncProblem,
                        tone = StatusTone.Warning,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = onRetry,
                        modifier = Modifier.testTag("messages_stale"),
                    )
                }
            }
            val loadError = state.loadError
            when {
                state.unavailable -> item(key = "unavailable") {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(if (state.isDemoMode) R.string.feature_demo_unavailable else R.string.feature_not_connected),
                        body = null,
                        modifier = Modifier.testTag("messages_unavailable"),
                    )
                }
                !conversation.loaded && loadError != null -> item(key = "error") {
                    EmptyState(icon = Icons.Filled.CloudOff, title = stringResource(loadError), body = null, modifier = Modifier.testTag("messages_error")) {
                        Spacer(Modifier.height(12.dp))
                        SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
                    }
                }
                !conversation.loaded && conversation.pending.isEmpty() -> item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("messages_loading"))
                }
                else -> {
                    if (conversation.canLoadOlder) {
                        item(key = "older") {
                            SecondaryActionButton(
                                text = stringResource(R.string.messages_load_older),
                                onClick = onLoadOlder,
                                icon = Icons.Filled.ExpandLess,
                                modifier = Modifier.testTag("messages_load_older"),
                            )
                        }
                    }
                    if (conversation.messages.isEmpty() && conversation.pending.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(icon = Icons.Filled.Forum, title = stringResource(R.string.messages_empty), body = null)
                        }
                    }
                    items(conversation.messages, key = { it.id }) { item ->
                        MessageBubble(item)
                    }
                    items(conversation.pending, key = { "pending_${it.commandId}" }) { item ->
                        PendingBubble(item, enabled = !state.sending, onResend = { onResend(item.commandId) }, onDiscard = { onDiscard(item.commandId) })
                    }
                }
            }
        }
    }
}

/** How long a message stays on screen before it counts as seen. */
internal const val MESSAGE_SEEN_AFTER_MILLIS = 500L

/** Keys of the list items at least half inside the visible area; the content padding lies under the bars. */
internal fun LazyListLayoutInfo.mostlyVisibleKeys(): Set<Any> {
    val visibleEnd = viewportEndOffset - afterContentPadding
    return visibleItemsInfo.filter { isMostlyVisible(it.offset, it.size, visibleEnd) }.mapTo(HashSet()) { it.key }
}

/** Seen: at least half of the item is visible, or it fills at least half of the visible area when it is taller. */
internal fun isMostlyVisible(offset: Int, size: Int, visibleEnd: Int): Boolean {
    if (size <= 0 || visibleEnd <= 0) return false
    val overlap = minOf(offset + size, visibleEnd) - maxOf(offset, 0)
    return overlap * 2 >= minOf(size, visibleEnd)
}

@Composable
private fun MessageBubble(message: DriverMessage) {
    val locale = currentLocale()
    val outgoing = message.direction == MessageDirection.FROM_DRIVER
    val colors = DriverTheme.colors
    // The driver's own messages are blue with white text; incoming messages stay on the neutral background.
    val textColor = if (outgoing) colors.onSentMessage else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (outgoing) colors.onSentMessage.copy(alpha = SECONDARY_ALPHA) else colors.muted
    Bubble(outgoing = outgoing, tag = "message_${message.id}") {
        Text(
            // The driver's own messages stay "You"; an incoming message names its writer when the server sends it.
            text = if (outgoing) stringResource(R.string.messages_you) else message.senderName ?: stringResource(R.string.messages_office),
            style = MaterialTheme.typography.labelMedium,
            color = secondaryColor,
        )
        Text(message.body, style = MaterialTheme.typography.bodyLarge, color = textColor)
        val meta = listOfNotNull(
            message.createdAt?.let { DriverFormats.dayTime(it, locale) },
            message.readAt?.takeIf { outgoing }?.let { stringResource(R.string.messages_read, DriverFormats.time(it)) },
        )
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = secondaryColor)
        }
    }
}

@Composable
private fun PendingBubble(message: PendingMessage, enabled: Boolean, onResend: () -> Unit, onDiscard: () -> Unit) {
    val onSent = DriverTheme.colors.onSentMessage
    val failed = message.state == PendingCommandState.OUTCOME_UNKNOWN || message.state == PendingCommandState.NOT_SENT
    val buttonColor = onSent.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
    Bubble(outgoing = true, tag = "pending_message_${message.commandId}") {
        Text(stringResource(R.string.messages_you), style = MaterialTheme.typography.labelMedium, color = onSent.copy(alpha = SECONDARY_ALPHA))
        Text(message.body, style = MaterialTheme.typography.bodyLarge, color = onSent)
        // Red text would be unreadable on the blue bubble: a failure is marked with an icon and full-strength text.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (failed) {
                Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = onSent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = stringResource(message.state.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) onSent else onSent.copy(alpha = SECONDARY_ALPHA),
            )
        }
        if (failed) {
            Row {
                TextButton(onClick = onResend, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("pending_message_resend")) {
                    Text(stringResource(R.string.messages_retry), color = buttonColor)
                }
                TextButton(onClick = onDiscard, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.messages_discard), color = buttonColor)
                }
            }
        }
    }
}

@Composable
private fun Bubble(outgoing: Boolean, tag: String, content: @Composable () -> Unit) {
    val colors = DriverTheme.colors
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .then(
                    if (outgoing) {
                        Modifier.background(colors.sentMessage, BubbleShape)
                    } else {
                        // Office messages: their own bubble colour and a visible edge, never lost in the background.
                        Modifier
                            .background(colors.receivedMessage, BubbleShape)
                            .border(1.dp, colors.receivedMessageBorder, BubbleShape)
                    },
                )
                .padding(12.dp)
                .testTag(tag),
        ) { content() }
    }
}

/** Labels and times on the blue bubble: still readable white. */
private const val SECONDARY_ALPHA = 0.85f
private const val DISABLED_ALPHA = 0.5f
private val BubbleShape = RoundedCornerShape(16.dp)
