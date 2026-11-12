package dev.subsonic.poweramp.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.subsonic.poweramp.MainActivity
import dev.subsonic.poweramp.R

object SyncNotifications {
    private const val CHANNEL_ID = "sync_problems"
    private const val AUTH_FAILED_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Shown when the library can't sync because the server rejected the login"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showAuthFailed(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // The app screen still shows the error.

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Library sync stopped")
            .setContentText("The server rejected the login. Tap to sign in again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(AUTH_FAILED_ID, notification)
    }

    fun cancelAuthFailed(context: Context) {
        NotificationManagerCompat.from(context).cancel(AUTH_FAILED_ID)
    }
}
