package com.dynamictransport.dynamicone.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dynamictransport.dynamicone.core.alarm.AndroidRestAlarmNotifier
import com.dynamictransport.dynamicone.core.alarm.RestAlarmController
import com.dynamictransport.dynamicone.core.alarm.RestAlarmIntents
import com.dynamictransport.dynamicone.core.alarm.RestAlarmIntents.rest
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.RestWindow
import com.dynamictransport.dynamicone.feature.restalarm.RestAlarmScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The wake-up alarm over the lock screen (docs/16 §1). Opened by the alarm notification's full-screen intent. */
@AndroidEntryPoint
class RestAlarmActivity : ComponentActivity() {

    @Inject
    lateinit var controller: RestAlarmController

    private data class Shown(val rest: RestWindow, val test: Boolean)

    private var shown by mutableStateOf<Shown?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        if (!show(intent)) return
        setContent {
            DynamicOneTheme {
                shown?.let { (rest, test) ->
                    RestAlarmScreen(
                        restEnd = rest.end,
                        test = test,
                        onStartShift = { answer { controller.stop(rest); startActivity(RestAlarmIntents.startShift(this)) } },
                        onSnooze = { answer { controller.snooze(rest) } },
                        onStop = { answer { if (test) controller.stopTest() else controller.stop(rest) } },
                    )
                }
            }
        }
        // Follows the ringing alarm: another alarm replaces the one on screen (the system shows a newer alarm only as a
        // notification while this screen is on); answered elsewhere (notification action, shift started) it closes.
        lifecycleScope.launch {
            controller.ringing.drop(1).collect { alarm ->
                if (alarm == null) finish() else shown = Shown(alarm.rest, alarm.test)
            }
        }
        // Like the sound, the screen does not stay on for ever.
        lifecycleScope.launch {
            delay(AndroidRestAlarmNotifier.RING_TIMEOUT.toMillis())
            finish()
        }
    }

    /** A newer alarm (e.g. the snoozed one) while the screen is open shows that alarm. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent): Boolean {
        val rest = intent.rest()
        if (rest == null) {
            finish()
            return false
        }
        shown = Shown(rest, intent.getBooleanExtra(RestAlarmIntents.EXTRA_TEST, false))
        return true
    }

    private fun answer(block: suspend () -> Unit) {
        lifecycleScope.launch {
            block()
            finish()
        }
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
