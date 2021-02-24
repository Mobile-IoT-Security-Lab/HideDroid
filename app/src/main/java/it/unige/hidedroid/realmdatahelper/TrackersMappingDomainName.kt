package it.unige.hidedroid.realmdatahelper

import android.content.Context
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import java.io.IOException
import kotlin.text.RegexOption.IGNORE_CASE

class TrackersMappingDomainName {

    companion object{
        private var DEFAULT_VALUE = "DEFAULT_VALUE"
        private var TAG = TrackersMappingDomainName::class.java.name
    }
    private var hashMapping: HashMap<String, MutableList<String>>? = null

    constructor(hashMap: HashMap<String, MutableList<String>>){
        this.hashMapping = hashMap
    }

    private fun addMapping(url: String, name: String){
        if (this.hashMapping != null) {
            var mutableList = this.hashMapping?.get(url)
            if (mutableList == null) {
                mutableList = mutableListOf<String>()
            }
            mutableList!!.add(name)
            this.hashMapping?.put(url, mutableList)
        }
    }

    fun isUrlPresent(url:String): Boolean {
        var found = false
        for (regex in this.hashMapping?.keys!!){
            if (regex.toRegex(IGNORE_CASE).containsMatchIn(url)){
                found = true
                break
            }
        }
        return found
    }


    private fun getJsonDataFromAsset(context: Context, fileName: String):String?{
        val jsonString: String
        try{
            jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        }catch (ioException: IOException){
            return null
        }
        return jsonString
    }

    fun populateMapping(jsonFileName: String, context: Context){

        var jsonString = getJsonDataFromAsset(context, jsonFileName)
        var parser: Parser = Parser.default()
        var stringBuilder: StringBuilder = StringBuilder(jsonString!!)
        var json: JsonArray<JsonObject> = parser.parse(stringBuilder) as JsonArray<JsonObject>
        for (tracker in json.value){
            if (tracker["network_signature"].toString().isNotBlank()) {
                this.addMapping(url=tracker["network_signature"].toString(), name=tracker["name"].toString())
            }
        }
    }


}