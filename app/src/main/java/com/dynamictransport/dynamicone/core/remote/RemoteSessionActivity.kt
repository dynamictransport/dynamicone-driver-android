package com.dynamictransport.dynamicone.core.remote

import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.dynamictransport.dynamicone.core.design.layout.applyScreenOrientationPolicy
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.feature.mdm.RemoteSessionConsentScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The office asks to see the screen (docs/29 §4): Accept or Decline over the lock screen, opened like a wake-up call.
 * Accept leads to the system's own screen capture dialog; only its "yes" starts the stream.
 */
@AndroidEntryPoint
class RemoteSessionActivity : ComponentActivity() {

    @Inject
    lateinit var controller: RemoteSessionController

    /** The session whose system dialog is open. */
    private var consentingSessionId: String? = null

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sessionId = consentingSessionId
        consentingSessionId = null
        val permission = result.data
        if (sessionId != null) {
            if (result.resultCode == RESULT_OK && permission != null) {
                controller.accept(sessionId, result.resultCode, permission)
            } else {
                lifecycleScope.launch { controller.decline(sessionId) }
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenOrientationPolicy()
        showOverLockScreen()
        enableEdgeToEdge()
        consentingSessionId = savedInstanceState?.getString(STATE_CONSENTING)
        if (consentingSessionId == null && !asksNow(intent)) return
        setContent {
            DynamicOneTheme {
                val pending by controller.pending.collectAsState()
                pending?.let { request ->
                    RemoteSessionConsentScreen(
                        request = request,
                        onAccept = { askForCapture(request.sessionId) },
                        onDecline = {
                            lifecycleScope.launch {
                                controller.decline(request.sessionId)
                                finish()
                            }
                        },
                    )
                }
            }
        }
        // Declined on the notification, withdrawn by the office or expired: the screen closes by itself.
        lifecycleScope.launch {
            controller.pending.drop(1).collect { pending -> if (pending == null && consentingSessionId == null) finish() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consentingSessionId == null) asksNow(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CONSENTING, consentingSessionId)
    }

    private fun asksNow(intent: Intent?): Boolean {
        val sessionId = intent?.getStringExtra(EXTRA_REMOTE_SESSION_ID)?.takeIf { it.isNotBlank() }
        if (sessionId == null || controller.pending.value?.sessionId != sessionId) {
            finish()
            return false
        }
        return true
    }

    /** The system dialog cannot open over the lock screen: the phone is unlocked first. */
    private fun askForCapture(sessionId: String) {
        if (consentingSessionId != null) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = launchCapture(sessionId)
                },
            )
        } else {
            launchCapture(sessionId)
        }
    }

    private fun launchCapture(sessionId: String) {
        if (consentingSessionId != null || controller.pending.value?.sessionId != sessionId) return
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        consentingSessionId = sessionId
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private companion object {
        const val STATE_CONSENTING = "consentingSessionId"
    }
}
