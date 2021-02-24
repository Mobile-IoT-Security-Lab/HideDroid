package it.unige.hidedroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.github.megatronking.netbare.NetBareService
import it.unige.hidedroid.R
import it.unige.hidedroid.activity.MainActivity

class ServiceVPN: NetBareService(){

    override fun onCreate() {

        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(getString(R.string.channel_id_notification)) == null) {
                notificationManager.createNotificationChannel(NotificationChannel(getString(R.string.channel_id_notification),
                        getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun createNotification(): Notification {
        // TODO change image and description notification
        //Toasty.info(applicationContext, "CreateNotification").show()
        val intent = Intent(this, MainActivity::class.java)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.action = Intent.ACTION_MAIN
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, getString(R.string.channel_id_notification))
                .setSmallIcon(R.mipmap.ic_launcher_circle)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.start_incognito_mode))
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
    }

    override fun notificationId(): Int {

        //Toasty.info(applicationContext, "NotificationId").show()
        return 100

    }

}