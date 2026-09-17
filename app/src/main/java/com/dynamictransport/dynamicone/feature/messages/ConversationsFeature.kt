package com.dynamictransport.dynamicone.feature.messages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dynamictransport.dynamicone.R
import com.dynamictransport.dynamicone.core.design.component.ClickableCard
import com.dynamictransport.dynamicone.core.design.component.DriverTopBar
import com.dynamictransport.dynamicone.core.design.component.EmptyState
import com.dynamictransport.dynamicone.core.design.component.SecondaryActionButton
import com.dynamictransport.dynamicone.core.design.component.StatusBanner
import com.dynamictransport.dynamicone.core.design.component.StatusTone
import com.dynamictransport.dynamicone.core.design.theme.Dimens
import com.dynamictransport.dynamicone.core.design.theme.DriverTheme
import com.dynamictransport.dynamicone.core.push.MessageNotifier
import com.dynamictransport.dynamicone.domain.model.ConversationSummary
import com.dynamictransport.dynamicone.domain.model.MessageDirection
import com.dynamictransport.dynamicone.domain.model.RefreshResult
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.MessageRepository
import com.dynamictransport.dynamicone.feature.common.DriverFormats
import com.dynamictransport.dynamicone.feature.common.currentLocale
import com.dynamictransport.dynamicone.feature.common.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    /** The general conversation first, then trip conversations with the newest message first. */
    val conversations: List<ConversationSummary> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    @param:StringRes val loadError: Int? = null,
    val stale: Boolean = false,
    val unavailable: Boolean = false,
    val isDemoMode: Boolean = false,
    /** New messages can be announced by push notifications on this build. */
    val pushAvailable: Boolean = false,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val appInfo: AppInfo,
    private val notifier: MessageNotifier,
) : ViewModel() {

    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<ConversationsUiState> = combine(
        messageRepository.observeConversations(),
        lastRefresh,
        refreshing,
        pollWhileVisible { refreshOnce() },
    ) { list, refresh, isRefreshing, _ ->
        val failed = refresh?.takeIf { it != RefreshResult.SUCCESS && it != RefreshResult.NOT_SUPPORTED }
        ConversationsUiState(
            conversations = list.conversations,
            loaded = list.loaded,
            refreshing = isRefreshing,
            loadError = failed?.takeIf { !list.loaded }?.messageRes(),
            stale = failed != null && list.loaded,
            unavailable = refresh == RefreshResult.NOT_SUPPORTED,
            isDemoMode = appInfo.isDemoMode,
            pushAvailable = isPushAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState(isDemoMode = appInfo.isDemoMode, pushAvailable = isPushAvailable))

    private val isPushAvailable: Boolean get() = appInfo.isPushConfigured && !appInfo.isDemoMode

    /** Checked whenever the list returns to the screen: the driver may have changed it in the system settings. */
    fun notificationsEnabled(): Boolean = notifier.canNotify()

    fun onRetry() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce(): RefreshResult {
        refreshing.value = true
        val result = messageRepository.refreshConversations()
        lastRefresh.value = result
        refreshing.value = false
        return result
    }
}

/** Unread incoming messages of all conversations, for the Messages destination of the bottom navigation. */
@HiltViewModel
class MessageBadgeViewModel @Inject constructor(messageRepository: MessageRepository) : ViewModel() {
    val unreadCount: StateFlow<Int> = messageRepository.observeConversations()
        .map { it.totalUnread }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun ConversationsRoute(onOpenConversation: (tripId: String?) -> Unit, viewModel: ConversationsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsEnabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(viewModel) {
        notificationsEnabled = viewModel.notificationsEnabled()
        onPauseOrDispose { }
    }
    // A refused request (also "don't ask again") leads to the app's notification settings.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = viewModel.notificationsEnabled()
        if (!granted) context.startActivity(notificationSettingsIntent(context))
    }
    ConversationsScreen(
        state = state,
        onOpenConversation = onOpenConversation,
        onRetry = viewModel::onRetry,
        notificationsBlocked = state.pushAvailable && !notificationsEnabled,
        onEnableNotifications = {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(notificationSettingsIntent(context))
            }
        },
    )
}

@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onOpenConversation: (tripId: String?) -> Unit,
    onRetry: () -> Unit,
    notificationsBlocked: Boolean = false,
    onEnableNotifications: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            DriverTopBar(
                title = stringResource(R.string.nav_messages),
                onBack = null,
                backContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (notificationsBlocked) {
                item(key = "notifications_blocked") {
                    StatusBanner(
                        message = stringResource(R.string.notifications_blocked),
                        icon = Icons.Filled.NotificationsOff,
                        tone = StatusTone.Warning,
                        actionLabel = stringResource(R.string.notifications_enable),
                        onAction = onEnableNotifications,
                        modifier = Modifier.testTag("notifications_blocked"),
                    )
                }
            }
            if (state.stale) {
                item(key = "stale") {
                    StatusBanner(
                        message = stringResource(R.string.data_stale),
                        icon = Icons.Filled.SyncProblem,
                        tone = StatusTone.Warning,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = onRetry,
                        modifier = Modifier.testTag("conversations_stale"),
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
                !state.loaded && loadError != null -> item(key = "error") {
                    EmptyState(icon = Icons.Filled.CloudOff, title = stringResource(loadError), body = null, modifier = Modifier.testTag("conversations_error")) {
                        Spacer(Modifier.height(12.dp))
                        SecondaryActionButton(text = stringResource(R.string.common_retry), onClick = onRetry, icon = Icons.Filled.Refresh)
                    }
                }
                !state.loaded -> item(key = "loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("conversations_loading"))
                }
                else -> items(state.conversations, key = { it.tripId ?: GENERAL_KEY }) { conversation ->
                    ConversationRow(conversation, onClick = { onOpenConversation(conversation.tripId) })
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    val locale = currentLocale()
    val colors = DriverTheme.colors
    val key = conversation.tripId ?: GENERAL_KEY
    val unread = conversation.unreadCount
    ClickableCard(onClick = onClick, modifier = Modifier.testTag("conversation_$key")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
            Icon(
                imageVector = if (conversation.tripId == null) Icons.Filled.Forum else Icons.Filled.LocalShipping,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        conversation.tripId == null -> stringResource(R.string.messages_general_title)
                        conversation.tripNo != null -> stringResource(R.string.messages_trip_label, conversation.tripNo)
                        else -> stringResource(R.string.trip_messages)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val last = conversation.lastMessage
                Text(
                    text = if (last == null) {
                        stringResource(R.string.messages_empty)
                    } else {
                        val preview = last.body.replace('\n', ' ')
                        when {
                            last.direction == MessageDirection.FROM_DRIVER -> stringResource(R.string.messages_preview_you, preview)
                            last.senderName != null -> stringResource(R.string.messages_preview_sender, last.senderName, preview)
                            else -> stringResource(R.string.messages_preview_office, preview)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                conversation.lastMessage?.createdAt?.let {
                    Text(DriverFormats.dayTime(it, locale), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                if (unread > 0) {
                    Spacer(Modifier.height(4.dp))
                    UnreadCountBadge(unread)
                }
            }
        }
    }
}

/** A count in a filled circle; read out as "N unread" (never colour alone). */
@Composable
fun UnreadCountBadge(count: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.messages_unread, count, count)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private const val GENERAL_KEY = "general"
private const val MAX_BADGE_COUNT = 99
