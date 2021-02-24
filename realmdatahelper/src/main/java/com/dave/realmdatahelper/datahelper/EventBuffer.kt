package com.dave.realmdatahelper.datahelper

import com.orm.SugarRecord


data class EventBuffer(
        var packageName:String = "",
        var host:String = "",
        var event:AnalyticsRequest? = null
) : SugarRecord()
