package com.dave.realmdatahelper.dgh

import android.util.Log
import com.dave.realmdatahelper.debug.PrivateTracker
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField
import io.realm.kotlin.where

open class DGH: RealmObject {

    @PrimaryKey
    @RealmField (name = "key")
    var key: String = ""

    @RealmField (name = "values_list")
    var valuesList: RealmList<String>? = null

    constructor(): super()

    constructor(key: String, valuesList: RealmList<String>) : super() {
        this.key = key
        this.valuesList = valuesList
    }

    fun insertOrUpdate(realmConfiguration: RealmConfiguration, value: String) {
        Log.d(PrivateTracker::class.java.name, "insertOrUpdateDGH")
        val realm = Realm.getInstance(realmConfiguration)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                val realmResult = realm.where<DGH>().equalTo("key", key).findFirst()
                if (realmResult != null) {
                    realmResult.valuesList?.add(value)
                } else {
                    this.valuesList?.add(value)
                    realm.insertOrUpdate(this)
                }
            }
        }
    }

    override fun toString(): String {
        return "$key;; $valuesList"
    }
}