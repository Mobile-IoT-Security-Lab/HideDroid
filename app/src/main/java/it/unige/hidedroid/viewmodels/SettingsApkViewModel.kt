package it.unige.hidedroid.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData

class SettingsApkViewModel(application:Application):AndroidViewModel(application) {
    // TODO
    var hashMapPackageNamePrivacyLevel = MutableLiveData<HashMap<String, Int>>()
}