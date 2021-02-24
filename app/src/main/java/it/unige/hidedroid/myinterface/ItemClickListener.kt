package it.unige.hidedroid.myinterface

import android.view.View

interface ItemClickListener {
    fun onClick(view: View?, position:Int, isLongClick:Boolean): Unit
}