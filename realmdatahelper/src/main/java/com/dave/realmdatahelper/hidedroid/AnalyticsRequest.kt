package com.dave.realmdatahelper.hidedroid

import android.util.Log
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField
import io.realm.kotlin.where

open class AnalyticsRequest : RealmObject {

    @PrimaryKey
    @RealmField(name = "id")
    var id: Long = 0

    @RealmField(name = "package_name")
    var packageName: String = ""

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "time")
    var timeStamp: Long = -1

    @RealmField(name = "byte_request")
    var byteRequest: ByteArray? = null

    @RealmField(name = "method")
    var method: String = ""

    @RealmField(name = "path")
    var path: String = ""

    @RealmField(name = "http_protocol")
    var httpProtocol: String = ""

    @RealmField(name = "header_json")
    var headersJson: String = ""

    @RealmField(name = "body_offset")
    var bodyOffset: Int = -1

    @RealmField(name = "body_string")
    var bodyString: String = ""

    @RealmField(name = "body_without_special_char")
    var bodyWithoutSpecialChar: String = ""

    constructor() : super()
    constructor(id: Long, packageName: String, host: String, timeStamp: Long, byteRequest: ByteArray?, method: String, path: String, httpProtocol: String, headersJson: String, bodyOffset: Int, bodyString: String, bodyWithoutSpecialChar: String) : super() {
        this.id = id
        this.packageName = packageName
        this.host = host
        this.timeStamp = timeStamp
        this.byteRequest = byteRequest
        this.method = method
        this.path = path
        this.httpProtocol = httpProtocol
        this.headersJson = headersJson
        this.bodyOffset = bodyOffset
        this.bodyString = bodyString
        this.bodyWithoutSpecialChar = bodyWithoutSpecialChar
    }

    fun storeAnalyticsRequest() {
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            Log.d(AnalyticsRequest::class.java.name, "storeAnalyticsRequest")
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                realm.insertOrUpdate(this)
            }
        }
    }

    fun insertOrUpdateEventBuffer() {
        Log.d(AnalyticsRequest::class.java.name, "updateEventBuffer")
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                var eventBuffer = realm.where<EventBuffer>()
                        .equalTo("packageName", this.packageName)
                        .and()
                        .equalTo("host", this.host)
                        .findFirst()
                if (eventBuffer != null) {
                    eventBuffer.event.add(this)
                } else {
                    eventBuffer = EventBuffer(this.packageName, this.host, RealmList())
                    eventBuffer.event.add(this)
                    realm.insertOrUpdate(eventBuffer)
                }

            }
        }
    }

    override fun toString(): String {
        return "$id;; ${packageName};; $host;; $timeStamp;; $headersJson;; $bodyOffset;; $bodyWithoutSpecialChar"

    }

}

