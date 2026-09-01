package it.unige.hidedroid.interceptor

import android.content.pm.PackageManager
import android.util.Log
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.realmdatahelper.debug.PrivateTracker
import com.dave.realmdatahelper.debug.Request as DebugRequest
import com.dave.realmdatahelper.debug.Error as DebugError
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.github.megatronking.netbare.http.HttpIndexedInterceptor
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.http.HttpRequestChain
import com.github.megatronking.netbare.http.HttpResponseChain
import io.netty.buffer.Unpooled
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import it.unige.hidedroid.interceptor.UtilsHttpInterceptor.fromMapToString
import it.unige.hidedroid.log.DomainLogger
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.TrackersMappingDomainName
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.utils.DomainAnonymizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpInterceptor(var packageManager: PackageManager,
                      var trackersMappingDomainName: TrackersMappingDomainName,
                      var listAppToMonitoring: MutableList<String>,
                      var realmConfigPrivateTracker: RealmConfiguration,
                      var realmConfigLog: RealmConfiguration,
                      var listPrivateFields: List<String>,
                      var isDebugEnabled: AtomicBoolean,
                      var isDomainBlockingEnabled: AtomicBoolean,
                      var idPacket: AtomicLong,
                      var dataAnonymizer: DataAnonymizer) : HttpIndexedInterceptor() {

    companion object {
        val TAG = HttpInterceptor::class.java.name

        /**
         * Buffer size when decompressing content.
         */
        const val DECOMPRESS_BUFFER_SIZE = 16192

        /**
         * Synthetic answer for blocked requests: an empty 204 with the connection
         * closed, so the target app sees a completed response instead of hanging
         * until its own timeout (and usually retrying).
         */
        private val BLOCKED_RESPONSE = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()

        fun createFactory(packageManager: PackageManager,
                          trackersMappingDomainName: TrackersMappingDomainName,
                          listAppToMonitoring: MutableList<String>,
                          realmConfigPrivateTracker: RealmConfiguration,
                          realmConfigLog: RealmConfiguration,
                          listPrivateFields: List<String>,
                          isDebugEnabled: AtomicBoolean,
                          isDomainBlockingEnabled: AtomicBoolean,
                          dataAnonymizer: DataAnonymizer): HttpInterceptorFactory {

            // createFactory() is invoked from the UI thread while assembling the VPN config,
            // but Realm no longer allows write transactions on the UI thread. Compute the
            // next packet id on an IO dispatcher so the transaction runs off the main thread.
            val idPacket = runBlocking(Dispatchers.IO) {
                Realm.getDefaultInstance().use { realm ->
                    var nextId = 0L
                    realm.executeTransaction { r ->
                        val result = r.where<AnalyticsRequest>().max("id")
                        if (result != null) {
                            nextId = result.toLong() + 1
                        }
                    }
                    AtomicLong(nextId)
                }
            }

            LoggerHideDroid.d(TAG, "I'm doing createFactory in HttpInterceptor")
            return HttpInterceptorFactory { HttpInterceptor(packageManager, trackersMappingDomainName, listAppToMonitoring, realmConfigPrivateTracker, realmConfigLog, listPrivateFields, isDebugEnabled, isDomainBlockingEnabled, idPacket, dataAnonymizer) }
        }
    }


    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun intercept(chain: HttpRequestChain, buffer: ByteBuffer, index: Int) {
        // if uid is known
        if (packageManager.getPackagesForUid(chain.request().uid()) != null) {
            // get package name
            val packageNameApp = packageManager.getPackagesForUid(chain.request().uid())!![0]
            // if host belong to an analytics domain known
            var isTracked: Boolean
            var isPrivate = false

            val realm = Realm.getInstance(realmConfigPrivateTracker)
            realm.use { realm ->
                val realmResult = realm.where<PrivateTracker>().equalTo("host", chain.request().host()).findFirst()
                if (realmResult != null) {
                    isPrivate = true
                }
            }

            // An explicit per-domain record (default/manual/automatic) overrides the
            // bundled regex list for that exact host; no record falls back to it unchanged.
            val requestDomainOverride = DomainAnonymizationHelper.getByHost(chain.request().host())
            isTracked = requestDomainOverride?.enabled
                    ?: this.trackersMappingDomainName.isUrlPresent(chain.request().host())

            if (this.listAppToMonitoring.contains(packageNameApp)) {
                val host = chain.request().host()
                val isBlocked = isDomainBlockingEnabled.get() && DomainAnonymizationHelper.isBlocked(host, packageNameApp)

                if (isBlocked) {
                    if (index == 0) {
                        DomainLogger.log(
                                chain.request().time(),
                                packageNameApp,
                                host,
                                chain.request().method().toString(),
                                chain.request().path(),
                                true,
                                false,
                                false
                        )
                        // The request is not forwarded, so answer it ourselves on the first
                        // packet or the connection hangs until the app's timeout. Only
                        // HTTP/1.x can take a raw HTTP/1.1 response; HTTP/2 streams stay
                        // silently dropped.
                        // ponytail: h2 blocked streams get no RST_STREAM, apps see a
                        // stalled stream until their own timeout; add frame injection
                        // if that becomes a problem.
                        if (chain.request().streamId() == -1) {
                            try {
                                chain.injectResponse(BLOCKED_RESPONSE)
                            } catch (e: IOException) {
                                LoggerHideDroid.e(TAG, "Failed to answer blocked request to $host: $e")
                            }
                        }
                    }
                    LoggerHideDroid.d(TAG, "Blocking request to $host from $packageNameApp")
                    return
                }

                if (index == 0) {
                    // Log every first-packet request from a monitored app, body or not.
                    // Body-based private-field detection below may upgrade isPrivate, but
                    // that refinement is not reflected here; hosts already flagged in the
                    // PrivateTracker table are caught by the isPrivate check above.
                    DomainLogger.log(
                            chain.request().time(),
                            packageNameApp,
                            host,
                            chain.request().method().toString(),
                            chain.request().path(),
                            false,
                            isTracked,
                            isPrivate
                    )

                    // "Automatic" domain discovery: every host seen for a monitored
                    // app gets listed (anonymization off by default) for the user to
                    // toggle later - independent of whether it ends up tracked here.
                    DomainAnonymizationHelper.recordAutomaticIfAbsent(host)

                    // if is the first that is complete of all data
                    val offsetBody = chain.request().requestBodyOffset()
                    if (offsetBody < buffer.array().size) {
                        val byteBuffer = Arrays.copyOfRange(buffer.array(), offsetBody, buffer.array().size)
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
                                            val content = UtilsHttpInterceptor.decompressContents(temp, "gzip", packageNameApp, host, requestHeadersString, isDebugEnabled)
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
                                                    Log.e("errorGzipDecompression", "Error on gzip decompressing: $exception")
                                                    try {
                                                        storeDebugError(packageNameApp, host, requestHeadersString, "",
                                                                "gzip decompressing: ${exception.message}")
                                                    } catch (e: Exception) { /* ignore */ }
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
                                            Log.e("errorFlurryDecoding", "Error on flurry decoding: $exception")
                                            try {
                                                storeDebugError(packageNameApp, host, requestHeadersString, bodyDecodedAndParsed,
                                                        "flurry decoding: ${exception.message}")
                                            } catch (e: Exception) { /* ignore */ }
                                        }
                                        exception.printStackTrace()
                                    }
                                }
                                requestHeadersString.contains("\"Content-Encoding\":[\"deflate\"]") -> {
                                    val byteBufferTrimmed = UtilsHttpInterceptor.trim(byteBuffer, contentLength)
                                    if (byteBufferTrimmed!!.isNotEmpty()) {
                                        // Il magic bytes di un DEFLATE è 0x78 = 120 .
                                        val startIndex = byteBufferTrimmed.indexOf(120)
                                        val secondMagicByte = listOf<Int>(1, 94, -100, -38, 32, 125, -69, -7)
                                        if (startIndex != -1 && secondMagicByte.contains(byteBufferTrimmed[startIndex + 1].toInt())) {
                                            val temp = (byteBufferTrimmed).copyOfRange(startIndex, byteBufferTrimmed.size)
                                            val content = UtilsHttpInterceptor.decompressContents(temp, "deflate", packageNameApp, host, requestHeadersString, isDebugEnabled)
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
                                                    Log.e("errorDeflateDecompressing", "Error on deflate decompressing: $exception")
                                                    try {
                                                        storeDebugError(packageNameApp, host, requestHeadersString, "",
                                                                "deflate decompressing: ${exception.message}")
                                                    } catch (e: Exception) { /* ignore */ }
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
                                            Log.e("errorStandardDecoding", "Error on standard decoding: $e")
                                            try {
                                                storeDebugError(packageNameApp, host, requestHeadersString, data,
                                                        "standard decoding: ${e.message}")
                                            } catch (ex: Exception) { /* ignore */ }
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

                                if (isDebugEnabled.get()) {
                                    try {
                                        storeDebugRequest(id, packageNameApp, host, requestHeadersString,
                                                bodyString, chain.request().method().toString(),
                                                chain.request().path(), chain.request().time(),
                                                isTracked, isPrivate, chain.request().httpProtocol().toString())
                                    } catch (e: Exception) {
                                        LoggerHideDroid.e(TAG, "Failed to store debug request: $e")
                                    }
                                }

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
            } else {
                // app not selected for monitoring, or domain not known: forward as-is
                chain.process(buffer)
            }
        } else {
            // if uid is not known forward the process
            chain.process(buffer)
        }

    }

    override fun intercept(chain: HttpResponseChain, buffer: ByteBuffer, index: Int) {
        chain.process(buffer)
        var isPrivate = false
        val responseDomainOverride = DomainAnonymizationHelper.getByHost(chain.response().host())
        var isTracked = responseDomainOverride?.enabled
                ?: this.trackersMappingDomainName.isUrlPresent(chain.response().host())
        val realm = Realm.getInstance(realmConfigPrivateTracker)
        realm.use { realm ->
            val realmResult = realm.where<PrivateTracker>().equalTo("host", chain.response().host()).findFirst()
            if (realmResult != null) {
                isPrivate = true
            }
        }
        if (isTracked || isPrivate) {
            if (index == 0) {
                Log.d(TAG, "Response from ${chain.response().host()}:\n${String(buffer.array(), StandardCharsets.UTF_8)}")
            }
        }
    }

    private fun storeDebugRequest(id: Long, packageName: String, host: String, headers: String,
                                  body: String, method: String, path: String, timeStamp: Long,
                                  isTracked: Boolean, isPrivate: Boolean, httpProtocol: String) {
        Realm.getInstance(realmConfigLog).use { realm ->
            val debugRequest = DebugRequest(
                    id,
                    packageName,
                    host,
                    headers,
                    body,
                    method,
                    path,
                    timeStamp,
                    isTracked,
                    isPrivate,
                    httpProtocol
            )
            realm.executeTransaction { r ->
                r.insertOrUpdate(debugRequest)
            }
        }
    }

    private fun storeDebugError(packageName: String, host: String, headers: String, body: String,
                                error: String) {
        Realm.getInstance(realmConfigLog).use { realm ->
            val nextId = realm.where<DebugError>().max("id")?.let { it.toLong() + 1 } ?: 1L
            val debugError = DebugError(
                    nextId,
                    packageName,
                    host,
                    headers,
                    body,
                    error,
                    System.currentTimeMillis()
            )
            realm.executeTransaction { r ->
                r.insertOrUpdate(debugError)
            }
        }
    }

}