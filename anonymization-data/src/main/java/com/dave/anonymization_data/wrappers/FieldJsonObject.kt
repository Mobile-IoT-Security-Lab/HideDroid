package com.dave.anonymization_data.wrappers

import org.json.JSONObject

class FieldJsonObject(var jsonObject: JSONObject): FieldType {
    override fun toString(): String {
        return jsonObject.toString()
    }
}