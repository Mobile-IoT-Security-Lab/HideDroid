package it.unige.hidedroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import it.unige.hidedroid.log.LoggerHideDroid
import java.lang.Exception

class CheckBatteryReceiver: BroadcastReceiver() {
    // TODO start service anonymization
    companion object {
        val TAG = CheckBatteryReceiver::class.java.name
    }


    override fun onReceive(context: Context?, intent: Intent?) {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context?.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        // How are we charging?
        val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        LoggerHideDroid.d(TAG, "$status, $isCharging, $usbCharge, $acCharge")
        if (isCharging){
            // TODO start service anonymization
            LoggerHideDroid.d(TAG, "Is Charging, start service anonymization")
        }
    }

}