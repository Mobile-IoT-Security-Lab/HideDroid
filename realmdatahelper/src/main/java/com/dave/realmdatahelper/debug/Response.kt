package com.dave.realmdatahelper.debug

import android.util.Log
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.RealmField

open class Response: RealmObject {

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "headers")
    var headers: String = ""

    @RealmField(name = "body_anonymized")
    var body_anonymized: String = ""

    @RealmField(name = "code")
    var code: String = ""

    constructor(): super()
    constructor(host: String, headers: String, body_anonymized: String, code: String) : super() {
        this.host = host
        this.headers = headers
        this.body_anonymized = body_anonymized
        this.code = code
    }

    fun insertOrUpdateResponse(realConfigLog: RealmConfiguration) {
        Log.d(Request::class.java.name, "insertOrUpdateResponse")
        val realm = Realm.getInstance(realConfigLog)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                realm.insertOrUpdate(this)
            }
        }
    }

    override fun toString(): String {
        return "$host;; $headers;; $body_anonymized;; $code"
    }
}