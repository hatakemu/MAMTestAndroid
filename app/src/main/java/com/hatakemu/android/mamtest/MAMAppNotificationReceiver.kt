package com.hatakemu.android.mamtest.mam

import android.util.Log
import com.microsoft.intune.mam.client.notification.MAMNotificationReceiver
import com.microsoft.intune.mam.policy.notification.MAMNotification
import com.microsoft.intune.mam.policy.notification.MAMNotificationType

class MAMAppNotificationReceiver : MAMNotificationReceiver {
    override fun onReceive(notification: MAMNotification): Boolean = try {
        when (notification.type) {
            // MAMNotificationType 毎に必要な処理を実装。

            MAMNotificationType.WIPE_USER_DATA -> {
                Log.i("MAM-Notify", "Selective wipe signal received; cleaning org data")
                // 選択的ワイプの実装は、個々のアプリに実装の責任がある。このテストアプリでは何もしない。
                true
            }

            MAMNotificationType.COMPLIANCE_STATUS -> {
                Log.i("MAM-Notify", "Compliance status notification")
                true
            }

            else -> true
        }
    } catch (t: Throwable) {
        Log.e("MAM-Notify", "Failed to handle ${notification.type}: ${t.message}", t)
        false
    }
}
