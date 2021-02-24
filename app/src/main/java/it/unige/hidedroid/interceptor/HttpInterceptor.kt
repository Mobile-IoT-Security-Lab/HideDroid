package it.unige.hidedroid.interceptor

import android.content.pm.PackageManager
import android.util.Log
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.realmdatahelper.debug.PrivateTracker
import com.dave.realmdatahelper.debug.Request
import com.dave.realmdatahelper.debug.Response
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.utils.Utils
import com.github.megatronking.netbare.http.HttpIndexedInterceptor
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.http.HttpRequestChain
import com.github.megatronking.netbare.http.HttpResponseChain
import io.netty.buffer.Unpooled
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import it.unige.hidedroid.interceptor.UtilsHttpInterceptor.fromMapToString
import it.unige.hidedroid.interceptor.UtilsHttpInterceptor.isAnalyticsRequest
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.*
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpInterceptor(var packageManager: PackageManager,
                      var trackersMappingDomainName: TrackersMappingDomainName,
                      var listAppToMonitoring: MutableList<String>,
                      var realmConfigLog: RealmConfiguration,
                      var realmConfigPrivateTracker: RealmConfiguration,
                      var listPrivateFields: List<String>,
                      var isDebugEnabled: AtomicBoolean,
                      var idPacket: AtomicLong,
                      var dataAnonymizer: DataAnonymizer,
                      var androidId: String) : HttpIndexedInterceptor() {

    companion object {
        val TAG = HttpInterceptor::class.java.name

        /**
         * Buffer size when decompressing content.
         */
        const val DECOMPRESS_BUFFER_SIZE = 16192

        fun createFactory(packageManager: PackageManager,
                          trackersMappingDomainName: TrackersMappingDomainName,
                          listAppToMonitoring: MutableList<String>,
                          realmConfigLog: RealmConfiguration,
                          realmConfigPrivateTracker: RealmConfiguration,
                          listPrivateFields: List<String>,
                          isDebugEnabled: AtomicBoolean,
                          dataAnonymizer: DataAnonymizer,
                          androidId: String): HttpInterceptorFactory {

            var idPacket = AtomicLong(0)
            val realm = Realm.getDefaultInstance()
            realm.use { realm ->
                realm.executeTransaction { realm ->
                    val result = realm.where<AnalyticsRequest>().max("id")
                    if (result != null) {
                        idPacket = AtomicLong(result.toLong() + 1)
                    }
                }
            }

            LoggerHideDroid.d(TAG, "I'm doing createFactory in HttpInterceptor")
            return HttpInterceptorFactory { HttpInterceptor(packageManager, trackersMappingDomainName, listAppToMonitoring, realmConfigLog, realmConfigPrivateTracker, listPrivateFields, isDebugEnabled, idPacket, dataAnonymizer, androidId) }
        }
    }


    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun intercept(chain: HttpRequestChain, buffer: ByteBuffer, index: Int) {
        // if uid is known
        if (packageManager.getPackagesForUid(chain.request().uid()) != null) {
            // get package name
            val packageNameApp = packageManager.getPackagesForUid(chain.request().uid())!![0]
            // if host belong to an analytics domain known
            var isTracked = false
            var isPrivate = false

            val realm = Realm.getInstance(realmConfigPrivateTracker)
            realm.use { realm ->
                val realmResult = realm.where<PrivateTracker>().equalTo("host", chain.request().host()).findFirst()
                if (realmResult != null) {
                    isPrivate = true
                }
            }

            if (this.trackersMappingDomainName.isUrlPresent(chain.request().host())) {
                isTracked = true
            }
            if (this.listAppToMonitoring.contains(packageNameApp)) {
                if (index == 0) {
                    // if is the first that is complete of all data
                    val offsetBody = chain.request().requestBodyOffset()
                    if (offsetBody < buffer.array().size) {
                        val byteBuffer = Arrays.copyOfRange(buffer.array(), offsetBody, buffer.array().size)
                        val host = chain.request().host()
                        val requestHeaders = chain.request().requestHeaders()
                        val requestHeadersString = fromMapToString(requestHeaders)
                        var bodyString = ""
                        var bodyWithoutNotAsciiChar = ""

                        var contentLength = 0
                        if (requestHeaders != null && requestHeaders["Content-Length"] != null) {
                            contentLength = requestHeaders["Content-Length"]?.component1()?.toInt()!!
                        }

                        // Controllo che il pacchetto non contenga audio/immagini/video/3Dmodel/font
                        if (requestHeaders != null && requestHeaders["Content-Type"] != null
                                && (!requestHeaders["Content-Type"]!!.contains("audio")
                                        && !requestHeaders["Content-Type"]!!.contains("image")
                                        && !requestHeaders["Content-Type"]!!.contains("video")
                                        && !requestHeaders["Content-Type"]!!.contains("model")
                                        && !requestHeaders["Content-Type"]!!.contains("font"))) {

                            when {
                                //---------- Facebook ----------
                                requestHeadersString?.contains("\"Content-Encoding\":[\"gzip\"]")!! -> {
                                    //------ non utilizzo questo metodo perchè extractReadableBytes non funziona correttamente
                                    //------ per questo utilizziamo la funzione trim + indexOf(31)
                                    //val wrappedByteBuf = Unpooled.wrappedBuffer(trim(byteBuffer))
                                    //val newByteBuffer = UtilsHttpInterceptor.extractReadableBytes(wrappedByteBuf)

                                    val byteBufferTrimmed = UtilsHttpInterceptor.trim(byteBuffer, contentLength)
                                    if (byteBufferTrimmed!!.isNotEmpty()) {
                                        // Magic bytes di un GZIP sono 31 -117.
                                        val startIndex = byteBufferTrimmed.indexOf(31)
                                        if (startIndex != -1 && byteBufferTrimmed[startIndex + 1].toInt() == -117) {
                                            val temp = (byteBufferTrimmed).copyOfRange(startIndex, byteBufferTrimmed.size)
                                            val content = UtilsHttpInterceptor.decompressContents(temp, "gzip", packageNameApp, host, requestHeadersString, isDebugEnabled, realmConfigLog, androidId)
                                            try {
                                                bodyString = URLDecoder.decode(String(content!!, StandardCharsets.UTF_8).replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                if (requestHeadersString.contains("application/x-www-form-urlencoded")) {
                                                    val bodyStringReplaced = bodyString.replace("[&](?=.*[&])".toRegex(), "&&&")
                                                    bodyWithoutNotAsciiChar = bodyStringReplaced.substring(0, bodyStringReplaced.lastIndex)
                                                } else {
                                                    bodyWithoutNotAsciiChar = bodyString
                                                }
                                            } catch (exception: Exception) {
                                                if (isDebugEnabled.get()) {
                                                    com.dave.realmdatahelper.debug.Error(packageNameApp, host, requestHeadersString, String(byteBuffer), "Error on gzip decompressing: $exception").insertOrUpdateError(realmConfigLog)
                                                    Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on gzip decompressing: $exception --- app: $packageNameApp", "decompressingRequest", "error")
                                                }
                                                exception.printStackTrace()
                                            }
                                        }
                                    }
                                }
                                //---------- Flurry ----------
                                host == "data.flurry.com" -> {
                                    // TODO: non sono ancora riuscito a testare se funzioni correttamente
                                    val bodyDecoded = UtilsHttpInterceptor.extractReadableBytes(Unpooled.wrappedBuffer(byteBuffer))?.let { String(it, StandardCharsets.UTF_8).replace("[^\\x20-\\x7e]".toRegex(), "") }
                                    val bodyDecodedAndParsed = bodyDecoded!!.replace("\\},(?![\\s\"\\}])(.*?)\\{|\\}[^,]{0,10},(?=\\{)\\{|\\}(?!\\})[^,]*\\{".toRegex(), "},{")
                                    bodyDecodedAndParsed.substring(bodyDecodedAndParsed.indexOf('{'), bodyDecodedAndParsed.lastIndexOf('}') + 1)
                                    try {
                                        bodyString = URLDecoder.decode(bodyDecodedAndParsed, "UTF-8")
                                        bodyWithoutNotAsciiChar = bodyString
                                    } catch (exception: Exception) {
                                        if (isDebugEnabled.get()) {
                                            com.dave.realmdatahelper.debug.Error(packageNameApp, host, requestHeadersString, String(byteBuffer), "Error on flurry decoding: $exception").insertOrUpdateError(realmConfigLog)
                                            Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on flurry decoding: $exception --- app: $packageNameApp", "decodingRequest", "error")
                                        }
                                        exception.printStackTrace()
                                    }
                                }
                                requestHeadersString?.contains("\"Content-Encoding\":[\"deflate\"]")!! -> {
                                    val byteBufferTrimmed = UtilsHttpInterceptor.trim(byteBuffer, contentLength)
                                    if (byteBufferTrimmed!!.isNotEmpty()) {
                                        // Il magic bytes di un DEFLATE è 0x78 = 120 .
                                        val startIndex = byteBufferTrimmed.indexOf(120)
                                        val secondMagicByte = listOf<Int>(1, 94, -100, -38, 32, 125, -69, -7)
                                        if (startIndex != -1 && secondMagicByte.contains(byteBufferTrimmed[startIndex + 1].toInt())) {
                                            val temp = (byteBufferTrimmed!!).copyOfRange(startIndex, byteBufferTrimmed!!.size)
                                            val content = UtilsHttpInterceptor.decompressContents(temp, "deflate", packageNameApp, host, requestHeadersString, isDebugEnabled, realmConfigLog, androidId)
                                            try {
                                                bodyString = URLDecoder.decode(String(content!!, StandardCharsets.UTF_8).replace("[^\\x20-\\x7e]".toRegex(), ""), "UTF-8")
                                                if (requestHeadersString.contains("application/x-www-form-urlencoded")) {
                                                    val bodyStringReplaced = bodyString.replace("[&](?=.*[&])".toRegex(), "&&&")
                                                    bodyWithoutNotAsciiChar = bodyStringReplaced.substring(0, bodyStringReplaced.lastIndex)
                                                } else {
                                                    bodyWithoutNotAsciiChar = bodyString
                                                }
                                            } catch (exception: Exception) {
                                                if (isDebugEnabled.get()) {
                                                    com.dave.realmdatahelper.debug.Error(packageNameApp, host, requestHeadersString, String(byteBuffer), "Error on deflate decompressing: $exception").insertOrUpdateError(realmConfigLog)
                                                    Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on deflate decompressing: $exception --- app: $packageNameApp", "decompressingRequest", "error")
                                                }
                                                exception.printStackTrace()
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    var data = ""
                                    try {
                                        data = String(byteBuffer).replace("%(?![0-9a-fA-F]{2})", "%25")
                                        data = data.replace("\\+", "%2B")
                                        bodyString = URLDecoder.decode(data, "UTF-8")
                                    } catch (e: Exception) {
                                        if (isDebugEnabled.get()) {
                                            com.dave.realmdatahelper.debug.Error(packageNameApp, host, requestHeadersString, String(byteBuffer), "Error on standard decoding: $e").insertOrUpdateError(realmConfigLog)
                                            Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on standard decoding: $e --- app: $packageNameApp", "decodingRequest", "error")
                                        }
                                        e.printStackTrace()
                                    }
                                    bodyWithoutNotAsciiChar = UtilsHttpInterceptor.removeAllNonUtf8Char(bodyString)

                                    if (bodyWithoutNotAsciiChar.isNotEmpty()) {
                                        if (requestHeadersString.contains("application/x-www-form-urlencoded")) {
                                            bodyWithoutNotAsciiChar = bodyWithoutNotAsciiChar.replace("&", "&&&")
                                        }
                                    }
                                }
                            }

                            // controlla se il body contiene dati sensibili per capire se l'host è privato
                            if (!isTracked && !isPrivate) {
                                for (field in listPrivateFields) {
                                    if (bodyWithoutNotAsciiChar.toLowerCase(Locale.ROOT).contains(field.toLowerCase(Locale.ROOT))) {
                                        isPrivate = true
                                        val privateTracker = PrivateTracker(host)
                                        privateTracker.insertOrUpdate(realmConfigPrivateTracker)
                                        break
                                    }
                                }
                            }

                            var body = ""
                            if (!isTracked && !isPrivate) {
                                body = bodyWithoutNotAsciiChar
                                chain.process(buffer)
                            }

                            if (isDebugEnabled.get()) {
                                val request = Request(
                                        host,
                                        body,
                                        isTracked,
                                        isPrivate
                                )
                                request.insertOrUpdateRequest(realmConfigLog)
                            }

                            Log.d(TAG, "$host, $packageNameApp,  " +
                                    "size request ${UtilitiesStoreDataOnRealmDb.getEventBufferFromPackageName(packageNameApp, host).event.size}")

                            LoggerHideDroid.d(TAG, "Request Host : ${host}, Package Name: $packageNameApp")
                            if (isTracked || isPrivate) {
                                val id = idPacket.getAndIncrement()
                                val analyticsRequest = AnalyticsRequest(
                                        id,
                                        packageNameApp,
                                        host,
                                        chain.request().time(),
                                        buffer.array(),
                                        chain.request().method().toString(),
                                        chain.request().path(),
                                        chain.request().httpProtocol().toString(),
                                        requestHeadersString,
                                        offsetBody,
                                        bodyString,
                                        bodyWithoutNotAsciiChar
                                )
                                LoggerHideDroid.d(TAG, "Increments idPacket variable: ${idPacket.get()}")
                                analyticsRequest.insertOrUpdateEventBuffer()

                                val box = DataAnonymizer.Box(analyticsRequest, buffer)
                                dataAnonymizer.requestToAnonymizeLock.lock()
                                try {
                                    dataAnonymizer.requestToAnonymizeQueue.add(box)
                                    dataAnonymizer.requestToAnonymizeCondition.signal()
                                } finally {
                                    dataAnonymizer.requestToAnonymizeLock.unlock()
                                }

                                box.lock.lock()
                                try {
                                    while (box.packet == null) {
                                        try {
                                            box.condition.await()
                                        } catch (ie: InterruptedException) {

                                        }
                                    }
                                } finally {
                                    box.lock.unlock()
                                }

                                if (box.method != null) {
                                    chain.request().setMethod(box.method)
                                }
                                if (box.path != null) {
                                    chain.request().setPath(box.path)
                                }
                                if (box.httpProtocol != null) {
                                    chain.request().setHttpProtocol(box.httpProtocol)
                                }
                                chain.process(ByteBuffer.wrap(box.packet))
                            }
                        } else {
                            chain.process(buffer)
                        }
                    } else {
                        chain.process(buffer)
                    }
                } else {
                    chain.process(buffer)
                }
            } else if (this.listAppToMonitoring.contains(packageNameApp) && !isAnalyticsRequest(buffer.array())) {
                // if i should intercept the content but is not analyticsRequest --> forward
                chain.process(buffer)
            } else {
                // if domain is not known or the app is not selected by the user
                chain.process(buffer)
            }
        } else {
            // if uid is not known forward the process
            chain.process(buffer)
        }

    }

    override fun intercept(chain: HttpResponseChain, buffer: ByteBuffer, index: Int) {
        chain.process(buffer)
        var isTracked = false
        var isPrivate = false
        if (this.trackersMappingDomainName.isUrlPresent(chain.response().host())) {
            isTracked = true
        }
        val realm = Realm.getInstance(realmConfigPrivateTracker)
        realm.use { realm ->
            realm.executeTransaction { realm ->
                val realmResult = realm.where<PrivateTracker>().equalTo("host", chain.response().host()).findFirst()
                if (realmResult != null) {
                    isPrivate = true
                }
            }
        }
        if (isTracked || isPrivate) {
            if (index == 0) {
                Log.d(TAG, "Response from ${chain.response().host()}:\n${String(buffer.array(), StandardCharsets.UTF_8)}")
                if (isDebugEnabled.get()) {
                    Response(chain.response().host(), "", "response captured from interceptor", chain.response().code().toString()).insertOrUpdateResponse(realmConfigLog)
                }
            }
        }
    }

}