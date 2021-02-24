package com.dave.anonymization_data.parsering

import android.util.Log
import com.dave.anonymization_data.wrappers.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception
import java.util.*
import java.util.regex.Pattern

enum class ValueEncoding {
    JSON_ARRAY, JSON_OBJECT, BASE64, PLAINTEXT, BOOLEAN, NONE
}

/**
 * Classe che wrappa il singolo value contenuto nel body della richiesta, specificandone il tipo
 * di formato per successivo invio e codifica al servizio di Analytics
 */
open class ValueWrapper {
    var valueEncoding: ValueEncoding = ValueEncoding.NONE
    lateinit var fieldParsed: FieldType
    var fieldAnonymized: FieldType? = null
    var value: String? = null

    private val TAG = ValueWrapper::class.java.name

    private fun castJsonArray(value: String): Boolean {
        try {
            fieldParsed = FieldJsonArray(JSONArray(value))
        } catch (e: Exception) {
            Log.e(TAG, "Error on parsing the json array: $e")
            return false
        }
        return true
    }

    private fun castJsonObject(value: String): Boolean {
        try {
            fieldParsed = FieldJsonObject(JSONObject(value))
        } catch (e: Exception) {
            Log.e(TAG, "Error on parsing json object: $e")
            return false
        }
        return true
    }

    private fun castBase64(value: String): Boolean {
        if (value.matches("^([A-Za-z0-9+]{4})*([A-Za-z0-9+]{3}=|[A-Za-z0-9+]{2}==)?\$".toRegex())) {
            val base64Decoded = String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
            if (base64Decoded.matches("[\\p{ASCII}\r\n]*".toRegex())) {
                fieldParsed = FieldBase64(base64Decoded)
                return true
            }
        }
        return false
    }

    private fun castBoolean(value: String): Boolean {
        val valueToLowerCase = value.toLowerCase(Locale.ROOT)
        if (valueToLowerCase == "true") {
            fieldParsed = FieldBoolean(true)
            return true
        } else if (valueToLowerCase == "false") {
            fieldParsed = FieldBoolean(false)
            return true
        }
        return false
    }

    constructor(value: String) {
        this.value = value

        valueEncoding = when {
            castJsonObject(value) -> {
                ValueEncoding.JSON_OBJECT
            }

            castJsonArray(value) -> {
                ValueEncoding.JSON_ARRAY
            }

            castBoolean(value) -> {
                ValueEncoding.BOOLEAN
            }

            castBase64(value) -> {
                ValueEncoding.BASE64
            }

            else -> {
                fieldParsed = FieldPlaintext(value)
                ValueEncoding.PLAINTEXT
            }
        }
    }

    fun anonymizedString(): String {
        if (fieldAnonymized != null) {
            return fieldAnonymized!!.toString()
        }
        return ""
    }

    override fun toString(): String {
        var stringToReturn = "$value --- "
        stringToReturn += when (valueEncoding) {
            ValueEncoding.JSON_ARRAY -> {
                "JSON_ARRAY"
            }

            ValueEncoding.JSON_OBJECT -> {
                "JSON_OBJECT"
            }

            ValueEncoding.BASE64 -> {
                "BASE64"
            }

            ValueEncoding.PLAINTEXT -> {
                "PLAINTEXT"
            }

            ValueEncoding.BOOLEAN -> {
                "BOOLEAN"
            }

            else -> {
                "NONE"
            }
        }
        return stringToReturn
    }
}