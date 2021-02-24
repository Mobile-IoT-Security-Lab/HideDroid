package com.dave.anonymization_data.data

import com.dave.anonymization_data.parsering.ValueWrapper

enum class ContentEncoding {
    GZIP, DEFLATE, NONE
}

enum class ContentType {
    MULTIPART_FORM_DATA, X_WWW_FORM_URLENCODED, APPLICATION_JSON, TEXT_PLAIN, NONE
}

/**
 * Classe wraopper che mantiene informazioni sulla richiesta (ad es, il tipo di encoding) e memorizza
 * il body in un formato utile per la successiva anonimizzazione
 */
class MultidimensionalData {

    var id: Long? = null
    var packageName: String? = null
    var host: String? = null
    var method: String? = null
    var path: String? = null
    var httpProtocol: String? = null
    var headers: String? = null
    var body: MutableMap<String, ValueWrapper>? = null
    var contentEncoding: ContentEncoding = ContentEncoding.NONE
    var contentType: ContentType = ContentType.NONE
    var extraBytes: StringBuffer? = null

    constructor(id: Long, packageName: String, host: String, method: String, path: String, httpProtocol: String, headers: String,
                body: MutableMap<String, ValueWrapper>, contentType: ContentType, extraBytes: StringBuffer) {
        this.id = id
        this.packageName = packageName
        this.host = host
        this.method = method
        this.path = path
        this.httpProtocol = httpProtocol
        this.headers = headers
        this.body = body
        this.contentType = contentType
        this.extraBytes = extraBytes

        when {
            headers.contains("\"Content-Encoding\":[\"gzip\"]") -> {
                this.contentEncoding = ContentEncoding.GZIP
            }

            headers.contains("\"Content-Encoding\":[\"deflate\"]")  -> {
                this.contentEncoding = ContentEncoding.DEFLATE
            }
        }
    }

    constructor() {}
}