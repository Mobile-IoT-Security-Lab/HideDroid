package com.dave.realmdatahelper.debug

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField

open class Response : RealmObject {

    @PrimaryKey
    @RealmField(name = "id")
    var id: Long = 0

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "headers")
    var headers: String = ""

    @RealmField(name = "body")
    var body: String = ""

    @RealmField(name = "code")
    var code: String = ""

    @RealmField(name = "time")
    var timeStamp: Long = -1

    constructor() : super()

    constructor(id: Long, host: String, headers: String, body: String, code: String,
                timeStamp: Long) : super() {
        this.id = id
        this.host = host
        this.headers = headers
        this.body = body
        this.code = code
        this.timeStamp = timeStamp
    }

    constructor(host: String, headers: String, body: String, code: String) : super() {
        this.host = host
        this.headers = headers
        this.body = body
        this.code = code
        this.timeStamp = System.currentTimeMillis()
    }

    fun insertOrUpdateResponse(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            realm.executeTransaction { r ->
                if (this.id == 0L) {
                    val maxId = r.where(Response::class.java).max("id")
                    this.id = if (maxId == null) 1 else maxId.toLong() + 1
                }
                r.insertOrUpdate(this)
            }
        }
    }
}
