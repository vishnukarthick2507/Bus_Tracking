package com.example.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent            // ✅ ADDED
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {

        val title = message.notification?.title ?: "SmartBus"
        val body = message.notification?.body ?: ""

        /* ===========================
           ✅ ADDED: FOREGROUND SUPPORT
           =========================== */
        val intent = Intent("BUS_ALERT")
        intent.putExtra("title", title)
        intent.putExtra("body", body)
        sendBroadcast(intent)
        /* =========================== */

        val channelId = "SMARTBUS_ALERTS"

        val manager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bus Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
