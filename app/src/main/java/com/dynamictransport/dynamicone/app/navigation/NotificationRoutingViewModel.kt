package com.dynamictransport.dynamicone.app.navigation

import android.os.Build
import androidx.lifecycle.ViewModel
import com.dynamictransport.dynamicone.core.push.ConversationOpenRequest
import com.dynamictransport.dynamicone.core.push.MessageNotifier
import com.dynamictransport.dynamicone.core.push.NotificationNavigation
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Notification taps and the one-time notification permission request of the signed-in app. */
@HiltViewModel
class NotificationRoutingViewModel @Inject constructor(
    private val navigation: NotificationNavigation,
    private val notifier: MessageNotifier,
    private val appInfo: AppInfo,
) : ViewModel() {

    val openRequest: StateFlow<ConversationOpenRequest?> = navigation.pending

    /** The wake-up alarm opened the app on the daily shift start. */
    val startShiftRequest: StateFlow<Boolean> = navigation.startShiftRequested

    fun onOpenRequestHandled() = navigation.consume()

    fun onStartShiftRequestHandled() = navigation.consumeStartShift()

    /** Android 13+, push set up for the real backend, not allowed yet, and not asked before in this process. */
    fun shouldAskForNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appInfo.isPushConfigured && !appInfo.isDemoMode && !navigation.permissionAsked && !notifier.canNotify()

    fun onNotificationPermissionAsked() {
        navigation.permissionAsked = true
    }
}
