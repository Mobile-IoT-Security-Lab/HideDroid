package it.unige.hidedroid

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import com.dave.realmdatahelper.realmmodules.DGHModule
import com.dave.realmdatahelper.realmmodules.DefaultModules
import com.dave.realmdatahelper.realmmodules.PrivateTrackerModule
import com.github.megatronking.netbare.BuildConfig
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareUtils
import com.github.megatronking.netbare.ssl.JKS
import com.google.gson.Gson
import com.orm.SugarContext
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import it.unige.hidedroid.activity.MainActivity
import it.unige.hidedroid.receiver.AppReceiver
import it.unige.hidedroid.receiver.CheckBatteryReceiver
import it.unige.hidedroid.service.ServiceAnonymization
import me.weishu.reflection.Reflection
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock


class HideDroidApplication : Application() {
    companion object {
        const val JSK_ALIAS = "HideDroidSample"
        const val DEBUG_ENABLED_KEY = "debugEnabled"

        private lateinit var sInstance: HideDroidApplication

        fun getInstance(): HideDroidApplication {
            return sInstance
        }
    }

    private lateinit var mJKS: JKS
    lateinit var realmConfigPrivateTracker: RealmConfiguration
    lateinit var realmConfigDGH: RealmConfiguration

    val isDebugEnabled: AtomicBoolean = AtomicBoolean(false)
    lateinit var preferences: SharedPreferences
    lateinit var sharedPreferencesUpdateListener: MainActivity.SharedPreferencesUpdateListener
    var serviceAnonymizationReady: ServiceAnonymizationReady? = null
    lateinit var serviceAnonymizationChangeStatusListener: ServiceAnonymization.ServiceAnonymizationChangeStatus
    lateinit var blackListFields: Set<String>
    lateinit var dghBox: DataAnonymizer.DghBox
    var dghKeysSet: MutableSet<String> = mutableSetOf()
    var dghFacebookKeySet: MutableSet<String> = mutableSetOf()
    var serviceAnonymization: ServiceAnonymization? = null
    var selectedPrivacyLevels: MutableMap<String, AtomicInteger> = mutableMapOf()
    var selectedPrivacyLevelsLock: ReentrantLock = ReentrantLock()


    interface ServiceAnonymizationReady {
        fun onReadyService()
    }

    override fun onCreate() {
        super.onCreate()

        preferences = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        when {
            preferences.getInt(DEBUG_ENABLED_KEY, -1) == 1 -> {
                isDebugEnabled.set(true)
            }
            preferences.getInt(DEBUG_ENABLED_KEY, -1) == 0 -> {
                isDebugEnabled.set(false)
            }
            preferences.getInt(DEBUG_ENABLED_KEY, -1) == -1 -> {
                with(preferences.edit()) {
                    putInt(DEBUG_ENABLED_KEY, 1) // TODO: set default to 0
                    commit()
                }
                isDebugEnabled.set(true) // TODO: set default to false
            }
        }

        sharedPreferencesUpdateListener = object : MainActivity.SharedPreferencesUpdateListener {
            override fun onUpdatePreferences(isDebugEnabled: Boolean) {
                with(preferences.edit()) {
                    if (isDebugEnabled) {
                        putInt(DEBUG_ENABLED_KEY, 1)
                    } else {
                        putInt(DEBUG_ENABLED_KEY, 0)
                    }
                    commit()
                }
            }
        }

        serviceAnonymizationChangeStatusListener = object : ServiceAnonymization.ServiceAnonymizationChangeStatus {
            override fun onActivate(service: ServiceAnonymization) {
                serviceAnonymization = service
                serviceAnonymizationReady?.onReadyService()
            }

            override fun onDeactivate() {
                serviceAnonymization = null
            }
        }

        sInstance = this
        // create certificate
        mJKS = JKS(this, JSK_ALIAS, JSK_ALIAS.toCharArray(), JSK_ALIAS, JSK_ALIAS,
                JSK_ALIAS, JSK_ALIAS, JSK_ALIAS)

        NetBare.get().attachApplication(this, BuildConfig.DEBUG)

        // SUGAR
        SugarContext.init(this)

        // REALM DB
        Realm.init(this)
        val realmConfig = RealmConfiguration.Builder()
                .name("realmeventbuffer.realm")
                .schemaVersion(0)
                .modules(DefaultModules())
                .build()
        Realm.setDefaultConfiguration(realmConfig)

        // REALM DB FOR PRIVATE TRACKERS
        realmConfigPrivateTracker = RealmConfiguration.Builder()
                .name("realmprivatetrackers.realm")
                .schemaVersion(0)
                .modules(PrivateTrackerModule())
                .build()

        // REALM DB FOR DGH
        realmConfigDGH = RealmConfiguration.Builder()
                .name("realmDGH.realm")
                .schemaVersion(0)
                .modules(DGHModule())
                .build()

        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val privacyLevels = realm.where<PackageNamePrivacyLevel>().findAll()
            for (privacyLevel in privacyLevels) {
                selectedPrivacyLevels[privacyLevel.packageName!!] = AtomicInteger(privacyLevel.privacyLevel)
            }
        }

        var jsonString = this.assets.open("black_list_fields.json").bufferedReader().use { it.readText() }
        blackListFields = Gson().fromJson(jsonString, Array<String>::class.java).toHashSet()

        jsonString = this.assets.open("dgh.json").bufferedReader().use { it.readText() }
        val dgh = JSONObject(jsonString)
        for (key in dgh.keys()) {
            dghKeysSet.add(key)
        }

        jsonString = this.assets.open("dgh_facebook.json").bufferedReader().use { it.readText() }
        val dghFacebook = JSONObject(jsonString)
        for (key in dghFacebook.keys()) {
            dghFacebookKeySet.add(key)
        }
        dghBox = DataAnonymizer.DghBox(dgh, dghKeysSet, dghFacebook, dghFacebookKeySet)

        this.myRegisterReceiver()
    }

    fun myRegisterReceiver() {
        val receiverBattery = CheckBatteryReceiver()
        this.registerReceiver(receiverBattery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        this.registerReceiver(receiverBattery, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        this.registerReceiver(receiverBattery, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))

        val receiverApp = AppReceiver()
        this.registerReceiver(receiverApp,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        SugarContext.terminate()
        Realm.getDefaultInstance().close();
    }

    fun getJSK(): JKS {
        return mJKS
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // On android Q, we can't access Java8EngineWrapper with reflect.
        if (NetBareUtils.isAndroidQ()) {
            Reflection.unseal(base)
        }
    }
}