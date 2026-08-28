package me.connerowen.qspulldowntweak.data

import com.highcapable.yukihookapi.hook.xposed.prefs.data.PrefsData

object DataConst {
    val PREF_QS_PULLDOWN_MODE = PrefsData("qs_pulldown_mode", "0")
    val PREF_QS_ONE_SWIPE_CLOSE = PrefsData("qs_one_swipe_close", false)
}