package it.unige.hidedroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import io.realm.RealmConfiguration
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.activity.MainActivity
import it.unige.hidedroid.log.LoggerHideDroid
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class ServiceAnonymization : Service() {

    val ACTION_START = "it.unige.hidedroid.action.Start"
    val ACTION_STOP = "it.unige.hidedroid.action.Stop"

    lateinit var isActive: AtomicBoolean
    lateinit var preferences: SharedPreferences
    lateinit var listener: ServiceAnonymizationChangeStatus
    lateinit var realmConfigDGH: RealmConfiguration

    //Threads
    lateinit var dataAnonymizer: DataAnonymizer
    private var threadDataAnonymizer: Thread? = null

    lateinit var requestToAnonymizeLock: ReentrantLock
    lateinit var requestToAnonymizeCondition: Condition


    companion object {
        val TAG = ServiceAnonymization::class.java.name
        private const val NOTIFICATION_ID = 101
    }

    interface ServiceAnonymizationChangeStatus {
        fun onActivate(service: ServiceAnonymization)
        fun onDeactivate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        LoggerHideDroid.d(TAG, "OnBind")
        return null
    }

    override fun onCreate() {
        LoggerHideDroid.d(TAG, "OnCreate Service")
        super.onCreate()

        preferences = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        isActive = AtomicBoolean(false)
        listener = (this.application as HideDroidApplication).serviceAnonymizationChangeStatusListener
        realmConfigDGH = (this.application as HideDroidApplication).realmConfigDGH
        requestToAnonymizeLock = ReentrantLock()
        requestToAnonymizeCondition = requestToAnonymizeLock.newCondition()
        val minNumberOfRequestForDP = getString(R.string.minNumberOfRequestForDP).toInt()
        val numberOfActions = getString(R.string.numberOfActions).toInt()

        //DataAnonymizer
        dataAnonymizer = DataAnonymizer(isActive, (this.application as HideDroidApplication).isDebugEnabled, minNumberOfRequestForDP,
                (this.application as HideDroidApplication).selectedPrivacyLevels, getString(R.string.numberOfPrivacyLevels).toInt(), numberOfActions,
                realmConfigDGH, requestToAnonymizeLock, requestToAnonymizeCondition,
                (this.application as HideDroidApplication).selectedPrivacyLevelsLock, (this.application as HideDroidApplication).blackListFields,
                (this.application as HideDroidApplication).dghBox)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (!isActive.get()) {
            LoggerHideDroid.d(TAG, "Start Service")
            isActive.set(true)
            threadDataAnonymizer = Thread(dataAnonymizer)
            threadDataAnonymizer!!.start()
            LoggerHideDroid.d(TAG, "ServiceAnonymization is active: $isActive")
        }

        listener.onActivate(this)
        return START_STICKY
    }

    override fun onDestroy() {
        LoggerHideDroid.d(TAG, "Destroy Service")

        isActive.set(false)
        if (threadDataAnonymizer != null) {
            threadDataAnonymizer!!.interrupt()
            threadDataAnonymizer = null
        }
        LoggerHideDroid.d(TAG, "ServiceAnonymization is active: $isActive")

        listener.onDeactivate()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(getString(R.string.channel_id_anonymization)) == null) {
                val channel = NotificationChannel(
                        getString(R.string.channel_id_anonymization),
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            action = Intent.ACTION_MAIN
        }
        val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.channel_id_anonymization))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.anonymization_service_running))
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
    }
}
