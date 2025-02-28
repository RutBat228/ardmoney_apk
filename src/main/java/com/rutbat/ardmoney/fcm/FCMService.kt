package com.rutbat.ardmoney.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rutbat.ardmoney.R
import com.rutbat.ardmoney.ArdMoneyApp
import com.rutbat.ardmoney.config.ConfigManager
import com.rutbat.ardmoney.core.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class FCMService : FirebaseMessagingService() {

    private val TAG = "FCMService"
    private val CHANNEL_ID = "telegram_style_channel"
    private val GROUP_KEY_ALERTS = "com.rutbat.ardmoney.ALERTS"
    private val notificationIdCounter = AtomicInteger(1)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "onMessageReceived called, state: ${applicationState()}")
        Log.d(TAG, "Raw message data: ${remoteMessage.data}")

        try {
            val config = ConfigManager.getConfig()
            if (!config.optBoolean("fcm_enabled", true)) {
                Log.d(TAG, "FCM disabled in config")
                return
            }

            val title = remoteMessage.data["title"] ?: "Notification"
            val body = remoteMessage.data["body"] ?: "You have a new message"
            val messageId = remoteMessage.data["message_id"] ?: ""
            val imageUrl = remoteMessage.data["image_url"]

            Log.d(TAG, "Parsed Title: $title")
            Log.d(TAG, "Parsed Body: $body")
            Log.d(TAG, "Parsed Message ID: $messageId")
            Log.d(TAG, "Parsed Image URL: $imageUrl")

            sendNotification(title, body, messageId, imageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onMessageReceived: ${e.message}", e)
        }
    }

    private fun sendNotification(title: String, body: String, messageId: String, imageUrl: String?) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val uniqueId = notificationIdCounter.getAndIncrement()

        saveNotification(title, body, messageId, imageUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ardmoney Notifications",
                NotificationManager.IMPORTANCE_HIGH // Заменили IMPORTANCE_BACKGROUND на IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления в стиле Telegram"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent для открытия страницы уведомления
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("targetUrl", "https://ardmoney.ru/allert.php")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "${packageName}.OPEN_URL"
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            uniqueId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent для действия "Прочитано" (тоже через MainActivity)
        val readIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("targetUrl", "https://ardmoney.ru/allert.php")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "${packageName}.OPEN_URL"
        }
        val readPendingIntent = PendingIntent.getActivity(
            this,
            uniqueId + 1,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body.split("\n").first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_ALERTS)
            .addAction(NotificationCompat.Action(null, "Прочитано", readPendingIntent))

        val summaryBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Уведомления Ardmoney")
            .setContentText("У вас есть новые уведомления")
            .setStyle(NotificationCompat.InboxStyle()
                .setBigContentTitle("Уведомления Ardmoney")
                .addLine("$title: ${body.split("\n").first()}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY_ALERTS)
            .setGroupSummary(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (!imageUrl.isNullOrEmpty()) {
            Log.d(TAG, "Starting image load for URL: $imageUrl")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap: Bitmap = Glide.with(this@FCMService)
                        .asBitmap()
                        .load(imageUrl)
                        .override(64, 64)
                        .centerCrop()
                        .submit()
                        .get()
                    Log.d(TAG, "Image loaded successfully: width=${bitmap.width}, height=${bitmap.height}")

                    builder.setLargeIcon(bitmap)
                    notificationManager.notify(uniqueId, builder.build())
                    notificationManager.notify(0, summaryBuilder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load image from URL: ${e.message}", e)
                    notificationManager.notify(uniqueId, builder.build())
                    notificationManager.notify(0, summaryBuilder.build())
                }
            }
        } else {
            Log.d(TAG, "No image URL provided, sending notification without image")
            notificationManager.notify(uniqueId, builder.build())
            notificationManager.notify(0, summaryBuilder.build())
        }
    }

    private fun saveNotification(title: String, body: String, messageId: String, imageUrl: String?) {
        val sharedPref = getSharedPreferences("notifications", Context.MODE_PRIVATE)
        val notifications = sharedPref.getStringSet("pending_notifications", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val notificationData = "$messageId|$title|$body|${imageUrl ?: ""}"
        notifications.add(notificationData)
        with(sharedPref.edit()) {
            putStringSet("pending_notifications", notifications)
            apply()
        }
        Log.d(TAG, "Уведомление сохранено: $notificationData")
    }

    private fun applicationState(): String {
        return if (ArdMoneyApp.isAppInForeground) "Foreground" else "Background"
    }
}