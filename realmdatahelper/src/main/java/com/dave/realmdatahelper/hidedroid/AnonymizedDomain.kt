package com.dave.realmdatahelper.hidedroid

import android.util.Log
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField
import io.realm.kotlin.where

open class AnonymizedDomain : RealmObject {
    @PrimaryKey
    @RealmField(name = "host")
    var host: String? = null
    @RealmField(name = "enabled")
    var enabled: Boolean = false
    @RealmField(name = "blocked")
    var blocked: Boolean = false
    @RealmField(name = "added_by")
    var addedBy: String? = null
    @RealmField(name = "app_packages")
    var appPackages: RealmList<String> = RealmList()

    constructor() : super()
    constructor(host: String = "", enabled: Boolean = false, blocked: Boolean = false, addedBy: String = "", appPackages: RealmList<String>? = null) {
        this.host = host
        this.enabled = enabled
        this.blocked = blocked
        this.addedBy = addedBy
        if (appPackages != null) {
            this.appPackages = appPackages
        }
    }

    override fun toString(): String {
        return "AnonymizedDomain = (host=${this.host}, enabled=${this.enabled}, blocked=${this.blocked}, addedBy=${this.addedBy}, appPackages=${this.appPackages})"
    }

    fun storeDomain() {
        Log.d(AnonymizedDomain::class.java.name, "storeDomain")
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                realm.insertOrUpdate(this)
            }
        }
    }

    fun deleteThisFromRealm() {
        Log.d(AnonymizedDomain::class.java.name, "deleteThisFromRealm")
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                val result = realm.where<AnonymizedDomain>().equalTo("host", this.host).findAll()
                result.deleteAllFromRealm()
            }
        }
    }
}
