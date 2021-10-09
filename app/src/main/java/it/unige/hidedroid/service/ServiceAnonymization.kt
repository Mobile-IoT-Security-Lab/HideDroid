package it.unige.hidedroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.google.gson.Gson
import io.realm.RealmConfiguration
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.log.LoggerHideDroid
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

class ServiceAnonymization: Service() {

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


    companion object{
        val TAG = ServiceAnonymization::class.java.name
    }

    interface ServiceAnonymizationChangeStatus {
        fun onActivate(service: ServiceAnonymization)
        fun onDeactivate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        //TODO("Not yet implemented")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isActive.get()) {
            LoggerHideDroid.d(TAG, "Start Service")
            isActive.set(true)
            threadDataAnonymizer = Thread(dataAnonymizer)
            threadDataAnonymizer!!.start()
            LoggerHideDroid.d(TAG, "ServiceAnonymization is active: $isActive")
        }

        listener?.onActivate(this)
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
}