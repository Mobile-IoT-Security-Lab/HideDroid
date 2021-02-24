package com.dave.realmdatahelper.realmmodules

import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.debug.Request
import com.dave.realmdatahelper.debug.Response
import io.realm.annotations.RealmModule


@RealmModule(classes = [Request::class, Error::class, Response::class])
class LoggerModule