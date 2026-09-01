package com.dave.realmdatahelper.hidedroid

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField

/**
 * One row per domain request seen by the VPN interceptor.
 */
open class DomainLogEntry : RealmObject {

    @PrimaryKey
    @RealmField(name = "id")
    var id: Long = 0

    @RealmField(name = "timestamp")
    var timestamp: Long = 0

    @RealmField(name = "package_name")
    var packageName: String? = null

    @RealmField(name = "host")
    var host: String? = null

    @RealmField(name = "method")
    var method: String? = null

    @RealmField(name = "path")
    var path: String? = null

    @RealmField(name = "status")
    var status: String? = null

    constructor() : super()

    constructor(id: Long, timestamp: Long, packageName: String?, host: String?, method: String?, path: String?, status: String?) : super() {
        this.id = id
        this.timestamp = timestamp
        this.packageName = packageName
        this.host = host
        this.method = method
        this.path = path
        this.status = status
    }
}
