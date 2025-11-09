package com.hatakemu.android.mamtest

import com.microsoft.intune.mam.client.app.MAMApplication
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.exception.MsalException
import java.lang.reflect.Proxy
import java.lang.reflect.Method
import java.lang.reflect.InvocationHandler

/**
 * 最新 SDK(v12.x) 環境で AAR 内に MAMStrictMode/MAMEnrollmentManager が見えず
 * 直接 import できない場合でも確実に初期化できるよう、反射で処理します。
 *
 * - Strict Mode: クラスが見つかるときのみ enable() を呼ぶ
 * - 認証コールバック: MAMServiceAuthenticationCallbackExtended を Proxy で実装
 * - 登録(register)/解除(unregister): UI 側（MSAL サインイン成功/サインアウト）で呼んでください
 *
 * 必要前提:
 * - MSAL のシングルアカウント PCA を返す AuthClient.current() が利用可能であること
 */
class MyMAMApp : MAMApplication() {

    override fun onMAMCreate() {
        super.onMAMCreate()

        // 1) Strict Mode を (存在する場合のみ) 有効化
        tryEnableMAMStrictMode()

        // 2) 認証コールバックを MAM SDK へ登録（反射で動的に実施）
        tryRegisterAuthCallback()
    }

    /**
     * MAM Strict Mode の有効化を反射で試みる。
     * - クラスが見つからない/メソッドがない場合は黙ってスキップ（本番は無効でも可）
     * 参考: Stage 4 の必須要件（開発・検証時）
     */
    private fun tryEnableMAMStrictMode() {
        runCatching {
            val cls = Class.forName("com.microsoft.intune.mam.client.content.MAMStrictMode")
            val m = cls.getMethod("enable")
            m.invoke(null)
        }.onFailure {
            // MAMStrictMode が AAR から見えない場合はスキップ（ログ出力したい場合はここで）
        }
    }

    /**
     * MAMServiceAuthenticationCallbackExtended を動的 Proxy で実装し、
     * MAMEnrollmentManager.registerAuthenticationCallback(...) へ反射で登録します。
     *
     * 認証コールバックは Stage 4 で必須（SDK が Intune サービスへアクセスする度に
     * MSAL のアクセストークンを供給する）
     */
    private fun tryRegisterAuthCallback() {
        runCatching {
            // ---- 反射で必要クラスを解決 ----
            val componentsCls = Class.forName("com.microsoft.intune.mam.client.app.MAMComponents")
            val enrollmentMgrCls = Class.forName("com.microsoft.intune.mam.client.app.MAMEnrollmentManager")
            val callbackIfaceCls =
                Class.forName("com.microsoft.intune.mam.client.app.MAMServiceAuthenticationCallbackExtended")

            // MAMComponents.get(MAMEnrollmentManager.class)
            val getMethod = componentsCls.getMethod("get", Class::class.java)
            val enrollmentMgr = getMethod.invoke(null, enrollmentMgrCls)

            // 認証コールバックの Proxy を作成
            val callbackProxy = Proxy.newProxyInstance(
                callbackIfaceCls.classLoader,
                arrayOf(callbackIfaceCls),
                AuthenticationCallbackHandler()
            )

            // enrollmentMgr.registerAuthenticationCallback(callback)
            val registerMethod = enrollmentMgrCls.getMethod("registerAuthenticationCallback", callbackIfaceCls)
            registerMethod.invoke(enrollmentMgr, callbackProxy)
        }.onFailure {
            // AAR に Manager/Callback が見えない場合は登録できないためスキップ
            // （この状況では UI 側の registerAccountForMAM 呼び出しのみで進め、ポリシー適用に必要な
            //  トークン供給が発生した時点で再度 SDK 側からコールバック登録の必要性がログ出力されます）
        }
    }

    /**
     * MAMServiceAuthenticationCallbackExtended のメソッド群を動的に処理する InvocationHandler。
     *
     * 期待シグネチャ（SDK 側から呼ばれる）:
     *   acquireToken(upn: String, aadId: String, tenantId: String, authority: String, resourceId: String): String?
     *
     * 実装内容:
     *   - MSAL のサイレント取得で "$resourceId/.default" スコープのアクセストークンを返す
     */
    private class AuthenticationCallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (args == null) return null

            return when (method.name) {
                "acquireToken" -> {
                    val upn = args.getOrNull(0) as? String ?: return null
                    val aadId = args.getOrNull(1) as? String ?: return null
                    val tenantId = args.getOrNull(2) as? String ?: return null
                    val authority = args.getOrNull(3) as? String ?: return null
                    val resourceId = args.getOrNull(4) as? String ?: return null

                    // MSAL シングルアカウントから対象アカウントを特定
                    val msal = com.hatakemu.android.mamtest.auth.AuthClient.current() ?: return null
                    val account: IAccount? = runCatching {
                        msal.currentAccount?.currentAccount?.let { current ->
                            if (current.id == aadId) current else null
                        }
                    }.getOrNull()

                    if (account == null) return null

                    val scopes = listOf("$resourceId/.default")
                    return try {
                        val params = AcquireTokenSilentParameters.Builder()
                            .forAccount(account)
                            .fromAuthority(account.authority ?: authority)
                            .withScopes(scopes)
                            .build()
                        val result = msal.acquireTokenSilent(params)
                        result?.accessToken
                    } catch (_: MsalException) {
                        null
                    }
                }
                else -> null
            }
        }
    }
}
