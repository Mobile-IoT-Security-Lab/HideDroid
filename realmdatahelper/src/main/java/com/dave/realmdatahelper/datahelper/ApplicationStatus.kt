package com.dave.realmdatahelper.datahelper

import com.orm.SugarRecord
import com.orm.dsl.Unique


class ApplicationStatus: SugarRecord {
    @Unique
    var packageName:String? = null
    var installed:Boolean = false
    var isInRemoving:Boolean = false
    var isRepackaged:Boolean = false
    var isInInstalling:Boolean = false
    var isInRepackaging:Boolean = false

    constructor(): super()
    constructor(packageName:String = "", installed:Boolean = false, isRepackaged:Boolean = false,
                isInRemoving:Boolean = false, isInInstalling:Boolean = false,
                isInRepackaging:Boolean = false){
        this.packageName = packageName
        this.installed = installed
        this.isInRemoving = isInRemoving
        this.isRepackaged = isRepackaged
        this.isInInstalling = isInInstalling
        this.isInRepackaging = isInRepackaging


    }

    override fun toString(): String {
        return "APPRepackagedItem = (packageName=${this.packageName}, " +
                "installed=${this.installed}, " +
                "isInRemoving=${this.isInRemoving}, " +
                "isRepacked=${this.isRepackaged}, " +
                "isInInstalling=${this.isInInstalling}, " +
                "isInRepackaging=${this.isInRepackaging})"
    }

}