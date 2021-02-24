package com.dave.realmdatahelper.datahelper

import com.orm.SugarRecord
import com.orm.dsl.Unique

class PackageNamePrivacyLevel: SugarRecord {
    @Unique
    var packageName:String? = null
    var isInstalled: Boolean = false
    var privacyLevel:Int = -1

    constructor(): super()
    constructor(packageName:String="", isInstalled:Boolean = false, privacyLevel:Int=0) {
        this.packageName = packageName
        this.isInstalled = isInstalled
        this.privacyLevel = privacyLevel
    }

    override fun toString(): String {
        return "PackageNamePrivacyLevel = (packageName=${this.packageName}, " +
                "installed=${this.isInstalled}, " +
                "privacyLevel=${this.privacyLevel})"
    }
}