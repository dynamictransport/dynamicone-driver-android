package com.dynamictransport.dynamicone.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.dynamictransport.dynamicone.core.design.layout.applyScreenOrientationPolicy
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.push.EXTRA_CALL_ID
import com.dynamictransport.dynamicone.core.wakeup.WakeUpCallController
import com.dynamictransport.dynamicone.feature.wakeup.WakeUpCallScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The planner's wake-up call over the lock screen (docs/25 §2). Opened only by the call's full-screen notification. */
@AndroidEntryPoint
class WakeUpCallActivity : ComponentActivity() {

    @Inject
    lateinit var controller: WakeUpCallController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenOrientationPolicy()
        showOverLockScreen()
        enableEdgeToEdge()
        if (!ringsNow(intent)) return
        setContent {
            DynamicOneTheme {
                val call by controller.ringing.collectAsState()
                call?.let { ringing ->
                    WakeUpCallScreen(
                        call = ringing,
                        onAcknowledge = {
                            lifecycleScope.launch {
                                controller.acknowledge(ringing.callId)
                                finish()
                            }
                        },
                    )
                }
            }
        }
        // Answered on the notification, stopped by the server or rung out: the screen closes with the sound.
        lifecycleScope.launch {
            controller.ringing.drop(1).collect { ringing -> if (ringing == null) finish() }
        }
    }

    /** A newer call while the screen is open shows that call (the controller already rings it). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ringsNow(intent)
    }

    private fun ringsNow(intent: Intent?): Boolean {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)?.takeIf { it.isNotBlank() }
        val ringing = controller.ringing.value
        if (callId == null || ringing == null || ringing.callId != callId) {
            finish()
            return false
        }
        return true
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
}
