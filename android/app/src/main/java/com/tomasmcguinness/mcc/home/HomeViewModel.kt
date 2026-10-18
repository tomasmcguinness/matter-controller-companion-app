package com.tomasmcguinness.mcc.home

import androidx.lifecycle.ViewModel
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.data.RelayCredentialStore
import com.tomasmcguinness.mcc.notifications.PushManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel : ViewModel() {

    private val _controllers = MutableStateFlow<List<Controller>>(emptyList())
    val controllers: StateFlow<List<Controller>> = _controllers.asStateFlow()

    fun load() {
        _controllers.value = ControllerStore.controllers
    }

    fun remove(controller: Controller) {
        _controllers.value = _controllers.value.filterNot { it.id == controller.id }

        ControllerStore.remove(controller.id)
        RelayCredentialStore.removePassword(controller.id)
        PushManager.controllerRemoved(controller.id)
    }
}
