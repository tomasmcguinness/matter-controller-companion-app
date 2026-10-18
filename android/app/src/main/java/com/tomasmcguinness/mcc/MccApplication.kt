package com.tomasmcguinness.mcc

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.data.RelayCredentialStore
import com.tomasmcguinness.mcc.notifications.PushManager

class MccApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        ControllerStore.init(this)
        RelayCredentialStore.init(this)
        PushManager.init(this)

        createNotificationChannel()

        PushManager.handleLaunch()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Controllers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifications your Matter controllers send."
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        /** Also named in the manifest, and by the relay when it sends to FCM. */
        const val CHANNEL_ID = "controllers"

        fun showNotification(context: Context, title: String, body: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}
