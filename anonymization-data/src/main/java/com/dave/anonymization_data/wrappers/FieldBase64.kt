package com.dave.anonymization_data.wrappers

import android.util.Base64

class FieldBase64(var base64String: String): FieldType {
    override fun toString(): String {
        return String(Base64.encode(base64String.toByteArray(), Base64.DEFAULT))
    }
}