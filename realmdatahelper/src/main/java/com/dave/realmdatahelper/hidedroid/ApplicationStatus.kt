package com.dave.realmdatahelper.hidedroid

import android.util.Log
import io.realm.Realm
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.annotations.RealmField
import io.realm.kotlin.where


open class ApplicationStatus : RealmObject {
    @PrimaryKey
    @RealmField(name = "package_name")
    var packageName: String? = null

    @RealmField(name = "installed")
    var installed: Boolean = false

    @RealmField(name = "is_in_removing")
    var isInRemoving: Boolean = false

    @RealmField(name = "is_repackaged")
    var isRepackaged: Boolean = false

    @RealmField(name = "is_in_installing")
    var isInInstalling: Boolean = false

    @RealmField(name = "is_in_repackaging")
    var isInRepackaging: Boolean = false

    constructor() : super()
    constructor(packageName: String = "", installed: Boolean = false, isRepackaged: Boolean = false,
                isInRemoving: Boolean = false, isInInstalling: Boolean = false,
                isInRepackaging: Boolean = false) {
        this.packageName = packageName
        this.installed = installed
        this.isInRemoving = isInRemoving
        this.isRepackaged = isRepackaged
        this.isInInstalling = isInInstalling
        this.isInRepackaging = isInRepackaging


    }

    override fun toString(): String {
        return "APPRepackagedItem = (packageName=${this.packageName}, " +
                "installed=${this.installed}, " +
                "isInRemoving=${this.isInRemoving}, " +
                "isRepacked=${this.isRepackaged}, " +
                "isInInstalling=${this.isInInstalling}, " +
                "isInRepackaging=${this.isInRepackaging})"
    }

    fun update(applicationStatus: ApplicationStatus) {
        this.installed = applicationStatus.installed
        this.isInRemoving = applicationStatus.isInRemoving
        this.isRepackaged = applicationStatus.isRepackaged
        this.isInInstalling = applicationStatus.isInInstalling
        this.isInRepackaging = applicationStatus.isInRepackaging
    }

    fun storeStateApp() {
        Log.d(ApplicationStatus::class.java.name, "storeStateApp")

        var realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                realm.insertOrUpdate(this)
            }
        }
    }

    fun updateApplicationStatusOnRealmDB() {
        Log.d(ApplicationStatus::class.java.name, "updateApplicationStatusOnRealmDB")

        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                realm.where<ApplicationStatus>().equalTo("package_name", packageName).findFirst()?.update(this)
            }
        }
    }

    fun deleteThisFromRealm() {
        Log.d(ApplicationStatus::class.java.name, "deleteThisFromRealm")
        var realm = Realm.getDefaultInstance()
        realm.use { realm ->
            realm.executeTransaction { realm ->
                // This will update an existing object with the same primary key
                val result = realm.where<ApplicationStatus>().equalTo("packageName", this.packageName).findAll()
                result.deleteAllFromRealm()
            }
        }
    }
}