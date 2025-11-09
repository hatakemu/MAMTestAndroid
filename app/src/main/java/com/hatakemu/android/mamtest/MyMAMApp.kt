package com.hatakemu.android.mamtest

import android.util.Log
import com.microsoft.intune.mam.client.app.MAMApplication
import com.microsoft.intune.mam.client.app.MAMComponents
import com.microsoft.intune.mam.policy.MAMEnrollmentManager
import com.microsoft.intune.mam.policy.MAMServiceAuthenticationCallback
import com.microsoft.intune.mam.policy.MAMServiceAuthenticationCallbackExtended
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.exception.MsalException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * MSAL -> Intune MAM SDK トークン供給のアプリ側実装（Stage 4 準拠）。
 * - scopes は resourceId + "/.default"
 * - authority は account.authority を優先、null/空なら TENANT_AUTHORITY でフォールバック
 */
private const val TENANT_ID =
    "516f6912-3d81-47b6-8866-20353e6bfdda"

private const val TENANT_AUTHORITY =
    "https://login.microsoftonline.com/$TENANT_ID"

class MyMAMApp : MAMApplication() {

    override fun onMAMCreate() {
        super.onMAMCreate()
        enableMAMStrictModeIfAvailable()
        registerMAMAuthCallback()
    }

    /** MAM Strict Mode を（存在時のみ）有効化 */
    private fun enableMAMStrictModeIfAvailable() {
        runCatching {
            val cls = Class.forName("com.microsoft.intune.mam.client.content.MAMStrictMode")
            val m = cls.getMethod("enable")
            m.invoke(null)
            Log.d("MAM-SDK", "MAMStrictMode.enable() called")
        }.onFailure {
            Log.d("MAM-SDK", "MAMStrictMode not available; skip")
        }
    }

    /**
     * 認証コールバックを登録。
     * - mgr が null の可能性に対応（明示チェック）
     * - 引数型差異に備え、Callback/CallbackExtended の **両方**を実装する Proxy を渡す
     */
    private fun registerMAMAuthCallback() {
        val mgr: MAMEnrollmentManager? = MAMComponents.get(MAMEnrollmentManager::class.java)
        if (mgr == null) {
            Log.w("MAM-SDK", "MAMEnrollmentManager is null. Check SDK/Plugin integration and Manifest Application name.")
            return
        }

        val proxy = Proxy.newProxyInstance(
            this::class.java.classLoader,
            arrayOf(
                MAMServiceAuthenticationCallback::class.java,
                MAMServiceAuthenticationCallbackExtended::class.java
            ),
            AuthenticationCallbackHandler()
        )

        // まず Extended を試す → ダメなら無印
        runCatching {
            val m = MAMEnrollmentManager::class.java.getMethod(
                "registerAuthenticationCallback",
                MAMServiceAuthenticationCallbackExtended::class.java
            )
            m.invoke(mgr, proxy)
            Log.d("MAM-SDK", "registerAuthenticationCallback(Extended) registered")
        }.onFailure {
            runCatching {
                val m2 = MAMEnrollmentManager::class.java.getMethod(
                    "registerAuthenticationCallback",
                    MAMServiceAuthenticationCallback::class.java
                )
                m2.invoke(mgr, proxy)
                Log.d("MAM-SDK", "registerAuthenticationCallback(Callback) registered")
            }.onFailure { ex2 ->
                Log.e("MAM-SDK", "Failed to register auth callback", ex2)
            }
        }
    }

