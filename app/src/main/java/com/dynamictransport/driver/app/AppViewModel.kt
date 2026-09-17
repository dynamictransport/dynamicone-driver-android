package com.dynamictransport.driver.app

import androidx.lifecycle.ViewModel
import com.dynamictransport.driver.domain.model.SessionState
import com.dynamictransport.driver.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = authRepository.sessionState
}
