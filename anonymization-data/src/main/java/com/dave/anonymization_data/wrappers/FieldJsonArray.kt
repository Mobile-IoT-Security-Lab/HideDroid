
package com.dave.anonymization_data.wrappers

import org.json.JSONArray

class FieldJsonArray(var jsonArray: JSONArray): FieldType {
    override fun toString(): String {
        return jsonArray.toString()
    }
}