package com.dave.realmdatahelper.debug

import android.util.Log
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.RealmField

open class Request: RealmObject {

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "body")
    var body: String = ""

    @RealmField(name = "is_tracked")
    var is_tracked: Boolean = false

    @RealmField(name = "is_private")
    var is_private: Boolean = false

    constructor(): super()
    constructor(host: String, body: String, is_tracked: Boolean, is_private: Boolean) : super() {
        this.host = host
        this.body = body
        this.is_tracked = is_tracked
        this.is_private = is_private
    }

    fun insertOrUpdateRequest(realConfigLog: RealmConfiguration) {
        Log.d(Request::class.java.name, "insertOrUpdateRequest")
        val realm = Realm.getInstance(realConfigLog)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                realm.insertOrUpdate(this)
            }
        }
    }

    override fun toString(): String {
        return "$host;; $body;; $is_tracked;; $is_private"
    }
}