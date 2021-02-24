package com.dave.anonymization_data.anonymizationthreads

import android.content.SharedPreferences
import android.util.Log
import com.dave.anonymization_data.data.MultidimensionalData
import com.dave.anonymization_data.parsering.BodyParser
import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock


/**
 * Thread che prende le richieste dal database, dalla tabella AnalyticsRequest, li parsa mediante la classe
 * BodyParser, e li invia al DataAnonymizer dopo averne bufferizzato un certa quantita'. Se la coda del
 * DataAnonymizer non è vuota, si mette in attesa finche' questa non si svuota. Nel frattempo il DbReader se
 * il suo buffer non è pieno, può leggere e analizzare altre richieste fino al suo riempimento.
 */
class DbReader : Runnable {

    private var TAG = DbReader::class.java.name
    private var isActive: AtomicBoolean
    private var isDebugEnabled: AtomicBoolean
    private var realmConfigLog: RealmConfiguration
    private var preferences: SharedPreferences
    var requestBuffer: MutableList<AnalyticsRequest>
    //var youCanRead: AtomicBoolean
    var firstAnalyticsRequestIdToRead: AtomicLong
    var requestBufferMaxSize: Int = -1
    private var dataAnonymizer: DataAnonymizer
    private var requestToAnonymizeLock: ReentrantLock
    private var requestToAnonymizeCondition: Condition
    var interceptorLock: ReentrantLock
    var interceptorCondition: Condition


    constructor(isActive: AtomicBoolean, isDebugEnabled: AtomicBoolean, realmConfigLog: RealmConfiguration, preferences: SharedPreferences,
                firstAnalyticsRequestIdToRead: AtomicLong, requestBufferMaxSize: Int, dataAnonymizer: DataAnonymizer,
                requestToAnonymizeLock: ReentrantLock, requestToAnonymizeCondition: Condition) {
        this.isActive = isActive
        this.isDebugEnabled = isDebugEnabled
        this.realmConfigLog = realmConfigLog
        this.preferences = preferences
        this.requestBuffer = mutableListOf()
        //this.youCanRead = AtomicBoolean(false)
        this.firstAnalyticsRequestIdToRead = firstAnalyticsRequestIdToRead
        this.requestBufferMaxSize = requestBufferMaxSize
        this.dataAnonymizer = dataAnonymizer
        this.requestToAnonymizeLock = requestToAnonymizeLock
        this.requestToAnonymizeCondition = requestToAnonymizeCondition
        this.interceptorLock = ReentrantLock()
        this.interceptorCondition = interceptorLock.newCondition()
    }

