package com.tomasmcguinness.mcc.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tomasmcguinness.mcc.MccApplication

class MccMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushManager.didRegister(token)
    }

    /**
     * Only called while MCC is in the foreground; otherwise FCM shows the notification itself.
     * Show controller notifications even while MCC is open, rather than dropping them.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return

        MccApplication.showNotification(this, notification.title.orEmpty(), notification.body.orEmpty())
    }
}
