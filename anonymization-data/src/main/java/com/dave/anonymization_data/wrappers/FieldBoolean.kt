package com.dave.anonymization_data.wrappers

class FieldBoolean(var boolean: Boolean): FieldType {
    override fun toString(): String {
        return boolean.toString()
    }
}