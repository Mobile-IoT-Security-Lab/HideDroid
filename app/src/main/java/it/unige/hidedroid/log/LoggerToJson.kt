package it.unige.hidedroid.log

import com.google.gson.annotations.SerializedName

data class LoggerToJson (
    @SerializedName("total_packets") var total_packets: Int,
    @SerializedName("number_packets_tracked") var number_packets_tracked: Int,
    @SerializedName("number_private_analytics_packets") var number_private_analytics_packets: Int,
    @SerializedName("all_hosts") var all_hosts: List<String>,
    @SerializedName("tracked_hosts") var tracked_hosts: List<String>,
    @SerializedName("private_hosts") var private_hosts: List<String>,
    @SerializedName("errors") var errors: List<Error>,
    @SerializedName("number_accepted_request") var number_accepted_request: Int,
    @SerializedName("number_refused_request") var number_refused_request: Int
)

// Errori in fasi di: repackaging, installazione, decodifica
data class Error (
    @SerializedName("app") var app: String,
    @SerializedName("host") var host: String,
    @SerializedName("headers") var headers:String,
    @SerializedName("body") var body:String,
    @SerializedName("error") var error: String
)