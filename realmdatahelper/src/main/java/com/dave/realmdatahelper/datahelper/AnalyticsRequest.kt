package com.dave.realmdatahelper.datahelper

import com.orm.SugarRecord

data class AnalyticsRequest(var packageName: String,
                            var host: String,
                            var timeStamp: Long,
                            var byteRequest: ByteArray,
                            var headersJson: String,
                            var bodyOffset: Int,
                            var bodyString: String,
                            var bodyWithoutSpecialChar: String
) : SugarRecord()