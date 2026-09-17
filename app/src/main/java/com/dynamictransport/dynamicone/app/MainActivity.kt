package com.dynamictransport.dynamicone.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dynamictransport.dynamicone.app.navigation.DynamicOneApp
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.push.NotificationNavigation
import com.dynamictransport.dynamicone.domain.model.SessionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val appViewModel: AppViewModel by viewModels()

    @Inject
    lateinit var notificationNavigation: NotificationNavigation

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Short splash: only until the local session check is done (no network wait).
        splashScreen.setKeepOnScreenCondition { appViewModel.sessionState.value is SessionState.Loading }
        enableEdgeToEdge()
        // A tapped notification opens its conversation; a recreated activity does not open it again.
        if (savedInstanceState == null) notificationNavigation.handle(intent)
        setContent {
            DynamicOneTheme {
                DynamicOneApp(appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationNavigation.handle(intent)
    }
}
