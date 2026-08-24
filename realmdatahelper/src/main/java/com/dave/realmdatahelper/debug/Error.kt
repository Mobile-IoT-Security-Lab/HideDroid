package com.dave.realmdatahelper.debug

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField

open class Error : RealmObject {

    @PrimaryKey
    @RealmField(name = "id")
    var id: Long = 0

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

    @RealmField(name = "time")
    var timeStamp: Long = -1

    constructor() : super()

    constructor(id: Long, app: String, host: String, headers: String, body: String,
                error: String, timeStamp: Long) : super() {
        this.id = id
        this.app = app
        this.host = host
        this.headers = headers
        this.body = body
        this.error = error
        this.timeStamp = timeStamp
    }

    fun insertOrUpdateError(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            realm.executeTransaction { r ->
                r.insertOrUpdate(this)
            }
        }
    }
}
