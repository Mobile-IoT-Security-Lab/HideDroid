package com.dave.anonymization_data.anonymizationthreads

import android.util.Log
import com.dave.anonymization_data.algorithms.AnonymizationHeuristic
import com.dave.anonymization_data.algorithms.DifferentialPrivacy
import com.dave.anonymization_data.data.ContentEncoding
import com.dave.anonymization_data.data.ContentType
import com.dave.anonymization_data.data.MultidimensionalData
import com.dave.anonymization_data.parsering.BodyParser
import com.dave.anonymization_data.parsering.MultipartValueWrapper
import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.utils.Utils
import com.github.megatronking.netbare.http.HttpMethod
import com.github.megatronking.netbare.http.HttpProtocol
import io.realm.RealmConfiguration
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


/**
 * Thread che anonimizza i pacchetti ricevuti dall'HttpInterceptor. Le operazioni compiute sono:
 * - parsering del pacchetto
 * - anonimizzazione del pacchetto
 * - codifica del pacchetto
 * Il pacchetto anonimizzato viene restituito all'HttpInterceptor tramite la classe Box
 */
class DataAnonymizer : Runnable {

    private val TAG = DataAnonymizer::class.java.name
    private val isActive: AtomicBoolean
    private val isDebugEnabled: AtomicBoolean
    private val minNumberOfRequestForDP: Int
    private val selectedPrivacyLevels: MutableMap<String, AtomicInteger>
    private val numberOfPrivacyLevels: Int
    private val numberOfActions: Int
    private val realmConfigLog: RealmConfiguration
    private val realmConfigDGH: RealmConfiguration
    private val blackListFields: Set<String>
    private var dghBox: DghBox
    val requestToAnonymizeQueue: MutableList<Box>
    val requestToAnonymizeLock: ReentrantLock
    val requestToAnonymizeCondition: Condition
    private val selectedPrivacyLevelsLock: ReentrantLock
    private val androidId: String


    constructor(isActive: AtomicBoolean, isDebugEnabled: AtomicBoolean, minNumberOfRequestForDP: Int, selectedPrivacyLevels: MutableMap<String, AtomicInteger>,
                numberOfPrivacyLevels: Int, numberOfActions: Int, realmConfigLog: RealmConfiguration, realmConfigDGH: RealmConfiguration, requestToAnonymizeLock: ReentrantLock, requestToAnonymizeCondition: Condition,
                selectedPrivacyLevelsLock: ReentrantLock, blackListFields: Set<String>, dghBox: DghBox, androidId: String) {
        this.isActive = isActive
        this.isDebugEnabled = isDebugEnabled
        this.minNumberOfRequestForDP = minNumberOfRequestForDP
        this.selectedPrivacyLevels = selectedPrivacyLevels
        this.numberOfPrivacyLevels = numberOfPrivacyLevels
        this.numberOfActions = numberOfActions
        this.realmConfigLog = realmConfigLog
        this.realmConfigDGH = realmConfigDGH
        this.blackListFields = blackListFields
        this.dghBox = dghBox
        this.requestToAnonymizeQueue = mutableListOf()
        this.requestToAnonymizeLock = requestToAnonymizeLock
        this.requestToAnonymizeCondition = requestToAnonymizeCondition
        this.selectedPrivacyLevelsLock = selectedPrivacyLevelsLock
        this.androidId = androidId
    }

