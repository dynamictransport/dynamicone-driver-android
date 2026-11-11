package com.dynamictransport.dynamicone.app

import androidx.lifecycle.ViewModel
import com.dynamictransport.dynamicone.domain.model.SessionState
import com.dynamictransport.dynamicone.domain.repository.AppInfo
import com.dynamictransport.dynamicone.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    appInfo: AppInfo,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = authRepository.sessionState

    /** A company phone is enrolled before anyone signs in: the sign-in screen offers the way there (docs/29). */
    val deviceManagementAvailable: Boolean = appInfo.isDeviceManagementEnabled
}