    override fun run() {
        labelInterrupt@
        while (isActive.get()) {
            Log.d(TAG, "DbReader Thread is active")

            // Lettura delle richieste
            //readRequestsFromDb()
            //var wasBlocked = false
            var requestToAnonymize: AnalyticsRequest? = null
            this.interceptorLock.lock()
            try {
                while (requestBuffer.isEmpty() /*requestBuffer.size != requestBufferMaxSize && !youCanRead.get()*/) {
                    //wasBlocked = true
                    try {
                        Log.d(TAG, "DbReader Thread waits new requests to read")
                        interceptorCondition.await()
                        Log.d(TAG, "DbReader Thread wakes up from waiting new requests to read")
                    } catch (ie: InterruptedException) {
                        Log.d(TAG, "DbReader Thread interrupted from waiting new requests to read")
                        if (!isActive.get()) {
                            break@labelInterrupt
                        } else {
                            if (isDebugEnabled.get()) {
                                Error("", "", "", "", "DbReader thread was interrupted while isActive = ${isActive.get()} due to reason: $ie").insertOrUpdateError(realmConfigLog)
                            }
                            Log.d(TAG, "DbReader thread was interrupted while isActive = ${isActive.get()} due to reason: $ie")
                            return
                        }
                    }
                }
                /*if (wasBlocked) {
                    youCanRead.set(false)
                    readRequestsFromDb()
                }*/
                requestToAnonymize = requestBuffer.removeAt(0)
            } finally {
                this.interceptorLock.unlock()
            }

            // Parsering delle richieste
            val parsedRequest: MultidimensionalData
            //this.interceptorLock.lock()
            //try {
            //Log.d(TAG, "DbReader starts parsing ${requestBuffer.size} requests")
            //parsedRequest = BodyParser().parse(requestToAnonymize!!, isDebugEnabled, realmConfigLog)
            //Log.d(TAG, "DbReader finish parsing ${parsedRequestQueue.size} requests")
            //} finally {
            //this.interceptorLock.unlock()
            //}

            // Invio delle richieste al servizio di DataAnonymizer
            requestToAnonymizeLock.lock()
            try {
                /*while (dataAnonymizer.requestToAnonymizeQueue.isNotEmpty()) {
                    try {
                        Log.d(TAG, "DbReader Thread wait that the DataAnonymizer queue is empty")
                        requestToAnonymizeCondition.await()
                        Log.d(TAG, "DbReader Thread wakes up from waiting that the DataAnonymizer queue is empty")
                    } catch (ie: InterruptedException) {
                        Log.d(TAG, "DbReader Thread interrupted from waiting that the DataAnonymizer queue is empty")
                        if (!isActive.get()) {
                            break@labelInterrupt
                        } else {
                            if (isDebugEnabled.get()) {
                                Error("", "", "", "", "DbReader thread was interrupted while isActive = ${isActive.get()} due to reason: $ie").insertOrUpdateError(realmConfigLog)
                            }
                            Log.d(TAG, "DbReader thread was interrupted while isActive = ${isActive.get()} due to reason: $ie")
                        }
                    }
                }*/
                Log.d(TAG, "DbReader add all selected packets to DataAnonymizer's queue")
                //dataAnonymizer.requestToAnonymizeQueue.add(parsedRequest)
                requestToAnonymizeCondition.signal()
            } finally {
                requestToAnonymizeLock.unlock()
            }

            // Libero il buffer delle richieste lette dal DB
            /*this.interceptorLock.lock()
            try {
                Log.d(TAG, "DbReader Thread clears its request buffer queue")
                requestBuffer.clear()
            } finally {
                this.interceptorLock.unlock()
            }*/
        }

        // Aggiorno il prossimo ID da leggere dal Database prima di terminare
        /*with(preferences.edit()) {
            putLong("firstAnalyticsRequestIdToRead", firstAnalyticsRequestIdToRead.get())
            commit()
        }*/
        Log.d(TAG, "DbReader Thread is deactivated")
    }

    private fun readRequestsFromDb() {
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction {
                val numberOfEntry = realm.where<AnalyticsRequest>().count()
                var lastAnalyticsRequestIdToRead = firstAnalyticsRequestIdToRead.get() + requestBufferMaxSize
                if (lastAnalyticsRequestIdToRead >= numberOfEntry) {
                    lastAnalyticsRequestIdToRead = numberOfEntry
                }
                if ((requestBuffer.size + (lastAnalyticsRequestIdToRead - firstAnalyticsRequestIdToRead.get())) > requestBufferMaxSize) {
                    lastAnalyticsRequestIdToRead = firstAnalyticsRequestIdToRead.get() + requestBufferMaxSize - requestBuffer.size
                }
                if (lastAnalyticsRequestIdToRead < 1) {
                    return@executeTransaction
                }
                val realmResult = if (true /*lastAnalyticsRequestIdToRead == firstAnalyticsRequestIdToRead.get()*/) {
                    realm.where<AnalyticsRequest>()
                            .equalTo("id", firstAnalyticsRequestIdToRead.get()).findAll()
                } else {
                    realm.where<AnalyticsRequest>()
                            .between("id", firstAnalyticsRequestIdToRead.get(), lastAnalyticsRequestIdToRead - 1).findAll()
                }

                this.interceptorLock.lock()
                try {
                    requestBuffer.addAll(realm.copyFromRealm(realmResult))
                    firstAnalyticsRequestIdToRead.set(lastAnalyticsRequestIdToRead)
                } finally {
                    this.interceptorLock.unlock()
                }
            }
        }
    }

}