package com.dave.anonymization_data.wrappers

class FieldPlaintext(var plaintext: String): FieldType {
    override fun toString(): String {
        return plaintext
    }
}