package it.unige.hidedroid.interceptor

import android.util.Log
import com.github.megatronking.netbare.http.HttpRequestChain
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.netty.buffer.ByteBuf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object UtilsHttpInterceptor {

    fun removeAllNonUtf8Char(original: String): String {
        val regex = Regex("[^\\n\\r\\t\\p{Print}]")
        return regex.replace(original, "")
    }

    fun fromMapToString(map: MutableMap<String?, List<String>?>?): String? {
        if (map == null) {
            return null
        }
        val gson = Gson()
        val type: Type = object : TypeToken<MutableMap<String?, List<String>?>>() {}.type
        return gson.toJson(map, type)
    }

    fun fromStringToMap(mapString: String?): MutableMap<String?, List<String>?>? {
        if (mapString == null) {
            return null
        }
        val gson = Gson()
        val type: Type = object : TypeToken<MutableMap<String?, List<String>?>?>() {}.type
        return gson.fromJson<MutableMap<String?, List<String>?>>(mapString, type)
    }

    fun parsingContentTypeApplicationXwwwFormUrlEncode(body: String): MutableMap<String, String> {
        var map = mutableMapOf<String, String>()
        body.split("&").forEach {
            map[it.split("=")[0]] = it.split("=")[1]
        }
        return map
    }

    fun parsingContentTypeMultipartFormData(body: String, boundary: String): MutableMap<String, List<String>> {
        /*
            --3i2ndDfv2rTHiSisAbouNdArYfORhtTPEefj3q2f
            Content-Disposition: form-data; name="installer_package"

            com.google.android.packageinstaller
         */
        var mapMultipart = mutableMapOf<String, List<String>>()
        val listPart = body.split(boundary) // split request basing on boundary value
        listPart.forEach { value ->
            // extract nameParameter
            val patternNameParameter = " name=\"\\w*\"".toRegex()
            val parameterComplex = patternNameParameter.find(value)
            // extract valueParameter
            val patternParameterValue = "\n.*\n--".toRegex()
            val parameterComplexValue = patternParameterValue.find(value)
            if (parameterComplex?.value != null && parameterComplexValue != null) {
                val nameParameter = parameterComplex.value.split("=")[1].replace("\"", "")
                val valueParameter = parameterComplexValue.value.replace("--", "").trim()
                println("$nameParameter=$valueParameter")
                mapMultipart[nameParameter] = listOf(valueParameter)
            }

        }
        return mapMultipart
    }

    fun extractContentTypeFromHeaderString(header: String): String? {
        val mapHeader = fromStringToMap(header)
        var contentType: String? = null
        if (mapHeader != null && mapHeader.containsKey("Content-Type")) {
            contentType = mapHeader["Content-Type"]!![0].split(";")[0]
        }
        return contentType
    }

    fun extractBoundary(header: String): String? {
        val mapHeader = fromStringToMap(header)
        var boundary: String? = null
        if (mapHeader != null && mapHeader.containsKey("Content-Type")) {
            boundary = mapHeader["Content-Type"]!![0].split(";")[1].split("=")[1]
        }
        return boundary
    }

    fun isAnalyticsRequest(buffer: ByteArray): Boolean {
        val bufferString = String(buffer, StandardCharsets.UTF_8)
        return bufferString.contains("event", true)
                .or(bufferString.contains("events", true))
    }

    fun isNotLoginRequest(request: HttpRequestChain, buffer: ByteArray): Boolean {
        return request.request().host() != "graph.facebook.com" && !isAnalyticsRequest(buffer)
    }

    // Funzione utile ad eliminare i byte a 0 in eccesso
    fun trim(bytes: ByteArray, contentLength: Int): ByteArray? {
        var i = bytes.size - 1
        var limit = 0
        if (contentLength != 0) {
            limit = contentLength + 1
        }
        while (i >= limit && bytes[i].toInt() == 0) {
            --i
        }
        return bytes.copyOf(i + 1)
    }

    // Funzione per decomprimere pacchetti GZIP e DEFLATE
    fun decompressContents(fullMessage: ByteArray, type: String, packageNameApp: String, host: String, headers: String, isDebugEnabled: AtomicBoolean): ByteArray? {
        var fullMessage = fullMessage
        var reader: InflaterInputStream? = null
        val uncompressed: ByteArrayOutputStream
        try {
            if (type.equals("gzip", ignoreCase = true)) {
                reader = GZIPInputStream(ByteArrayInputStream(fullMessage))
            } else if (type.equals("deflate", ignoreCase = true)) {
                reader = InflaterInputStream(ByteArrayInputStream(fullMessage))
            }
            uncompressed = ByteArrayOutputStream(fullMessage.size)
            val decompressBuffer = ByteArray(HttpInterceptor.DECOMPRESS_BUFFER_SIZE)
            var bytesRead: Int
            while (reader!!.read(decompressBuffer).also { bytesRead = it } > -1) {
                uncompressed.write(decompressBuffer, 0, bytesRead)
            }
            fullMessage = uncompressed.toByteArray()
        } catch (e: IOException) {
            if (isDebugEnabled.get()) {
                Log.e("errorDecompressingContent", "Unable to decompress request: $e")
            }
            Log.w(HttpInterceptor.TAG, "Unable to decompress request", e)
        } finally {
            try {
                reader?.close()
            } catch (e: IOException) {
                Log.w(HttpInterceptor.TAG, "Unable to close gzip stream", e)
            }
        }
        return fullMessage
    }

    // TODO: capire perchè non funziona correttamente
    fun extractReadableBytes(content: ByteBuf): ByteArray? {
        val binaryContent = ByteArray(content.readableBytes())
        content.markReaderIndex()
        content.readBytes(binaryContent)
        content.resetReaderIndex()
        return binaryContent
    }
}