package com.dave.realmdatahelper.debug

import android.util.Log
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.RealmField

open class Error: RealmObject {

    @RealmField(name = "app")
    var app: String = ""

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "headers")
    var headers: String = ""

    @RealmField(name = "body")
    var body: String = ""

    @RealmField(name = "error")
    var error: String = ""

    constructor(): super()
    constructor(app: String, host: String, headers: String, body: String, error: String) : super() {
        this.app = app
        this.host = host
        this.headers = headers
        this.body = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.DEFAULT)
        this.error = error
    }

    fun insertOrUpdateError(realConfigLog: RealmConfiguration){
        Log.d(Error::class.java.name, "insertOrUpdateError")
        val realm = Realm.getInstance(realConfigLog)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                realm.insertOrUpdate(this)
            }
        }
    }

    override fun toString(): String {
        return "$app;; $host;; $headers;; $body;; $error"
    }

}