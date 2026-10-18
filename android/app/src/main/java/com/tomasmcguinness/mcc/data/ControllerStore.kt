package com.tomasmcguinness.mcc.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where MCC's controllers live, plus which controller a commissioning attempt is for.
 *
 * Google Play Services binds to [com.tomasmcguinness.mcc.commissioning.MccCommissioningService]
 * on its own, with nothing to say which controller the user started from, so the app records
 * that here right before it starts the flow.
 */
object ControllerStore {

    private const val CONTROLLERS_KEY = "controllers"
    private const val PENDING_COMMISSIONING_CONTROLLER_ID_KEY = "pendingCommissioningControllerId"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("controllers", Context.MODE_PRIVATE)
    }

    // region Controllers

    var controllers: List<Controller>
        get() {
            val json = prefs.getString(CONTROLLERS_KEY, null) ?: return emptyList()

            return try {
                Json.decodeFromString<List<Controller>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
        private set(value) {
            prefs.edit { putString(CONTROLLERS_KEY, Json.encodeToString(value)) }
        }

    fun add(controller: Controller) {
        controllers = controllers + controller
    }

    /** Replaces the stored controller with the same id, keeping its position in the list. */
    fun update(controller: Controller) {
        controllers = controllers.map { if (it.id == controller.id) controller else it }
    }

    fun remove(id: String) {
        controllers = controllers.filterNot { it.id == id }
    }

    fun controller(id: String): Controller? = controllers.firstOrNull { it.id == id }

    // endregion

    // region Commissioning handoff

    /**
     * Written by the app right before it starts a commissioning attempt on a given controller;
     * read by the commissioning service so it knows which controller's API to talk to.
     */
    var pendingCommissioningControllerId: String?
        get() = prefs.getString(PENDING_COMMISSIONING_CONTROLLER_ID_KEY, null)
        set(value) {
            prefs.edit {
                if (value != null) {
                    putString(PENDING_COMMISSIONING_CONTROLLER_ID_KEY, value)
                } else {
                    remove(PENDING_COMMISSIONING_CONTROLLER_ID_KEY)
                }
            }
        }

    // endregion
}
