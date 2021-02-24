package com.dave.anonymization_data.parsering

class MultipartValueWrapper: ValueWrapper {

    var filename: String?
    var contentType: String?

    constructor(filename: String?, contentType: String?, value: String): super(value) {
        this.filename = filename
        this.contentType = contentType
    }
}