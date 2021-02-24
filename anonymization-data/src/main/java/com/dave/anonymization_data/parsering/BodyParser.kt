package com.dave.anonymization_data.parsering

import android.util.Base64
import android.util.Log
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.anonymization_data.data.ContentEncoding
import com.dave.anonymization_data.data.ContentType
import com.dave.anonymization_data.data.MultidimensionalData
import com.dave.anonymization_data.wrappers.*
import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.utils.Utils
import io.realm.RealmConfiguration
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemFactory
import org.apache.commons.fileupload.FileUpload
import org.apache.commons.fileupload.UploadContext
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.http.HttpEntity
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterOutputStream

/**
 * Classe che:
 * - analizza le richieste ricevute dal thread DataAnonymizer tramite la funzione parse()
 *   mettendole in un formato utile per la loro anonimizzazione
 * - codifica le richieste anonimizzate dal thread DataAnonymizer tramite la funzione encode()
 *   per il loro successivo invio
 */
class BodyParser {

    val TAG = BodyParser::class.java.name

    fun parse(request: AnalyticsRequest, isDebugEnabled: AtomicBoolean, realmConfigLog: RealmConfiguration, androidId: String): MultidimensionalData {
        val bodyOriginal: MutableMap<String, ValueWrapper> = mutableMapOf()
        val extraBytes = StringBuffer()
        when (val contentType = detectContentType(request.headersJson)) {
            ContentType.APPLICATION_JSON -> {
                try {
                    bodyOriginal["body"] = ValueWrapper(request.bodyString)
                    return MultidimensionalData(request.id, request.packageName, request.host, request.method, request.path,
                            request.httpProtocol, request.headersJson, bodyOriginal, contentType, extraBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Error on json body parsing at request ${request.id}: $e")
                }
            }

            ContentType.X_WWW_FORM_URLENCODED -> {
                try {
                    return decodeKeyValuePairs(request, bodyOriginal, contentType, extraBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Error on url-encoded body parsing at request ${request.id}: $e")
                }
            }

            ContentType.MULTIPART_FORM_DATA -> {
                try {
                    val boundary = prepareMultipartBody(request, isDebugEnabled, realmConfigLog, extraBytes, androidId)
                    return decodeMultipartKeyValuePairs(request, bodyOriginal, contentType, boundary,
                            isDebugEnabled, realmConfigLog, extraBytes, androidId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error on multipart/form-data body parsing at request ${request.id}: $e")
                }
            }

            ContentType.TEXT_PLAIN -> {
                bodyOriginal["body"] = ValueWrapper(request.bodyString)
                return MultidimensionalData(request.id, request.packageName, request.host, request.method, request.path,
                        request.httpProtocol, request.headersJson, bodyOriginal, contentType, extraBytes)
            }
        }
        return MultidimensionalData()
    }

    fun encodeBody(requestToEncode: MultidimensionalData, box: DataAnonymizer.Box, isDebugEnabled: AtomicBoolean, realmConfigLog: RealmConfiguration, androidId: String): ByteArray {
        var bodyEncoded = ""
        when (requestToEncode.contentType) {
            ContentType.APPLICATION_JSON -> {
                for (entry in requestToEncode.body!!) {
                    if (entry.value.fieldAnonymized is FieldJsonObject) {
                        bodyEncoded = (entry.value.fieldAnonymized as FieldJsonObject).jsonObject.toString()
                    } else if (entry.value.fieldAnonymized is FieldJsonArray) {
                        bodyEncoded = (entry.value.fieldAnonymized as FieldJsonArray).jsonArray.toString()
                    }
                }
            }

            ContentType.X_WWW_FORM_URLENCODED -> {
                if (requestToEncode.body!!.isNotEmpty()) {
                    for (entry in requestToEncode.body!!) {
                        bodyEncoded += "${entry.key}=${encodeValueBody(entry.value)}&"
                    }
                    bodyEncoded = bodyEncoded.substring(0, bodyEncoded.length - 1)
                }
            }

            ContentType.MULTIPART_FORM_DATA -> {
                val multipartEntityBuilder = MultipartEntityBuilder.create()
                multipartEntityBuilder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                val headersJson = JSONObject(requestToEncode.headers!!)
                val contentType = headersJson.getString("Content-Type")
                val boundaryString = contentType.split("boundary=")[1]
                multipartEntityBuilder.setBoundary(boundaryString.substring(0, boundaryString.length - 2))
                for (entry in requestToEncode.body!!) {
                    val multipartValueWrapper = entry.value as MultipartValueWrapper
                    if (multipartValueWrapper.filename != null && multipartValueWrapper.contentType != null) {
                        multipartEntityBuilder.addBinaryBody(entry.key, encodeValueBody(multipartValueWrapper).toByteArray(),
                                org.apache.http.entity.ContentType.create(multipartValueWrapper.contentType), multipartValueWrapper.filename)
                    } else {
                        multipartEntityBuilder.addTextBody(entry.key, encodeValueBody(multipartValueWrapper))
                    }
                }
                val httpEntity: HttpEntity = multipartEntityBuilder.build()
                val outputStream = ByteArrayOutputStream()
                httpEntity.writeTo(outputStream)
                bodyEncoded = outputStream.toString()
                val endIndex = bodyEncoded.lastIndexOf("--")
                bodyEncoded = bodyEncoded.substring(0, endIndex) + "\r\n".repeat(2)
                if (!requestToEncode.extraBytes.isNullOrEmpty()) {
                    bodyEncoded = requestToEncode.extraBytes.toString() + bodyEncoded
                }
            }

            ContentType.TEXT_PLAIN -> {
                for (entry in requestToEncode.body!!) {
                    if (entry.value.fieldAnonymized is FieldPlaintext) {
                        bodyEncoded = (entry.value.fieldAnonymized as FieldPlaintext).plaintext
                    }
                }
            }

            else -> {
                bodyEncoded = "I can't encode with a content-type"
            }
        }

        val resultBody: ByteArray
        resultBody = when (requestToEncode.contentEncoding) {
            ContentEncoding.GZIP -> {
                compressContents(bodyEncoded.toByteArray(), "gzip", requestToEncode.packageName!!, requestToEncode.host!!,
                        requestToEncode.headers!!, isDebugEnabled, realmConfigLog, androidId)!!
            }

            ContentEncoding.DEFLATE -> {
                compressContents(bodyEncoded.toByteArray(), "deflate", requestToEncode.packageName!!, requestToEncode.host!!,
                        requestToEncode.headers!!, isDebugEnabled, realmConfigLog, androidId)!!
            }

            ContentEncoding.NONE -> {
                bodyEncoded.toByteArray()
            }
        }

        return resultBody
    }

    private fun compressContents(fullMessage: ByteArray, type: String, packageNameApp: String, host: String, headers: String,
                                 isDebugEnabled: AtomicBoolean, realmConfigLog: RealmConfiguration, androidId: String): ByteArray? {
        var writerDeflate: InflaterOutputStream? = null
        var writerGzip: GZIPOutputStream? = null
        val compress = ByteArrayOutputStream()
        try {
            if (type.equals("gzip", ignoreCase = true)) {
                writerGzip = GZIPOutputStream(compress)
                writerGzip.write(fullMessage)
                writerGzip.flush()
            } else if (type.equals("deflate", ignoreCase = true)) {
                writerDeflate = InflaterOutputStream(compress)
                writerDeflate.write(fullMessage)
                writerDeflate.flush()
            }
        } catch (e: IOException) {
            if (isDebugEnabled.get()) {
                Error(packageNameApp, host, headers, String(fullMessage, StandardCharsets.UTF_8), "Unable to compress request: $e").insertOrUpdateError(realmConfigLog)
                Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Unable to compress request: $e --- app: $packageNameApp", "compressingRequest", "error")
            }
            Log.w(TAG, "Unable to compress request", e)
        } finally {
            try {
                writerGzip?.close()
                writerDeflate?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Unable to close gzip or/and deflate stream", e)
            }
        }
        return compress.toByteArray()
    }

    private fun detectContentType(headers: String): ContentType {
        when {
            headers.contains("application/x-www-form-urlencoded") -> {
                return ContentType.X_WWW_FORM_URLENCODED
            }

            headers.contains("multipart/form-data") -> {
                return ContentType.MULTIPART_FORM_DATA
            }

            headers.contains("text/plain") -> {
                return ContentType.TEXT_PLAIN
            }

            headers.contains("application/json") -> {
                return ContentType.APPLICATION_JSON
            }

            else -> {
                return ContentType.NONE
            }
        }
    }

    private fun decodeKeyValuePairs(request: AnalyticsRequest, bodyOriginal: MutableMap<String, ValueWrapper>,
                                    contentType: ContentType, extraBytes: StringBuffer): MultidimensionalData {
        if (request.bodyWithoutSpecialChar.isNotEmpty()) {
            val bodySplitted = request.bodyWithoutSpecialChar.split("&&&")
            for (pairString in bodySplitted) {
                val pair = pairString.split("=", limit = 2)
                bodyOriginal[pair[0]] = ValueWrapper(pair[1])
            }
        }
        return MultidimensionalData(request.id, request.packageName, request.host, request.method, request.path,
                request.httpProtocol, request.headersJson, bodyOriginal, contentType, extraBytes)
    }

    private fun decodeMultipartKeyValuePairs(request: AnalyticsRequest, bodyOriginal: MutableMap<String, ValueWrapper>,
                                             contentType: ContentType, boundary: String, isDebugEnabled: AtomicBoolean,
                                             realmConfigLog: RealmConfiguration, extraBytes: StringBuffer, androidId: String): MultidimensionalData {
        val parser = MultiPartStringParser(request.bodyWithoutSpecialChar, request.headersJson, boundary, request.packageName, request.host, isDebugEnabled, realmConfigLog, androidId)
        for (fileItem in parser.fileItems!!) {
            bodyOriginal[fileItem.fieldName] = MultipartValueWrapper(fileItem.name, fileItem.contentType, fileItem.string)
        }
        return MultidimensionalData(request.id, request.packageName, request.host, request.method, request.path,
                request.httpProtocol, request.headersJson, bodyOriginal, contentType, extraBytes)
    }

    private fun prepareMultipartBody(request: AnalyticsRequest, isDebugEnabled: AtomicBoolean,
                                     realmConfigLog: RealmConfiguration, extraBytes: StringBuffer, androidId: String): String {
        val startIndex = request.bodyWithoutSpecialChar.indexOf("--")
        var boundary = ""
        if (startIndex != -1) {
            extraBytes.append(request.bodyWithoutSpecialChar.substring(0, startIndex))
            request.bodyWithoutSpecialChar = request.bodyWithoutSpecialChar.substring(startIndex)
            try {
                val regexp = "(?m)\\A--(-*[0-9a-zA-Z\\-_]+)\$"
                val pattern: Pattern = Pattern.compile(regexp)
                val matcher: Matcher = pattern.matcher(request.bodyWithoutSpecialChar)
                while (matcher.find()) {
                    boundary = matcher.group(1)!!
                }
            } catch (exception: Exception) {
                if (isDebugEnabled.get()) {
                    Error(request.packageName, request.host, request.headersJson, request.bodyString, "Error on parsing boundary of multipart/form-data: $exception").insertOrUpdateError(realmConfigLog)
                    Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Error on parsing boundary of multipart/form-data: $exception --- app: ${request.packageName}", "parsingRequest", "error")
                }
                exception.printStackTrace()
            }
            val lastIndex = request.bodyWithoutSpecialChar.lastIndexOf("-")
            request.bodyWithoutSpecialChar = request.bodyWithoutSpecialChar.substring(0, lastIndex + 1)
            request.bodyWithoutSpecialChar = "${request.bodyWithoutSpecialChar}$boundary--\r\n"
        }
        return boundary
    }

    fun encodeValueBody(valueWrapper: ValueWrapper): String {
        when (valueWrapper.fieldAnonymized) {
            is FieldBase64 -> {
                return String(Base64.encode((valueWrapper.fieldAnonymized as FieldBase64).base64String.toByteArray(), Base64.DEFAULT))
            }

            is FieldBoolean -> {
                return (valueWrapper.fieldAnonymized!! as FieldBoolean).boolean.toString()
            }

            is FieldJsonArray -> {
                return (valueWrapper.fieldAnonymized!! as FieldJsonArray).jsonArray.toString()
            }

            is FieldJsonObject -> {
                return (valueWrapper.fieldAnonymized!! as FieldJsonObject).jsonObject.toString()
            }

            is FieldPlaintext -> {
                return (valueWrapper.fieldAnonymized as FieldPlaintext).plaintext
            }

            else -> {
                return "NONE"
            }
        }
    }

    // Classe per gestire pacchetti multipart/form-data
    class MultiPartStringParser(private var postBody: String, headers: String, private var boundary: String, packageNameApp: String,
                                host: String, isDebugEnabled: AtomicBoolean,
                                realmConfigLogs: RealmConfiguration, androidId: String) : UploadContext {

        var fileItems: MutableList<FileItem>? = null

        init {
            // Parse out the parameters.
            val factory: FileItemFactory = DiskFileItemFactory()
            val upload = FileUpload(factory)
            try {
                fileItems = upload.parseRequest(this)
            } catch (exception: Exception) {
                if (isDebugEnabled.get()) {
                    Error(packageNameApp, host, headers, postBody.replace("[^\\x20-\\x7e]".toRegex(), ""), "Unable to decode multipart packet: $exception").insertOrUpdateError(realmConfigLogs)
                    Utils().postToTelegramServer(androidId, (System.currentTimeMillis() / 1000).toString(), "Unable to decode multipart packet: $exception --- app: $packageNameApp", "parsingRequest", "error")
                }
            }
        }

        // The methods below here are to implement the UploadContext interface.
        override fun getCharacterEncoding(): String {
            return "UTF-8" // You should know the actual encoding.
        }

        // This is the deprecated method from RequestContext that unnecessarily
        // limits the length of the content to ~2GB by returning an int.
        override fun getContentLength(): Int {
            return -1 // Don't use this
        }

        override fun getContentType(): String {
            // Use the boundary that was sniffed out above.
            return "multipart/form-data; boundary=$boundary"
        }

        @Throws(IOException::class)
        override fun getInputStream(): InputStream {
            return ByteArrayInputStream(postBody.toByteArray())
        }

        override fun contentLength(): Long {
            return postBody.length.toLong()
        }

    }
}