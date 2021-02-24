package it.unige.hidedroid.log

import android.util.Log
import it.unige.hidedroid.BuildConfig

object LoggerHideDroid {

    public fun v(TAG: String, message:String){
        if (BuildConfig.DEBUG){
            Log.v(TAG, message)
        }
    }

    public fun d(TAG: String, message:String){
        if (BuildConfig.DEBUG){
            Log.d(TAG, message)
        }
    }


    public fun i(TAG: String, message:String){
        if (BuildConfig.DEBUG){
            Log.i(TAG, message)
        }
    }

    public fun w(TAG: String, message:String){
        if (BuildConfig.DEBUG){
            Log.w(TAG, message)
        }
    }

    public fun e(TAG: String, message:String){
        if (BuildConfig.DEBUG){
            Log.e(TAG, message)
        }
    }

}