    /**
     * SDK -> アプリ向け認証コールバックの実装。
     * acquireToken(upn, aadId, tenantId, authority, resourceId) を受け、
     * scopes = { resourceId + "/.default" } で MSAL Silent を実行し、accessToken を返す。
     */
    private class AuthenticationCallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.name != "acquireToken") return null
            if (args == null || args.size < 5) return null

            val upn           = args[0] as? String ?: return null
            val aadId         = args[1] as? String ?: return null
            val tenantIdParam = args[2] as? String
            val authorityArg  = args[3] as? String
            val resourceId    = args[4] as? String ?: return null

            val msal = com.hatakemu.android.mamtest.auth.AuthClient.current() ?: return null
            val account: IAccount = msal.currentAccount?.currentAccount?.let { current ->
                if (current.id == aadId || current.username.equals(upn, ignoreCase = true)) current else null
            } ?: return null

            // authority は account.authority を優先、null/空なら SDK から、さらに null/空ならテナント固定
            val effectiveAuthority = when {
                !account.authority.isNullOrBlank() -> account.authority!!
                !authorityArg.isNullOrBlank()      -> authorityArg!!
                else                               -> TENANT_AUTHORITY
            }

            val scopes = listOf("$resourceId/.default")

            Log.d(
                "MAM-Token",
                "Preparing silent token for MAM: upn=$upn, aadId=$aadId, " +
                        "tenant=${tenantIdParam ?: TENANT_ID}, resourceId=$resourceId, " +
                        "authority=$effectiveAuthority, scopes=${scopes.joinToString()}"
            )

            return try {
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(effectiveAuthority)
                    .withScopes(scopes)
                    .build()

                val result = msal.acquireTokenSilent(params)
                val token = result?.accessToken ?: return null

                Log.i(
                    "MAM-Token",
                    "Token acquired for MAM. " +
                            "resourceId=$resourceId, scopes=${scopes.joinToString()}, " +
                            "authority=$effectiveAuthority, token=$token"
                )

                token
            } catch (_: MsalException) {
                null
            }
        }
    }
}

/**
 * MAM 登録ユーティリティ。
 * SDK の 2 つのシグネチャ（(upn, aadId, tenantId?) / (upn, aadId, tenantId?, authority)）に合わせて呼び分ける。
 */
object MAMInterop {

    /**
     * @param upn        UPN（例: user@contoso.com）
     * @param aadId      IAccount.id（例: GUID）
     * @param tenantId   既知のテナント ID。未指定なら既定の TENANT_ID を使用
     * @param authority  既知の Authority。未指定なら既定の TENANT_AUTHORITY を使用
     * @return ログ用の文字列（成功/失敗を含む）
     */
    fun register(
        upn: String,
        aadId: String,
        tenantId: String? = null,
        authority: String? = null
    ): String {
        val mgr: MAMEnrollmentManager? = MAMComponents.get(MAMEnrollmentManager::class.java)
        if (mgr == null) {
            val msg = "registerAccountForMAM failed: MAMEnrollmentManager is null"
            Log.e("MAM-Enroll", msg)
            return msg
        }

        val tId = tenantId ?: TENANT_ID
        val auth = authority ?: TENANT_AUTHORITY

        // まず 4 引数版 (upn, aadId, tenantId?, authority) を試す → ダメなら 3 引数版へ
        return runCatching {
            val m4 = MAMEnrollmentManager::class.java.getMethod(
                "registerAccountForMAM",
                String::class.java,  // upn
                String::class.java,  // aadId
                String::class.java,  // tenantId (nullable 可)
                String::class.java   // authority
            )
            val res = m4.invoke(mgr, upn, aadId, tId, auth)
            val msg = "registerAccountForMAM(upn=$upn, aadId=$aadId, tenantId=$tId, authority=$auth) -> ${res?.toString() ?: "void"}"
            Log.i("MAM-Enroll", msg)
            msg
        }.getOrElse { ex4 ->
            runCatching {
                val m3 = MAMEnrollmentManager::class.java.getMethod(
                    "registerAccountForMAM",
                    String::class.java,  // upn
                    String::class.java,  // aadId
                    String::class.java   // tenantId (nullable 可)
                )
                val res = m3.invoke(mgr, upn, aadId, tId)
                val msg = "registerAccountForMAM(upn=$upn, aadId=$aadId, tenantId=$tId) -> ${res?.toString() ?: "void"}"
                Log.i("MAM-Enroll", msg)
                msg
            }.getOrElse { ex3 ->
                val msg = "registerAccountForMAM failed: ${ex3.javaClass.simpleName}: ${ex3.message}"
                Log.e("MAM-Enroll", msg, ex3)
                msg
            }
        }
    }
}
