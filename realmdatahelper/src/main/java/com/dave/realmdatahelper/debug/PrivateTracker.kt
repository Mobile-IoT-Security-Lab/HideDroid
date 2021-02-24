package com.dave.realmdatahelper.debug

import android.util.Log
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField

open class PrivateTracker: RealmObject {

    @PrimaryKey
    @RealmField(name = "host")
    var host: String = ""

    constructor(): super()

    constructor(host: String) : super() {
        this.host = host
    }

    fun insertOrUpdate(realmConfiguration: RealmConfiguration) {
        Log.d(PrivateTracker::class.java.name, "insertOrUpdatePrivateTracker")
        val realm = Realm.getInstance(realmConfiguration)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                realm.insertOrUpdate(this)
            }
        }
    }

    override fun toString(): String {
        return "$host"
    }
}