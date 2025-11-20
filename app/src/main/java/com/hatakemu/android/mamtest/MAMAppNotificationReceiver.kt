package com.hatakemu.android.mamtest.mam

import android.util.Log
import com.microsoft.intune.mam.client.notification.MAMNotificationReceiver
import com.microsoft.intune.mam.policy.notification.MAMNotification
import com.microsoft.intune.mam.policy.notification.MAMNotificationType
import com.microsoft.intune.mam.policy.notification.MAMEnrollmentNotification
import com.microsoft.intune.mam.policy.notification.MAMComplianceNotification
import com.microsoft.intune.mam.client.app.MAMComponents
import com.microsoft.intune.mam.policy.MAMComplianceManager
import com.hatakemu.android.mamtest.auth.AuthClient

class MAMAppNotificationReceiver(
    private val upn: String,
    private val aadId: String,
    private val tenantId: String,
    private val authority: String
) : MAMNotificationReceiver {

    override fun onReceive(notification: MAMNotification): Boolean {
        return try {
            when (notification.type) {
                MAMNotificationType.MAM_ENROLLMENT_RESULT -> {
                    val enrollNotif = notification as MAMEnrollmentNotification
                    Log.i("MAM-Notify", "Enrollment result: ${enrollNotif.enrollmentResult}")

                    /* remediateCompliance はここでは実行しない
                    if (enrollNotif.enrollmentResult.name == "ENROLLMENT_SUCCEEDED") {

                        // MSALから現在のアカウント情報を取得
                        val account = AuthClient.current()?.currentAccount?.currentAccount
                        val upn = account?.username ?: ""
                        val aadId = account?.id ?: ""

                        if (upn.isNotBlank() && aadId.isNotBlank()) {
                            val complianceManager =
                                MAMComponents.get(MAMComplianceManager::class.java)
                            complianceManager?.remediateCompliance(
                                upn,
                                aadId,
                                tenantId,
                                authority,
                                true
                            )
                            Log.i("MAM-Compliance", "remediateCompliance called for $upn")
                        } else {
                            Log.e("MAM-Compliance", "Cannot remediate: UPN or AAD ID is empty")
                        }
                    }
                     */
                }

                MAMNotificationType.COMPLIANCE_STATUS -> {
                    val complianceNotif = notification as MAMComplianceNotification
                    Log.i("MAM-Notify", "Compliance status: ${complianceNotif.complianceStatus}")
                }

                MAMNotificationType.WIPE_USER_DATA -> {
                    // Selective WIpe はアプリの実装責任
                    Log.i("MAM-Notify", "Selective Wipe")
                }

                else -> Log.d("MAM-Notify", "Other notification: ${notification.type}")
            }
            true
        } catch (e: Exception) {
            Log.e("MAM-Notify", "Failed to handle ${notification.type}: ${e.message}", e)
            false
        }
    }
}
