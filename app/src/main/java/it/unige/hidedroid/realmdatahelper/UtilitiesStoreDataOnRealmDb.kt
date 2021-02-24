package it.unige.hidedroid.realmdatahelper

import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import io.realm.Realm
import io.realm.kotlin.where
import it.unige.hidedroid.log.LoggerHideDroid

object UtilitiesStoreDataOnRealmDb {

    private val TAG = UtilitiesStoreDataOnRealmDb::class.java.name


    fun getEventBufferFromPackageName(packageName:String, host:String): com.dave.realmdatahelper.hidedroid.EventBuffer {
        LoggerHideDroid.d(TAG, "getEventBufferFromPackageName")

        var eventBufferToReturn: com.dave.realmdatahelper.hidedroid.EventBuffer? = null
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val eventBufferList = realm.where<com.dave.realmdatahelper.hidedroid.EventBuffer>()
                    .equalTo("packageName", packageName)
                    .and()
                    .equalTo("host", host)

            if (eventBufferList?.findFirst() != null) {
                val eventBuffer = eventBufferList.findFirst()
                eventBufferToReturn = realm.copyFromRealm(eventBuffer!!)
            } else {
                eventBufferToReturn = com.dave.realmdatahelper.hidedroid.EventBuffer()
            }
        }
        return eventBufferToReturn!!
    }

    fun getAllApplicationStatus() : MutableList<com.dave.realmdatahelper.hidedroid.ApplicationStatus> {
        LoggerHideDroid.d(TAG, "getAllApplicationStatus")

        var list: MutableList<ApplicationStatus>? = null
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val realmResultAllApplicationStatus = realm.where<com.dave.realmdatahelper.hidedroid.ApplicationStatus>().findAll()
            list = realm.copyFromRealm(realmResultAllApplicationStatus) as MutableList<com.dave.realmdatahelper.hidedroid.ApplicationStatus>
        }
        return list!!

    }

    fun getAllPrivacySettingApp(): MutableList<com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel>{
        LoggerHideDroid.d(TAG, "getAllPrivacySettingApp")

        var list: MutableList<PackageNamePrivacyLevel>? = null
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val realmResultPackageNamePrivacyLevel = realm.where<com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel>().findAll()
            list = realm.copyFromRealm(realmResultPackageNamePrivacyLevel) as MutableList<com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel>
        }
        return list!!
    }

    fun getListPackageNameTracked(): MutableList<String> {
        LoggerHideDroid.d(TAG, "getListPackageNameTracked")

        var listPackageName = mutableListOf<String>()
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val packageNamePrivacyLevel = realm.where<com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel>().findAll()
            for (app in packageNamePrivacyLevel) {
                listPackageName.add(app.packageName!!)
            }
        }
        return listPackageName
    }

    fun getStatusAppFromPackageName(packageName: String): com.dave.realmdatahelper.hidedroid.ApplicationStatus? {
        LoggerHideDroid.d(TAG, "getStatusAppFromPackageName")

        val realm = Realm.getDefaultInstance()
        var applicationStatus: com.dave.realmdatahelper.hidedroid.ApplicationStatus? = null
        realm.use { realm ->
            val appStatusFromReal = realm.where<com.dave.realmdatahelper.hidedroid.ApplicationStatus>().equalTo("packageName", packageName).findFirst()
            if (appStatusFromReal != null) {
                applicationStatus = realm.copyFromRealm(appStatusFromReal)
            }
        }
        return applicationStatus
    }

    fun getPrivacySettingAppFromPackageName(packageName: String): com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel?{
        LoggerHideDroid.d(TAG, "getPrivacySettingAppFromPackageName")

        var packageNamePrivacyLevel: com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel? = null
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val packageNamePrivacyLevelFromRealm = realm.where<com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel>().equalTo("packageName", packageName).findFirst()
            if (packageNamePrivacyLevelFromRealm != null)
                packageNamePrivacyLevel = realm.copyFromRealm(packageNamePrivacyLevelFromRealm)
        }
        return packageNamePrivacyLevel
    }





}