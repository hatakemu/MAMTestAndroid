package com.hatakemu.android.mamtest

import android.util.Log
import com.microsoft.intune.mam.client.app.MAMApplication
import com.microsoft.intune.mam.client.app.MAMComponents
import com.microsoft.intune.mam.policy.MAMEnrollmentManager
import com.microsoft.intune.mam.policy.MAMServiceAuthenticationCallbackExtended
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.intune.mam.client.notification.MAMNotificationReceiverRegistry
import com.microsoft.intune.mam.policy.notification.MAMNotificationType
import com.hatakemu.android.mamtest.config.AppConfig
import com.hatakemu.android.mamtest.auth.AuthClient
import com.hatakemu.android.mamtest.mam.MAMAppNotificationReceiver

private const val TENANT_ID = AppConfig.TENANT_ID
private const val TENANT_AUTHORITY = AppConfig.TENANT_AUTHORITY

class MyMAMApp : MAMApplication() {

    override fun onMAMCreate() {
        super.onMAMCreate()

        // --- 必要なら StrictMode などの手動初期化をここで ---
        // enableMAMStrictModeIfAvailable()

        // --- MAMEnrollmentManager に認証コールバックを登録---
        val enrollmentManager = MAMComponents.get(MAMEnrollmentManager::class.java)
        if (enrollmentManager == null) {
            Log.e("MAM-SDK", "MAMEnrollmentManager is null. Check SDK/Manifest.")
            return
        }
        enrollmentManager.registerAuthenticationCallback(object : MAMServiceAuthenticationCallbackExtended {

            override fun acquireToken(
                upn: String,
                aadId: String,
                tenantId: String?,         // null 可
                authority: String?,         // null 可
                resourceId: String
            ): String? {
                if (resourceId.isBlank()) {
                    Log.e("MAM-Token", "resourceId is blank")
                    return null
                }

                val pca = AuthClient.current() ?: run {
                    Log.e("MAM-Token", "MSAL PCA is null (AuthClient.current())")
                    return null
                }

                val account = pca.currentAccount?.currentAccount ?: run {
                    Log.w("MAM-Token", "No MSAL currentAccount")
                    return null
                }

                // できれば upn / aadId と一致確認（簡易）
                if (account.id != aadId && !account.username.equals(upn, ignoreCase = true)) {
                    Log.w("MAM-Token", "Account mismatch for upn=$upn / aadId=$aadId")
                    return null
                }

                val effectiveAuthority = when {
                    !account.authority.isNullOrBlank() -> account.authority!!
                    !authority.isNullOrBlank()         -> authority
                    else                               -> TENANT_AUTHORITY
                }
                val scopes = listOf("$resourceId/.default")

                return try {
                    val params = AcquireTokenSilentParameters
                        .Builder()
                        .forAccount(account)
                        .fromAuthority(effectiveAuthority)
                        .withScopes(scopes)
                        .build()

                    val result = pca.acquireTokenSilent(params)
                    val token  = result?.accessToken

                    if (token.isNullOrBlank()) {
                        Log.e("MAM-Token", "Silent token is null/blank")
                        null
                    } else {
                        // 検証時のみ UNMASK ログに注意。実運用ではマスク推奨
                        Log.i("MAM-Token",
                            "Token acquired (UNMASKED): aud=$resourceId, authority=$effectiveAuthority, " +
                                    "tenant=${tenantId ?: TENANT_ID}, scopes=${scopes.joinToString()}, " +
                                    "upn=$upn, aadId=$aadId, token=$token")
                        token
                    }
                } catch (e: MsalException) {
                    Log.e("MAM-Token", "Silent token error: ${e.errorCode} / ${e.message}", e)
                    null
                } catch (t: Throwable) {
                    Log.e("MAM-Token", "Unexpected failure in acquireToken(5)", t)
                    null
                }
            }
        })
        Log.d("MAM-SDK", "MAMServiceAuthenticationCallbackExtended registered (code)")

        // NotificationReceiver を登録
        val receiver = MAMAppNotificationReceiver()
        val registry = MAMComponents.get(MAMNotificationReceiverRegistry::class.java)
        registry?.registerReceiver(receiver, MAMNotificationType.WIPE_USER_DATA)
        registry?.registerReceiver(receiver, MAMNotificationType.COMPLIANCE_STATUS)

    }

    // 任意：StrictMode
    @Suppress("unused")
    private fun enableMAMStrictModeIfAvailable() {
        runCatching {
            val cls = Class.forName("com.microsoft.intune.mam.client.content.MAMStrictMode")
            cls.getMethod("enable").invoke(null)
            Log.d("MAM-SDK", "MAMStrictMode.enable() called")
        }.onFailure { Log.d("MAM-SDK", "MAMStrictMode not available; skip") }
    }
}

