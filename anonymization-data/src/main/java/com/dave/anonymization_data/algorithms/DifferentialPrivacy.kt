package com.dave.anonymization_data.algorithms

import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import com.dave.anonymization_data.data.MultidimensionalData
import com.dave.anonymization_data.parsering.BodyParser
import com.dave.anonymization_data.parsering.ValueWrapper
import com.dave.anonymization_data.wrappers.FieldType
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.hidedroid.EventBuffer
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DifferentialPrivacy {
    var requestToAnonymize: MultidimensionalData
    private val blackListFields: Set<String>
    private val dghBox: DataAnonymizer.DghBox
    private val threshold: Float
    private val selectedPrivacyLevel: AtomicInteger
    private val numberOfPrivacyLevels: Int
    private val numberOfActions: Int
    private val minNumberOfRequestForDP: Int
    private val randomGenerator: Random

    companion object {
        val NEW_EVENT_GENERALIZED = "newEventGeneralized"
        val EVENT_GENERALIZED = "eventGeneralized"
    }

    constructor(requestToAnonymize: MultidimensionalData, blackListFields: Set<String>, dghBox: DataAnonymizer.DghBox, selectedPrivacyLevel: AtomicInteger, numberOfPrivacyLevels: Int, numberOfActions: Int, minNumberOfRequestForDP: Int) {
        this.requestToAnonymize = requestToAnonymize
        this.blackListFields = blackListFields
        this.dghBox = dghBox
        this.selectedPrivacyLevel = selectedPrivacyLevel
        this.numberOfPrivacyLevels = numberOfPrivacyLevels
        this.numberOfActions = numberOfActions
        this.minNumberOfRequestForDP = minNumberOfRequestForDP
        this.threshold = 1 - (selectedPrivacyLevel.get() / (numberOfActions + 1).toFloat())
        randomGenerator = Random(System.currentTimeMillis())
    }

    fun anonymize(isDebugEnabled: AtomicBoolean, realmConfigLog: RealmConfiguration, realmConfigDGH: RealmConfiguration, androidId: String): Map<String, MultidimensionalData> {
        val prInjection =  randomGenerator.nextFloat()
        val prRemove = randomGenerator.nextFloat()
        val prReplace = randomGenerator.nextFloat()

        val eventsAnonymized = mutableMapOf<String, MultidimensionalData>()
        var requestsWithSameHostAndPackageName: List<AnalyticsRequest>? = null

        // ---INJECTION---
        if (prInjection > threshold) {
            val realm = Realm.getDefaultInstance()
            realm.use { realm ->
                requestsWithSameHostAndPackageName = realm.copyFromRealm(realm.where<EventBuffer>().equalTo("packageName", requestToAnonymize.packageName)
                        .and().equalTo("host", requestToAnonymize.host).findFirst()!!.event.toList())
            }
            if (requestsWithSameHostAndPackageName != null && requestsWithSameHostAndPackageName!!.size >= this.minNumberOfRequestForDP) {
                var newEventAnalytics = requestsWithSameHostAndPackageName!![randomGenerator.nextInt(requestsWithSameHostAndPackageName!!.size)]
                while (newEventAnalytics.id == requestToAnonymize.id) {
                    newEventAnalytics = requestsWithSameHostAndPackageName!![randomGenerator.nextInt(requestsWithSameHostAndPackageName!!.size)]
                }
                val newEvent = BodyParser().parse(newEventAnalytics, isDebugEnabled, realmConfigLog, androidId)
                val newBodyIterator = newEvent.body!!.iterator()
                val fieldsToAdd = mutableMapOf<String, ValueWrapper>()
                val fieldsToModify = mutableMapOf<String, FieldType>()
                var eventNameChanged = ""
                while (newBodyIterator.hasNext()) {
                    val entry = newBodyIterator.next()
                    val anonymizationHeuristic = AnonymizationHeuristic(requestToAnonymize.host!!, newEvent.body!!, entry.value.fieldParsed, blackListFields, dghBox, selectedPrivacyLevel, numberOfPrivacyLevels, eventNameChanged, fieldsToModify, realmConfigDGH)
                    entry.value.fieldAnonymized = anonymizationHeuristic.anonymize(entry.key)
                    if (entry.value.fieldAnonymized == null) {
                        newBodyIterator.remove()
                    }
                    fieldsToAdd.putAll(anonymizationHeuristic.mapFieldsToAdd)
                }
                newEvent.body!!.putAll(fieldsToAdd)
                eventsAnonymized[NEW_EVENT_GENERALIZED] = newEvent

                val bodyIterator = requestToAnonymize.body!!.iterator()
                fieldsToAdd.clear()
                fieldsToModify.clear()
                eventNameChanged = ""
                while (bodyIterator.hasNext()) {
                    val entry = bodyIterator.next()
                    val anonymizationHeuristic = AnonymizationHeuristic(requestToAnonymize.host!!, requestToAnonymize.body!!, entry.value.fieldParsed, blackListFields, dghBox, selectedPrivacyLevel, numberOfPrivacyLevels, eventNameChanged, fieldsToModify, realmConfigDGH)
                    entry.value.fieldAnonymized = anonymizationHeuristic.anonymize(entry.key)
                    if (entry.value.fieldAnonymized == null) {
                        bodyIterator.remove()
                    }
                    fieldsToAdd.putAll(anonymizationHeuristic.mapFieldsToAdd)
                }
                requestToAnonymize.body!!.putAll(fieldsToAdd)
                eventsAnonymized[EVENT_GENERALIZED] = requestToAnonymize
            }
        }

        // ---REPLACE---
        if (prReplace > threshold) {
            if (requestsWithSameHostAndPackageName == null) {
                val realm = Realm.getDefaultInstance()
                realm.use { realm ->
                    requestsWithSameHostAndPackageName = realm.copyFromRealm(realm.where<EventBuffer>().equalTo("packageName", requestToAnonymize.packageName)
                            .and().equalTo("host", requestToAnonymize.host).findFirst()!!.event.toList())
                }
            }
            if (requestsWithSameHostAndPackageName != null && requestsWithSameHostAndPackageName!!.size >= minNumberOfRequestForDP) {
                var replacedEventAnalytics = requestsWithSameHostAndPackageName!![randomGenerator.nextInt(requestsWithSameHostAndPackageName!!.size)]
                while (replacedEventAnalytics.id == requestToAnonymize.id) {
                    replacedEventAnalytics = requestsWithSameHostAndPackageName!![randomGenerator.nextInt(requestsWithSameHostAndPackageName!!.size)]
                }
                val replacedEvent = BodyParser().parse(replacedEventAnalytics, isDebugEnabled, realmConfigLog, androidId)
                val replacedIterator = replacedEvent.body!!.iterator()
                val fieldsToAdd = mutableMapOf<String, ValueWrapper>()
                val fieldsToModify = mutableMapOf<String, FieldType>()
                val eventNameChanged = ""
                while (replacedIterator.hasNext()) {
                    val entry = replacedIterator.next()
                    val anonymizationHeuristic = AnonymizationHeuristic(requestToAnonymize.host!!, replacedEvent.body!!, entry.value.fieldParsed, blackListFields, dghBox, selectedPrivacyLevel, numberOfPrivacyLevels, eventNameChanged, fieldsToModify, realmConfigDGH)
                    entry.value.fieldAnonymized = anonymizationHeuristic.anonymize(entry.key)
                    if (entry.value.fieldAnonymized == null) {
                        replacedIterator.remove()
                    }
                    fieldsToAdd.putAll(anonymizationHeuristic.mapFieldsToAdd)
                }
                replacedEvent.body!!.putAll(fieldsToAdd)
                eventsAnonymized[EVENT_GENERALIZED] = replacedEvent
                return eventsAnonymized
            }
        }

        // ---REMOVE---
        if (prRemove > threshold) {
            if (EVENT_GENERALIZED in eventsAnonymized) {
                eventsAnonymized.remove(EVENT_GENERALIZED)
            }
            return eventsAnonymized
        }

        // ---GENERALIZE---
        if (EVENT_GENERALIZED !in eventsAnonymized) {
            val bodyIterator = requestToAnonymize.body!!.iterator()
            val fieldsToAdd = mutableMapOf<String, ValueWrapper>()
            val fieldsToModify = mutableMapOf<String, FieldType>()
            val eventNameChanged = ""
            while (bodyIterator.hasNext()) {
                val entry = bodyIterator.next()
                val anonymizationHeuristic = AnonymizationHeuristic(requestToAnonymize.host!!, requestToAnonymize.body!!, entry.value.fieldParsed, blackListFields, dghBox, selectedPrivacyLevel, numberOfPrivacyLevels, eventNameChanged, fieldsToModify, realmConfigDGH)
                entry.value.fieldAnonymized = anonymizationHeuristic.anonymize(entry.key)
                if (entry.value.fieldAnonymized == null) {
                    bodyIterator.remove()
                }
                fieldsToAdd.putAll(anonymizationHeuristic.mapFieldsToAdd)
            }
            requestToAnonymize.body!!.putAll(fieldsToAdd)
            eventsAnonymized[EVENT_GENERALIZED] = requestToAnonymize
        }
        return eventsAnonymized
    }
}