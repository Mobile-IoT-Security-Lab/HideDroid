package com.dave.realmdatahelper.debug

import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField

open class Request : RealmObject {

    @PrimaryKey
    @RealmField(name = "id")
    var id: Long = 0

    @RealmField(name = "package_name")
    var packageName: String = ""

    @RealmField(name = "host")
    var host: String = ""

    @RealmField(name = "headers")
    var headers: String = ""

    @RealmField(name = "body")
    var body: String = ""

    @RealmField(name = "method")
    var method: String = ""

    @RealmField(name = "path")
    var path: String = ""

    @RealmField(name = "time")
    var timeStamp: Long = -1

    @RealmField(name = "is_tracked")
    var isTracked: Boolean = false

    @RealmField(name = "is_private")
    var isPrivate: Boolean = false

    @RealmField(name = "http_protocol")
    var httpProtocol: String = ""

    constructor() : super()

    constructor(id: Long, packageName: String, host: String, headers: String, body: String,
                method: String, path: String, timeStamp: Long, isTracked: Boolean,
                isPrivate: Boolean, httpProtocol: String) : super() {
        this.id = id
        this.packageName = packageName
        this.host = host
        this.headers = headers
        this.body = body
        this.method = method
        this.path = path
        this.timeStamp = timeStamp
        this.isTracked = isTracked
        this.isPrivate = isPrivate
        this.httpProtocol = httpProtocol
    }

    fun insertOrUpdateRequest(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            realm.executeTransaction { r ->
                r.insertOrUpdate(this)
            }
        }
    }
}