    override fun run() {
        labelInterrupt@
        while (isActive.get()) {
            //Log.d(TAG, "DataAnonymizer Thread is active")
            var box: Box
            this.requestToAnonymizeLock.lock()
            try {
                // attendo di ricevere pacchetti dall'HttpInterceptor
                while (requestToAnonymizeQueue.isEmpty()) {
                    try {
                        //Log.d(TAG, "DataAnonymizer Thread waits until its input queue is empty")
                        requestToAnonymizeCondition.await()
                        //Log.d(TAG, "DataAnonymizer Thread wakes up from waiting until its input queue is empty")
                    } catch (ie: InterruptedException) {
                        Log.d(TAG, "DataAnonymizer Thread interrupted from waiting until its input queue is empty")
                        if (!isActive.get()) {
                            break@labelInterrupt
                        } else {
                            if (isDebugEnabled.get()) {
                                Error("", "", "", "", "DataAnonymizer thread was interrupted while isActive = ${isActive.get()} due to reason: $ie").insertOrUpdateError(realmConfigLog)
                                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "DataAnonymizer thread was interrupted while isActive = ${isActive.get()} due to reason: $ie", "interruptingThread", "error")
                            }
                            Log.d(TAG, "DataAnonymizer thread was interrupted while isActive = ${isActive.get()} due to reason: $ie")
                        }
                    }
                }
                box = requestToAnonymizeQueue.removeAt(0)
            } finally {
                this.requestToAnonymizeLock.unlock()
            }

            val requestToAnonymize = box.requestToAnonymize
            val bodyParser = BodyParser()

            //Log.d(TAG, "DataAnonymizer Thread starts parsing input request: ${requestToAnonymize.id}")
            val requestToAnonymizeParsed = bodyParser.parse(requestToAnonymize, isDebugEnabled, realmConfigLog, androidId)
            var selectedPrivacyLevel: AtomicInteger? = null
            selectedPrivacyLevelsLock.lock()
            try {
                if (selectedPrivacyLevels[requestToAnonymizeParsed.packageName] != null) {
                    selectedPrivacyLevel = selectedPrivacyLevels[requestToAnonymizeParsed.packageName]!!
                }
            } finally {
                selectedPrivacyLevelsLock.unlock()
            }

            var packet = "".toByteArray()
            if (selectedPrivacyLevel != null && selectedPrivacyLevel.get() != 0) {
                //Log.d(TAG, "DataAnonymizer Thread starts anonymizing request: ${requestToAnonymizeParsed.id}")
                val dp = DifferentialPrivacy(requestToAnonymizeParsed, blackListFields, dghBox, selectedPrivacyLevel, numberOfPrivacyLevels, numberOfActions, minNumberOfRequestForDP)
                val requestsAnonymized = dp.anonymize(isDebugEnabled, realmConfigLog, realmConfigDGH, androidId)

                //Log.d(TAG, "DataAnonymizer Thread starts encoding anonymized request")
                if (DifferentialPrivacy.EVENT_GENERALIZED in requestsAnonymized) {
                    val eventGeneralized = requestsAnonymized[DifferentialPrivacy.EVENT_GENERALIZED]
                    packet = bodyParser.encodeBody(eventGeneralized
                            ?: error(""), box, isDebugEnabled, realmConfigLog, androidId)

                    var headersString = ""
                    val headersJsonObject = JSONObject(eventGeneralized.headers!!)
                    for (key in headersJsonObject.keys()) {
                        headersString += ("$key: ${headersJsonObject.getJSONArray(key).getString(0)}\n")
                    }
                    headersString += "\n"
                    box.method = HttpMethod.parse(eventGeneralized.method!!)
                    box.path = URLDecoder.decode(eventGeneralized.path!!, "UTF-8")
                    box.httpProtocol = HttpProtocol.parse(eventGeneralized.httpProtocol!!)

                    packet = eventGeneralized.method!!.toByteArray() + " ".toByteArray() + box.path!!.toByteArray() + " ".toByteArray() + eventGeneralized.httpProtocol!!.toByteArray() +
                            "\r\n".toByteArray() + headersString.toByteArray() + packet
                }
                if (DifferentialPrivacy.NEW_EVENT_GENERALIZED in requestsAnonymized) {
                    // TODO: gestire con richiesta POST o GET al server remoto
                    val newEventGeneralized = requestsAnonymized[DifferentialPrivacy.NEW_EVENT_GENERALIZED]
                            ?: error("")
                    when (newEventGeneralized.method) {
                        "POST" -> {
                            when (newEventGeneralized.contentType) {
                                ContentType.X_WWW_FORM_URLENCODED -> {
                                    var client = getUnsafeOkHttpClient()!!
                                    if (newEventGeneralized.contentEncoding == ContentEncoding.GZIP || newEventGeneralized.contentEncoding == ContentEncoding.DEFLATE) {
                                        client = client.newBuilder().addInterceptor(CompressRequestInterceptor()).build()
                                    }
                                    val url = URLDecoder.decode("https://${newEventGeneralized.host}${newEventGeneralized.path}", "UTF-8")
                                    val requestBuilder = Request.Builder()
                                    val formBodyBuilder = FormBody.Builder()
                                    for (key in newEventGeneralized.body!!.keys) {
                                        formBodyBuilder.add(key, newEventGeneralized.body!![key]!!.anonymizedString())
                                    }
                                    val formBody = formBodyBuilder.build()

                                    val headersJsonObject = JSONObject(newEventGeneralized.headers!!)
                                    for (key in headersJsonObject.keys()) {
                                        requestBuilder.addHeader(key, headersJsonObject.getJSONArray(key).getString(0))
                                    }
                                    val request = requestBuilder.url(url).post(formBody).build()
                                    client.newCall(request).enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            Log.e(TAG, "Error on sending new url-encoded request: $e")
                                            if (isDebugEnabled.get()) {
                                                var bodySent = ""
                                                try {
                                                    bodySent = URLDecoder.decode(bodyToString(newEventGeneralized, request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error on decode bodySent: $e")
                                                }
                                                Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, "Error on sending new url-encoded request: $e").insertOrUpdateError(realmConfigLog)
                                                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new url-encoded request due to reason: $e --- app ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                            }
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            response.use {
                                                if (!response.isSuccessful) {
                                                    Log.d(TAG, "Request url-encoded body: ${bodyToString(newEventGeneralized, request)!!}")
                                                }

                                                Log.d(TAG, "New url-encoded response: ${response.code}")
                                                /*if (isDebugEnabled.get()) {
                                                    var bodySent = ""
                                                    try {
                                                        bodySent = URLDecoder.decode(bodyToString(request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error on decode bodySent: $e")
                                                    }
                                                    com.dave.realmdatahelper.debug.Response(newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, response.code.toString()).insertOrUpdateResponse(realmConfigLog)
                                                }*/
                                            }
                                        }

                                    })
                                }
                                ContentType.MULTIPART_FORM_DATA -> {
                                    var client = getUnsafeOkHttpClient()!!
                                    if (newEventGeneralized.contentEncoding == ContentEncoding.GZIP || newEventGeneralized.contentEncoding == ContentEncoding.DEFLATE) {
                                        client = client.newBuilder().addInterceptor(CompressRequestInterceptor()).build()
                                    }
                                    val url = URLDecoder.decode("https://${newEventGeneralized.host}${newEventGeneralized.path}", "UTF-8")
                                    val multiPartBodyBuilder = MultipartBody.Builder()

                                    for (entry in newEventGeneralized.body!!) {
                                        val multipartValueWrapper = entry.value as MultipartValueWrapper
                                        if (multipartValueWrapper.filename != null && multipartValueWrapper.contentType != null) {
                                            multiPartBodyBuilder.addFormDataPart(entry.key, multipartValueWrapper.filename, bodyParser.encodeValueBody(multipartValueWrapper)
                                                    .toRequestBody(multipartValueWrapper.contentType!!.toMediaType()))
                                        } else {
                                            multiPartBodyBuilder.addFormDataPart(entry.key, bodyParser.encodeValueBody(multipartValueWrapper))
                                        }
                                    }
                                    val multipartBody = multiPartBodyBuilder.setType(MultipartBody.FORM).build()

                                    val requestBuilder = Request.Builder()
                                    val headersJsonObject = JSONObject(newEventGeneralized.headers!!)
                                    for (key in headersJsonObject.keys()) {
                                        requestBuilder.addHeader(key, headersJsonObject.getJSONArray(key).getString(0))
                                    }
                                    val request = requestBuilder.url(url).post(multipartBody).build()
                                    client.newCall(request).enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            Log.e(TAG, "Error on sending new multipart request: $e")
                                            if (isDebugEnabled.get()) {
                                                var bodySent = ""
                                                try {
                                                    bodySent = URLDecoder.decode(bodyToString(newEventGeneralized, request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error on decode bodySent: $e")
                                                }
                                                Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, "Error on sending new multipart request: $e").insertOrUpdateError(realmConfigLog)
                                                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new multipart request: $e --- app: ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                            }
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            response.use {
                                                if (!response.isSuccessful) {
                                                    Log.d(TAG, "Request multipart body: ${bodyToString(newEventGeneralized, request)!!}")
                                                }

                                                Log.d(TAG, "New multipart response: ${response.code}")
                                                /*if (isDebugEnabled.get()) {
                                                    var bodySent = ""
                                                    try {
                                                        bodySent = URLDecoder.decode(bodyToString(request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error on decode bodySent: $e")
                                                    }
                                                    com.dave.realmdatahelper.debug.Response(newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, response.code.toString()).insertOrUpdateResponse(realmConfigLog)
                                                }*/
                                            }
                                        }
                                    })
                                }
                                ContentType.APPLICATION_JSON -> {
                                    var client = getUnsafeOkHttpClient()!!
                                    if (newEventGeneralized.contentEncoding == ContentEncoding.GZIP || newEventGeneralized.contentEncoding == ContentEncoding.DEFLATE) {
                                        client = client.newBuilder().addInterceptor(CompressRequestInterceptor()).build()
                                    }
                                    val url = URLDecoder.decode("https://${newEventGeneralized.host}${newEventGeneralized.path}", "UTF-8")
                                    val requestBuilder = Request.Builder()
                                    val headersJsonObject = JSONObject(newEventGeneralized.headers!!)
                                    for (key in headersJsonObject.keys()) {
                                        requestBuilder.addHeader(key, headersJsonObject.getJSONArray(key).getString(0))
                                    }
                                    val request = requestBuilder
                                            .url(url)
                                            .post(bodyParser.encodeBody(newEventGeneralized, box, isDebugEnabled, realmConfigLog, androidId)
                                                    .toRequestBody("application/json".toMediaType()))
                                            .build()

                                    client.newCall(request).enqueue(object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            Log.e(TAG, "Error on sending new json request: $e")
                                            if (isDebugEnabled.get()) {
                                                var bodySent = ""
                                                try {
                                                    bodySent = URLDecoder.decode(bodyToString(newEventGeneralized, request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error on decode bodySent: $e")
                                                }
                                                Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, "Error on sending new json request: $e").insertOrUpdateError(realmConfigLog)
                                                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new json request: $e --- app: ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                            }
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            response.use {
                                                if (!response.isSuccessful) {
                                                    Log.d(TAG, "Request json body: ${bodyToString(newEventGeneralized, request)!!}")
                                                }

                                                Log.d(TAG, "New json response: ${response.code}")
                                                /*if (isDebugEnabled.get()) {
                                                    var bodySent = ""
                                                    try {
                                                        bodySent = URLDecoder.decode(bodyToString(request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error on decode bodySent: $e")
                                                    }
                                                    com.dave.realmdatahelper.debug.Response(newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, response.code.toString()).insertOrUpdateResponse(realmConfigLog)
                                                }*/
                                            }
                                        }
                                    })
                                }
                                ContentType.TEXT_PLAIN -> {
                                    var client = getUnsafeOkHttpClient()!!
                                    if (newEventGeneralized.contentEncoding == ContentEncoding.GZIP || newEventGeneralized.contentEncoding == ContentEncoding.DEFLATE) {
                                        client = client.newBuilder().addInterceptor(CompressRequestInterceptor()).build()
                                    }
                                    val url = URLDecoder.decode("https://${newEventGeneralized.host}/${newEventGeneralized.path}", "UTF-8")
                                    val requestBuilder = Request.Builder()
                                    val headersJsonObject = JSONObject(newEventGeneralized.headers!!)
                                    for (key in headersJsonObject.keys()) {
                                        requestBuilder.addHeader(key, headersJsonObject.getJSONArray(key).getString(0))
                                    }
                                    val request = requestBuilder
                                            .url(url)
                                            .post(bodyParser.encodeBody(newEventGeneralized, box, isDebugEnabled, realmConfigLog, androidId).toRequestBody("text/plain".toMediaType())).build()

                                    client.newCall(request).enqueue(object: Callback {
                                        override fun onFailure(call: Call, e: IOException) {
                                            Log.e(TAG, "Error on sending new text/plain request: $e")
                                            if (isDebugEnabled.get()) {
                                                var bodySent = ""
                                                try {
                                                    bodySent = URLDecoder.decode(bodyToString(newEventGeneralized, request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error on decode bodySent: $e")
                                                }
                                                Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, "Error on sending new text/plain request: $e").insertOrUpdateError(realmConfigLog)
                                                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new text/plain request: $e --- app: ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                            }
                                        }

                                        override fun onResponse(call: Call, response: Response) {
                                            if (!response.isSuccessful) {
                                                Log.d(TAG, "Request text/plain body: ${bodyToString(newEventGeneralized, request)!!}")
                                            }

                                            Log.d(TAG, "New text/plain response: ${response.code}")
                                            /*if (isDebugEnabled.get()) {
                                                var bodySent = ""
                                                try {
                                                    bodySent = URLDecoder.decode(bodyToString(request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error on decode bodySent: $e")
                                                }
                                                com.dave.realmdatahelper.debug.Response(newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, response.code.toString()).insertOrUpdateResponse(realmConfigLog)
                                            }*/
                                        }

                                    })
                                }
                                else -> {
                                    Log.d(TAG, "Error on sending new injected request")
                                    if (isDebugEnabled.get()) {
                                        Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, "", "Error on sending new injected request").insertOrUpdateError(realmConfigLog)
                                        Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new injected request --- app: ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                    }
                                }
                            }
                        }

                        "GET" -> {
                            val client = getUnsafeOkHttpClient()!!
                            val url = URLDecoder.decode("https://${newEventGeneralized.host}${newEventGeneralized.path}", "UTF-8")
                            val requestBuilder = Request.Builder()

                            val headersJSONObject = JSONObject(newEventGeneralized.headers!!)
                            for (key in headersJSONObject.keys()) {
                                requestBuilder.addHeader(key, headersJSONObject.getJSONArray(key).getString(0))
                            }
                            val request = requestBuilder.url(url).build()
                            client.newCall(request).enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    Log.e(TAG, "Error on sending new get request: $e")
                                    if (isDebugEnabled.get()) {
                                        var bodySent = ""
                                        try {
                                            bodySent = URLDecoder.decode(bodyToString(newEventGeneralized, request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error on decode bodySent: $e")
                                        }
                                        Error(requestToAnonymize.packageName, newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, "Error on sending new get request: $e").insertOrUpdateError(realmConfigLog)
                                        Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending new get request: $e --- app: ${requestToAnonymize.packageName}", "injectingRequest", "debug")
                                    }
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    response.use {
                                        Log.d(TAG, "New get response: ${response.code}")
                                        /*if (isDebugEnabled.get()) {
                                            var bodySent = ""
                                            try {
                                                bodySent = URLDecoder.decode(bodyToString(request)!!.replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error on decode bodySent: $e")
                                            }
                                            com.dave.realmdatahelper.debug.Response(newEventGeneralized.host!!, newEventGeneralized.headers!!, bodySent, response.code.toString()).insertOrUpdateResponse(realmConfigLog)
                                        }*/
                                    }
                                }

                            })
                        }
                    }
                }
            } else {
                Log.d(TAG, "DataAnonymizer Thread find selectedPrivacyLevel = NULL or selectedPrivacyLevel = 0, so it sends the original packet")
                packet = box.buffer.array()
            }

            box.lock.lock()
            try {
                //Log.d(TAG, "DataAnonymizer Thread finish to anonymize request: ${requestToAnonymize.id}")
                box.packet = packet
                box.condition.signal()
            } catch (e: Exception) {
                Log.d(TAG, "Error on sending back to HttpInterceptor request anonymized: ${requestToAnonymize.id} due to error: $e")
                if (isDebugEnabled.get()) {
                    Error("", "", "", "", "Error on sending back to HttpInterceptor request anonymized: ${requestToAnonymize.id} due to error: $e").insertOrUpdateError(realmConfigLog)
                    Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on sending back to HttpInterceptor request anonymized due to error: $e --- app: ${requestToAnonymize.packageName}", "sendingBoxToHttpInterceptor", "error")
                }
            } finally {
                box.lock.unlock()
            }
        }
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient? {
        return try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts: Array<TrustManager> = arrayOf(
                    object : X509TrustManager {
                        @Throws(CertificateException::class)
                        override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
                        }

                        @Throws(CertificateException::class)
                        override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
                        }

                        override fun getAcceptedIssuers(): Array<X509Certificate> {
                            return arrayOf()
                        }
                    }
            )

            // Install the all-trusting trust manager
            val sslContext: SSLContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
            val builder: OkHttpClient.Builder = OkHttpClient.Builder()
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
            builder.build()
        } catch (e: java.lang.Exception) {
            throw RuntimeException(e)
        }
    }

    private fun bodyToString(multidimensionalRequest: MultidimensionalData, request: Request): String? {
        try {
            val copy = request.newBuilder().build()
            val buffer = Buffer()
            copy.body?.writeTo(buffer)

            return buffer.readUtf8()
        } catch (e: IOException) {
            if (isDebugEnabled.get()) {
                Error(multidimensionalRequest.packageName!!, multidimensionalRequest.host!!, multidimensionalRequest.headers!!, "", "Error on decoding body request: $e").insertOrUpdateError(realmConfigLog)
                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on decoding body request: $e --- app: ${multidimensionalRequest.packageName}", "injectingRequest", "debug")
            }
        }
        return ""
    }

    internal class CompressRequestInterceptor : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            if (originalRequest.body == null || (originalRequest.header("Content-Encoding") != "gzip" && originalRequest.header("Content-Encoding") != "deflate")) {
                return chain.proceed(originalRequest)
            }

            if (originalRequest.header("Content-Encoding") == "gzip") {
                val compressedRequest = originalRequest.newBuilder()
                        .method(originalRequest.method, gzip(originalRequest.body))
                        .build()
                return chain.proceed(compressedRequest)
            }
            if (originalRequest.header("Content-Encoding") == "deflate") {
                val compressedRequest = originalRequest.newBuilder()
                        .method(originalRequest.method, deflate(originalRequest.body))
                        .build()
                return chain.proceed(compressedRequest)
            }
            return chain.proceed(originalRequest)
        }

        private fun gzip(body: RequestBody?): RequestBody {
            return object : RequestBody() {
                override fun contentType(): MediaType? {
                    return body!!.contentType()
                }

                override fun contentLength(): Long {
                    return -1 // We don't know the compressed length in advance!
                }

                override fun writeTo(sink: BufferedSink) {
                    val gzipSink: BufferedSink = GzipSink(sink).buffer()
                    body!!.writeTo(gzipSink)
                    gzipSink.close()
                }
            }
        }

        private fun deflate(body: RequestBody?): RequestBody {
            return object : RequestBody() {
                override fun contentType(): MediaType? {
                    return body!!.contentType()
                }

                override fun contentLength(): Long {
                    return -1
                }

                override fun writeTo(sink: BufferedSink) {
                    val deflaterSink: BufferedSink = DeflaterSink(sink, Deflater()).buffer()
                    body!!.writeTo(deflaterSink)
                    deflaterSink.close()
                }

            }
        }
    }

    class Box {
        var packet: ByteArray? = null
        val buffer: ByteBuffer
        var method: HttpMethod? = null
        var path: String? = null
        var httpProtocol: HttpProtocol? = null
        val requestToAnonymize: AnalyticsRequest
        val lock: ReentrantLock
        val condition: Condition

        constructor(requestToAnonymize: AnalyticsRequest, buffer: ByteBuffer) {
            this.buffer = buffer
            this.requestToAnonymize = requestToAnonymize
            this.lock = ReentrantLock()
            this.condition = lock.newCondition()
        }
    }

    data class DghBox(var dgh: JSONObject, var dghKeysSet: MutableSet<String>, var dghFacebook: JSONObject, var dghFacebookKeysSet: MutableSet<String>)
}