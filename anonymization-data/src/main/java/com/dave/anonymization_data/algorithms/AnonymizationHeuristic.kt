package com.dave.anonymization_data.algorithms

import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.anonymization_data.parsering.ValueWrapper
import com.dave.anonymization_data.wrappers.*
import com.dave.realmdatahelper.dgh.DGH
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.realm.RealmConfiguration
import io.realm.RealmList
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception
import java.lang.Math.round
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class AnonymizationHeuristic {

    private val host: String
    private val eventBody: MutableMap<String, ValueWrapper>

    private val fieldToAnonymize: FieldType
    private val blackListFields: Set<String>
    private val dgh: JSONObject
    private val dghKeysSet: Set<String>
    private val selectedPrivacyLevel: AtomicInteger
    private val numberOfPrivacyLevels: Int
    private val realmConfigDGH: RealmConfiguration
    private val randomAnonymizedValueSelector: Random

    private var eventNameChanged: String
    private var mapFieldsToModify: MutableMap<String, FieldType>
    var mapFieldsToAdd: MutableMap<String, ValueWrapper>

    constructor(host: String, eventBody: MutableMap<String, ValueWrapper>, fieldToAnonymize: FieldType, blackListFields: Set<String>, dghBox: DataAnonymizer.DghBox,
                selectedPrivacyLevel: AtomicInteger, numberOfPrivacyLevels: Int, eventNameChanged: String, mapFieldsToModify: MutableMap<String, FieldType>,
                realmConfigDGH: RealmConfiguration) {
        this.host = host
        this.eventBody = eventBody
        this.fieldToAnonymize = fieldToAnonymize
        this.blackListFields = blackListFields

        if (host.contains(".facebook.")) {
            this.dgh = dghBox.dghFacebook
            this.dghKeysSet = dghBox.dghFacebookKeysSet
        } else {
            this.dgh = dghBox.dgh
            this.dghKeysSet = dghBox.dghKeysSet
        }
        this.selectedPrivacyLevel = selectedPrivacyLevel
        this.numberOfPrivacyLevels = numberOfPrivacyLevels
        this.realmConfigDGH = realmConfigDGH

        this.randomAnonymizedValueSelector = Random(System.currentTimeMillis())
        this.eventNameChanged = eventNameChanged

        this.mapFieldsToModify = mapFieldsToModify
        this.mapFieldsToAdd = mutableMapOf()
    }

    fun anonymize(key: String): FieldType? {
        when (fieldToAnonymize) {
            is FieldBase64 -> {
                val base64StringAnonymized = if (!host.contains(".facebook.")) {
                    anonymizeAny(fieldToAnonymize.base64String, realmConfigDGH, key) as String?
                } else {
                    anonymizeAnyFacebook(fieldToAnonymize.base64String, realmConfigDGH, key) as String?
                }
                if (base64StringAnonymized != null) {
                    return FieldBase64(base64StringAnonymized)
                }
            }
            is FieldBoolean -> {
                val booleanAnonymized = if (!host.contains(".facebook.")) {
                    anonymizeAny(fieldToAnonymize.boolean, realmConfigDGH, key) as Boolean?
                } else {
                    anonymizeAnyFacebook(fieldToAnonymize.boolean, realmConfigDGH, key) as Boolean?
                }
                if (booleanAnonymized != null) {
                    return FieldBoolean(booleanAnonymized)
                }
            }
            is FieldJsonArray -> {
                val jsonArrayAnonymized = if (!host.contains(".facebook.")) {
                    anonymizeAny(fieldToAnonymize.jsonArray, realmConfigDGH, key) as JSONArray?
                } else {
                    anonymizeAnyFacebook(fieldToAnonymize.jsonArray, realmConfigDGH, key) as JSONArray?
                }
                if (jsonArrayAnonymized != null) {
                    return FieldJsonArray(jsonArrayAnonymized)
                }
            }
            is FieldJsonObject -> {
                val jsonObjectAnonymized = if (!host.contains(".facebook.")) {
                    anonymizeAny(fieldToAnonymize.jsonObject, realmConfigDGH, key) as JSONObject?
                } else {
                    anonymizeAnyFacebook(fieldToAnonymize.jsonObject, realmConfigDGH, key) as JSONObject?
                }
                if (jsonObjectAnonymized != null) {
                    return FieldJsonObject(jsonObjectAnonymized)
                }
            }
            is FieldPlaintext -> {
                val plaintextAnonymized = if (!host.contains(".facebook.")) {
                    anonymizeAny(fieldToAnonymize.plaintext, realmConfigDGH, key) as String?
                } else {
                    anonymizeAnyFacebook(fieldToAnonymize.plaintext, realmConfigDGH, key) as String?
                }
                if (plaintextAnonymized != null) {
                    return FieldPlaintext(plaintextAnonymized)
                }
            }
            else -> {
                return FieldNone()
            }
        }
        return null
    }

    private fun anonymizeAny(anyToAnonymize: Any, realmConfigDGH: RealmConfiguration, key: String): Any? {
        when (anyToAnonymize) {
            is String -> {
                val dghTable = DGH(key, RealmList())
                dghTable.insertOrUpdate(realmConfigDGH, anyToAnonymize)
                if (key.toLowerCase(Locale.ROOT) in dghKeysSet) {
                    val listPossibleValues = dgh.getJSONArray(key.toLowerCase(Locale.ROOT)).get(selectedPrivacyLevel.get() - 1)
                    if (listPossibleValues is JSONArray) {
                        return listPossibleValues.getString(randomAnonymizedValueSelector.nextInt(listPossibleValues.length()))
                    }
                    return listPossibleValues as String
                }

                for (entry in blackListFields) {
                    if (key.toLowerCase(Locale.ROOT).contains(entry)) {
                        return anyToAnonymize
                    }
                }

                return anonymizeString(anyToAnonymize)
            }
            is Boolean -> {
                return !anyToAnonymize
            }
            is JSONArray -> {
                val jsonArrayAnonymized = JSONArray(anyToAnonymize.toString())
                for (i in 0 until jsonArrayAnonymized.length()) {
                    val anonymizedEntry: Any? = if (anyToAnonymize[i] is String) {
                        try {
                            val jsonArrayCasted = JSONArray(anyToAnonymize[i].toString())
                            anonymizeAny(jsonArrayCasted, realmConfigDGH, i.toString())
                        } catch (e: Exception) {
                            try {
                                val jsonObjectCasted = JSONObject(anyToAnonymize[i].toString())
                                anonymizeAny(jsonObjectCasted, realmConfigDGH, i.toString())
                            } catch (e: Exception) {
                                anonymizeAny(anyToAnonymize[i], realmConfigDGH, i.toString())
                            }
                        }
                    } else {
                        anonymizeAny(anyToAnonymize[i], realmConfigDGH, i.toString())
                    }
                    jsonArrayAnonymized.put(i, anonymizedEntry)
                }
                val dgh = DGH(key, RealmList())
                dgh.insertOrUpdate(realmConfigDGH, JSONArray(anyToAnonymize.toString()).toString())
                return jsonArrayAnonymized
            }
            is JSONObject -> {
                val jsonObjectAnonymized = JSONObject(anyToAnonymize.toString())
                for (keyJsonObject in jsonObjectAnonymized.keys()) {
                    val anonymizedEntry = if (anyToAnonymize.get(keyJsonObject) is String) {
                        try {
                            val jsonArrayCasted = JSONArray(anyToAnonymize.get(keyJsonObject).toString())
                            anonymizeAny(jsonArrayCasted, realmConfigDGH, keyJsonObject)
                        } catch (e: Exception) {
                            try {
                                val jsonObjectCasted = JSONObject(anyToAnonymize.get(keyJsonObject).toString())
                                anonymizeAny(jsonObjectCasted, realmConfigDGH, keyJsonObject)
                            } catch (e: Exception) {
                                anonymizeAny(anyToAnonymize.get(keyJsonObject), realmConfigDGH, keyJsonObject)
                            }
                        }
                    } else {
                        anonymizeAny(anyToAnonymize.get(keyJsonObject), realmConfigDGH, keyJsonObject)
                    }
                    jsonObjectAnonymized.put(keyJsonObject, anonymizedEntry)
                }
                return jsonObjectAnonymized
            }
            is Number -> {
                val dghTable = DGH(key, RealmList())
                dghTable.insertOrUpdate(realmConfigDGH, anyToAnonymize.toString())

                if (key.toLowerCase(Locale.ROOT) in dghKeysSet) {
                    var stringNumber = ""
                    val listPossibleValues = dgh.getJSONArray(key.toLowerCase(Locale.ROOT)).get(selectedPrivacyLevel.get() - 1)
                    stringNumber = if (listPossibleValues is JSONArray) {
                        listPossibleValues.getString(randomAnonymizedValueSelector.nextInt(listPossibleValues.length()))
                    } else {
                        listPossibleValues as String
                    }
                    return when {
                        stringNumber.matches("[-+]?[0-9]+\\.[0-9]+".toRegex()) -> {
                            stringNumber.toDouble()
                        }

                        stringNumber.matches("[-+]?[0-9]+".toRegex()) -> {
                            stringNumber.toLong()
                        }

                        else -> {
                            stringNumber
                        }
                    }
                }

                for (entry in blackListFields) {
                    if (key.toLowerCase(Locale.ROOT).contains(entry)) {
                        return anyToAnonymize
                    }
                }

                return anonymizeNumber(anyToAnonymize)
            }
            else -> {
                return ""
            }
        }
    }


    private fun anonymizeAnyFacebook(anyToAnonymize: Any, realmConfigDGH: RealmConfiguration, key: String): Any? {
        when (anyToAnonymize) {
            is String -> {
                val dghTable = DGH(key, RealmList())
                dghTable.insertOrUpdate(realmConfigDGH, anyToAnonymize)

                if (key in mapFieldsToModify.keys) {
                    return when(val field = mapFieldsToModify.remove(key)!!) {
                        is FieldPlaintext -> {
                            field.plaintext
                        }
                        is FieldBoolean -> {
                            field.boolean
                        }
                        is FieldJsonObject -> {
                            field.jsonObject
                        }
                        is FieldJsonArray -> {
                            field.jsonArray
                        }
                        is FieldBase64 -> {
                            field.base64String
                        }
                        is FieldNone -> {
                            null
                        }
                        else -> {
                            null
                        }
                    }
                }

                if (key in dghKeysSet) {
                    val listPossibleValues = dgh.getJSONArray(key).get(selectedPrivacyLevel.get() - 1)
                    if (listPossibleValues is JSONArray) {
                        val itemSelected = listPossibleValues.getString(randomAnonymizedValueSelector.nextInt(listPossibleValues.length()))
                        if (key == "_eventName") {
                            eventNameChanged = itemSelected
                        } else if (key == "event") {
                            if (itemSelected == "CUSTOM_APP_EVENTS") {
                                val numberCustomEvents = randomAnonymizedValueSelector.nextInt(5) + 1
                                val listPossibleEventName = dgh.getJSONArray("_eventName").getJSONArray(selectedPrivacyLevel.get() - 1)
                                val listCustomEvents = mutableListOf<CustomEventsFacebookTemplate>()
                                for (i in 0 until numberCustomEvents) {
                                    val customEvent = CustomEventsFacebookTemplate()
                                    customEvent._eventName = listPossibleEventName.getString(randomAnonymizedValueSelector.nextInt(listPossibleEventName.length()))
                                    customEvent._eventName_md5 = String(Hex.encodeHex(DigestUtils.md5(customEvent._eventName)))
                                    customEvent._logTime = anonymizeNumber(System.currentTimeMillis() / 1000).toInt()
                                    customEvent._ui = "unknown"
                                    customEvent.current = randomAnonymizedValueSelector.nextInt(11).toString()
                                    customEvent.previous = randomAnonymizedValueSelector.nextInt(11).toString()
                                    customEvent.initial = randomAnonymizedValueSelector.nextInt(11).toString()
                                    customEvent.usage = randomAnonymizedValueSelector.nextInt(11).toString()
                                    customEvent._inBackground = randomAnonymizedValueSelector.nextInt(11).toString()
                                    customEvent._implicitlyLogged = randomAnonymizedValueSelector.nextInt(11).toString()

                                    listCustomEvents.add(customEvent)
                                }
                                mapFieldsToAdd["custom_events"] = ValueWrapper(Gson().toJson(listCustomEvents))
                                mapFieldsToAdd["custom_events"]!!.fieldAnonymized = mapFieldsToAdd["custom_events"]!!.fieldParsed
                            }
                        }
                        return itemSelected
                    }
                    return listPossibleValues as String
                }

                if (key == "_eventName_md5" && eventNameChanged != "") {
                    val hashText = String(Hex.encodeHex(DigestUtils.md5(eventNameChanged)))
                    eventNameChanged = ""
                    return hashText
                }

                for (entry in blackListFields) {
                    if (key.toLowerCase(Locale.ROOT).contains(entry)) {
                        return anyToAnonymize
                    }
                }

                return anonymizeString(anyToAnonymize)
            }
            is Boolean -> {
                return !anyToAnonymize
            }
            is JSONArray -> {
                var removed = false
                if (key == "custom_events" && "event" in eventBody.keys) {
                    val listPossibleEvents = dgh.getJSONArray("event").getJSONArray(selectedPrivacyLevel.get() - 1)
                    val eventSelected = listPossibleEvents.getString(randomAnonymizedValueSelector.nextInt(listPossibleEvents.length()))
                    if (eventSelected != "CUSTOM_APP_EVENTS") {
                        removed = true
                    }
                    mapFieldsToModify["event"] = FieldPlaintext(eventSelected)
                }
                var jsonArrayAnonymized: JSONArray? = null
                if (!removed) {
                    jsonArrayAnonymized = JSONArray(anyToAnonymize.toString())
                    for (i in 0 until jsonArrayAnonymized.length()) {
                        val anonymizedEntry = if (anyToAnonymize[i] is String) {
                            try {
                                val jsonArrayCasted = JSONArray(anyToAnonymize[i].toString())
                                anonymizeAnyFacebook(jsonArrayCasted, realmConfigDGH, i.toString())
                            } catch (e: Exception) {
                                try {
                                    val jsonObjectCasted = JSONObject(anyToAnonymize[i].toString())
                                    anonymizeAnyFacebook(jsonObjectCasted, realmConfigDGH, i.toString())
                                } catch (e: Exception) {
                                    anonymizeAnyFacebook(anyToAnonymize[i], realmConfigDGH, i.toString())
                                }
                            }
                        } else {
                            anonymizeAnyFacebook(anyToAnonymize[i], realmConfigDGH, i.toString())
                        }
                        jsonArrayAnonymized.put(i, anonymizedEntry)
                    }
                    val dgh = DGH(key, RealmList())
                    dgh.insertOrUpdate(realmConfigDGH, JSONArray(anyToAnonymize.toString()).toString())
                }
                return jsonArrayAnonymized
            }
            is JSONObject -> {
                val jsonObjectAnonymized = JSONObject(anyToAnonymize.toString())
                for (keyJsonObject in jsonObjectAnonymized.keys()) {
                    val anonymizedEntry = if (anyToAnonymize.get(keyJsonObject) is String) {
                        try {
                            val jsonArrayCasted = JSONArray(anyToAnonymize.get(keyJsonObject).toString())
                            anonymizeAnyFacebook(jsonArrayCasted, realmConfigDGH, keyJsonObject)
                        } catch (e: Exception) {
                            try {
                                val jsonObjectCasted = JSONObject(anyToAnonymize.get(keyJsonObject).toString())
                                anonymizeAnyFacebook(jsonObjectCasted, realmConfigDGH, keyJsonObject)
                            } catch (e: Exception) {
                                anonymizeAnyFacebook(anyToAnonymize.get(keyJsonObject), realmConfigDGH, keyJsonObject)
                            }
                        }
                    } else {
                        anonymizeAnyFacebook(anyToAnonymize.get(keyJsonObject), realmConfigDGH, keyJsonObject)
                    }
                    jsonObjectAnonymized.put(keyJsonObject, anonymizedEntry)
                }
                return jsonObjectAnonymized
            }
            is Number -> {
                val dghTable = DGH(key, RealmList())
                dghTable.insertOrUpdate(realmConfigDGH, anyToAnonymize.toString())

                if (key.toLowerCase(Locale.ROOT) in dghKeysSet) {
                    var stringNumber = ""
                    val listPossibleValues = dgh.getJSONArray(key.toLowerCase(Locale.ROOT)).get(selectedPrivacyLevel.get() - 1)
                    stringNumber = if (listPossibleValues is JSONArray) {
                        listPossibleValues.getString(randomAnonymizedValueSelector.nextInt(listPossibleValues.length()))
                    } else {
                        listPossibleValues as String
                    }
                    return when {
                        stringNumber.matches("[-+]?[0-9]+\\.[0-9]+".toRegex()) -> {
                            stringNumber.toDouble()
                        }

                        stringNumber.matches("[-+]?[0-9]+".toRegex()) -> {
                            stringNumber.toLong()
                        }

                        else -> {
                            stringNumber
                        }
                    }
                }

                for (entry in blackListFields) {
                    if (key.toLowerCase(Locale.ROOT).contains(entry)) {
                        return anyToAnonymize
                    }
                }

                return anonymizeNumber(anyToAnonymize)
            }
            else -> {
                return ""
            }
        }
    }


    private fun anonymizeString(stringToAnonymize: String): String {
        val stringBuffer = StringBuffer(stringToAnonymize)
        when {
            stringToAnonymize.matches("^[tT]rue|[fF]alse$".toRegex()) -> {
                var anyToBoolean = stringToAnonymize.toBoolean()
                anyToBoolean = !anyToBoolean
                return anyToBoolean.toString()
            }
            stringToAnonymize.matches("^(-|\\+)?[0-9]+(\\.[0-9]+)?$".toRegex()) -> {
                val pattern = Pattern.compile("[0-9]+")
                val m = pattern.matcher(stringToAnonymize)
                var numberDigits = 0
                while (m.find()) {
                    numberDigits += m.group(0)!!.length
                }
                var mostSignificantDigit = (numberDigits * selectedPrivacyLevel.get()) / (numberOfPrivacyLevels - 1)
                if (mostSignificantDigit == numberDigits) {
                    mostSignificantDigit--
                }
                var i = stringToAnonymize.length - 1
                var numberOfZerosInserted = 0
                while (i >= 0 && numberOfZerosInserted < mostSignificantDigit) {
                    if (stringToAnonymize[i].isDigit()) {
                        stringBuffer.setCharAt(i, '0')
                        numberOfZerosInserted++
                    }
                    i--
                }
            }
            stringToAnonymize.isNotEmpty() -> {
                val numberOfStar = (stringToAnonymize.length * selectedPrivacyLevel.get()) / (numberOfPrivacyLevels - 1)
                for (i in (stringToAnonymize.length - 1) downTo (stringToAnonymize.length - numberOfStar)) {
                    stringBuffer.setCharAt(i, '*')
                }
            }
        }
        return stringBuffer.toString()
    }

    private fun anonymizeNumber(numberToAnonymize: Number): Number {
        var stringNumber = numberToAnonymize.toString()
        var numberDigits = 0
        for (i in stringNumber) {
            if (i.isDigit()) {
                numberDigits++
            }
        }
        var mostSignificantDigit = (numberDigits * selectedPrivacyLevel.get()) / (numberOfPrivacyLevels - 1)
        if (mostSignificantDigit == numberDigits) {
            mostSignificantDigit--
        }
        val stringBuffer = StringBuffer(stringNumber)
        var i = stringNumber.length - 1
        var numberOfZerosInserted = 0
        while (i >= 0 && numberOfZerosInserted < mostSignificantDigit) {
            if (stringNumber[i].isDigit()) {
                stringBuffer.setCharAt(i, '0')
                numberOfZerosInserted++
            }
            i--
        }
        stringNumber = stringBuffer.toString()
        return when {
            stringNumber.matches("[-+]?[0-9]+\\.[0-9]+".toRegex()) -> {
                stringNumber.toDouble()
            }

            stringNumber.matches("[-+]?[0-9]+".toRegex()) -> {
                stringNumber.toLong()
            }

            else -> {
                numberToAnonymize
            }
        }
    }

    data class CustomEventsFacebookTemplate (
            @SerializedName("_eventName") var _eventName: String = "EVENT_NAME",
            @SerializedName("_eventName_md5") var _eventName_md5: String = "EVENT_NAME_MD5",
            @SerializedName("_logTime") var _logTime: Int = -1,
            @SerializedName("_ui") var _ui: String = "unknown",
            @SerializedName("current") var current: String = "7",
            @SerializedName("usage") var usage: String =  "0",
            @SerializedName("previous") var previous: String = "0",
            @SerializedName("initial") var initial: String = "7",
            @SerializedName("_inBackground") var _inBackground: String = "1",
            @SerializedName("_implicitlyLogged") var _implicitlyLogged: String = "1"
    )
}