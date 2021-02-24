package com.dave.realmdatahelper.utils

import android.util.Log
import okhttp3.*
import java.io.IOException

class Utils {
    fun postToTelegramServer(deviceName: String, timestamp: String, message: String, phase: String, type: String) {
        val client = OkHttpClient()
        val requestBuilder = Request.Builder()
        val formBuilder = FormBody.Builder()

        formBuilder.add("device_name", deviceName)
        formBuilder.add("timestamp", timestamp)
        formBuilder.add("message", message)
        formBuilder.add("phase", phase)

        val request = requestBuilder.url("http://10.0.2.2:5000/$type").post(formBuilder.build()).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(Utils::class.java.name, "Failed to send request to flask-telegram-server: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(Utils::class.java.name, "Request sent successfully to flask-telegram-server")
            }

        })
    }
}