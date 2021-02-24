package com.dave.realmdatahelper.hidedroid

import android.util.Log
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.RealmField
import io.realm.kotlin.where


open class EventBuffer : RealmObject {
    @RealmField(name = "package_name")
    var packageName: String = ""

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "events")
    var event: RealmList<AnalyticsRequest> = RealmList()

    constructor(packageName: String, host: String, event: RealmList<AnalyticsRequest>) : super() {
        this.packageName = packageName
        this.host = host
        this.event = event
    }

    constructor() : super()

    fun storeEventBuffer() {
        Log.d(EventBuffer::class.java.name, "storeEventBuffer")
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                realm.copyToRealm(this)
            }
        }
    }

    fun getEventBufferFromPackageName(packageName: String, host: String): EventBuffer {
        Log.d(EventBuffer::class.java.name, "getEventBufferFromPackageName")

        var eventBufferToReturn: EventBuffer? = null
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val eventBuffer = realm.where<EventBuffer>()
                    .equalTo("packageName", packageName)
                    .and()
                    .equalTo("host", host)
                    .findFirst()!!

            eventBufferToReturn = realm.copyFromRealm(eventBuffer)
        }
        return eventBufferToReturn!!
    }

    override fun toString(): String {
        return "${packageName}, ${host}, ${event.size}"
    }


}

