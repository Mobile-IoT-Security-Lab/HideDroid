package com.dave.realmdatahelper.hidedroid

import android.util.Log
import io.realm.Realm
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField
import io.realm.kotlin.where

open class PackageNamePrivacyLevel: RealmObject {
    @PrimaryKey
    @RealmField(name = "package_name")
    var packageName:String? = null
    @RealmField(name = "is_installed")
    var isInstalled: Boolean = false
    @RealmField(name = "privacy_level")
    var privacyLevel:Int = -1

    constructor(): super()
    constructor(packageName:String="", isInstalled:Boolean = false, privacyLevel:Int=-1) {
        this.packageName = packageName
        this.isInstalled = isInstalled
        this.privacyLevel = privacyLevel
    }

    override fun toString(): String {
        return "PackageNamePrivacyLevel = (packageName=${this.packageName}, " +
                "installed=${this.isInstalled}, " +
                "privacyLevel=${this.privacyLevel})"
    }

    fun storePrivacySettings(){
        Log.d(PackageNamePrivacyLevel::class.java.name, "storePrivacySettings")
        var realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                realm.insertOrUpdate(this)
            }
        }
    }

    fun deleteThisFromRealm(){
        Log.d(PackageNamePrivacyLevel::class.java.name, "deleteThisFromRealm")
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                val result = realm.where<PackageNamePrivacyLevel>().equalTo("packageName", this.packageName).findAll()
                result.deleteAllFromRealm()
            }
        }
    }



}