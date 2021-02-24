package com.dave.realmdatahelper.realmmodules

import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.dave.realmdatahelper.hidedroid.EventBuffer
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import io.realm.annotations.RealmModule


@RealmModule(classes = [AnalyticsRequest::class, ApplicationStatus::class, EventBuffer::class, PackageNamePrivacyLevel::class])
class DefaultModules