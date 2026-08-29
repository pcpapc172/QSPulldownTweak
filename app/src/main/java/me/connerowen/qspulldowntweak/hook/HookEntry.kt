package me.connerowen.qspulldowntweak.hook

import android.view.MotionEvent
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField


@InjectYukiHookWithXposed(isUsingResourcesHook = true)
object HookEntry : IYukiHookXposedInit {

    // Tracks whether the QS panel currently expanded/expanding was opened via our
    // forced edge-swipe gesture (as opposed to the normal two-stage swipe, a
    // notification tap, split-shade, etc). Reset on every new isOpenQsEvent check
    // (i.e. every fresh open attempt) and cleared once QS fully closes.
    @Volatile
    private var qsOpenedViaGesture = false

    override fun onInit() = configs {
        YukiHookAPI.configs {
            debugLog {
                tag = "QSPulldownTweak"
            }
            isDebug = false
        }
    }

    override fun onHook() = encase {
        loadApp(name = "com.android.systemui") {
            "com.android.systemui.shade.QuickSettingsControllerImpl".toClass()
                .resolve()
                .firstMethod {
                    name = "isOpenQsEvent"
                    parameters(MotionEvent::class)
                }.hook {
                    before {
                        val quickPulldownMode = prefs.getString("qs_pulldown_mode", "0").toIntOrNull() ?: 0

                        if (quickPulldownMode > 0) {
                            val ev = args[0] as MotionEvent
                            val mQs: Any? = getObjectField(instance, "mQs")
                            val mBarState: Int = getIntField(instance, "mBarState")
                            val mView = callMethod(mQs, "getView") as View?
                            val isLayoutRtl = callMethod(mView, "isLayoutRtl") as Boolean
                            val w = callMethod(mView, "getMeasuredWidth") as Int
                            val x = ev.getX()
                            val region = w * 1f / 4f
                            var showQsOverride = false

                            when (quickPulldownMode) {
                                1 -> showQsOverride =
                                    if (isLayoutRtl) x < region else w - region < x

                                2 -> showQsOverride =
                                    if (isLayoutRtl) w - region < x else x < region
                            }
                            showQsOverride = showQsOverride and (mBarState == 0)

                            // Record whether *this* open attempt is our forced-gesture
                            // open, so the close-side hook below knows whether to apply.
                            qsOpenedViaGesture = showQsOverride

                            if (showQsOverride) result = true
                        }
                    }
                }

            // When QS was opened via our forced edge gesture, make swipe-up-to-close
            // fully collapse it in one motion from anywhere on the panel — instead of
            // only fully closing when the closing swipe also starts at the edge, and
            // otherwise stopping at the notification-shade (QQS) height first.
            //
            // flingQs(velocity, type, callback, animate) collapses QS. `type` is:
            //   0 = FLING_EXPAND   (re-expand back to full QS)
            //   1 = FLING_COLLAPSE (collapse down to mMinExpansionHeight, i.e. QQS/notifications — the "1st swipe")
            //   2 = FLING_HIDE     (collapse all the way to 0 — fully closed)
            // NotificationPanelViewController calls this with type=1 on a normal
            // swipe-up-to-close regardless of where on the panel the swipe starts, and
            // only uses type=2 in split-shade mode. We upgrade 1 -> 2 here — but only
            // for a session we know was opened via our gesture — so a single swipe-up
            // anywhere on the panel always fully closes, like split-shade already does.
            "com.android.systemui.shade.QuickSettingsControllerImpl".toClass()
                .resolve()
                .firstMethod {
                    name = "flingQs"
                    parameters(Float::class, Int::class, Runnable::class, Boolean::class)
                }.hook {
                    before {
                        val oneSwipeClose = prefs.getBoolean("qs_one_swipe_close", false)
                        if (!oneSwipeClose) return@before
                        if (!qsOpenedViaGesture) return@before // don't touch normally-opened QS sessions

                        val type = args[1] as Int
                        if (type == 2) {
                            qsOpenedViaGesture = false // already fully closing; session over
                            return@before
                        }
                        if (type != 1) return@before // ignore FLING_EXPAND (snap back open)

                        val isSplitShade = getBooleanField(instance, "mSplitShadeEnabled") as? Boolean ?: false
                        if (isSplitShade) return@before // split shade already fully closes in one swipe

                        args[1] = 2 // FLING_HIDE — force full close instead of partial collapse
                        qsOpenedViaGesture = false // this collapse fully closes it; reset for next session
                    }
                }
        }
    }
}