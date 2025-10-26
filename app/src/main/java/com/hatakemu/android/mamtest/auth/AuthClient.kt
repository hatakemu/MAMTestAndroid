package com.hatakemu.android.mamtest.auth

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.hatakemu.android.mamtest.R
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException

/**
 * MSAL Single-Account 用の最小クライアント。
 * - msal_config.json は res/raw に配置（R.raw.msal_config）
 * - Single-Account API（getCurrentAccountAsync / signOut(callback)）を利用
 */
object AuthClient {

    @Volatile
    private var pca: ISingleAccountPublicClientApplication? = null

    @MainThread
    fun getOrCreate(context: Context): ISingleAccountPublicClientApplication {
        pca?.let { return it }
        synchronized(this) {
            pca?.let { return it }
            val created = PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                R.raw.msal_config
            )
            pca = created
            return created
        }
    }

    /** 対話式サインイン（Activity 必須）。 */
    fun signInInteractive(
        activity: Activity,
        scopes: Array<String>,
        callback: AuthenticationCallback
    ) {
        val app = getOrCreate(activity.applicationContext)
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(scopes.toList())
            .withCallback(callback)
            .build()
        app.acquireToken(params)
    }

    /**
     * サイレント取得（current account 前提）。
     * アカウント未設定なら MsalUiRequiredException を返して UI 必要を通知。
     */
    fun acquireTokenSilent(
        context: Context,
        scopes: Array<String>,
        callback: AuthenticationCallback
    ) {
        val app = getOrCreate(context)
        app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    callback.onError(
                        MsalUiRequiredException("no_account", "No active account for silent token.")
                    )
                    return
                }
                val silentParams = AcquireTokenSilentParameters.Builder()
                    .forAccount(activeAccount)
                    .fromAuthority(app.configuration.defaultAuthority.authorityURL.toString())
                    .withScopes(scopes.toList())
                    .withCallback(callback)
                    .build()
                app.acquireTokenSilentAsync(silentParams)
            }

            /** Single-Account で現在のアカウントが変化したときに呼ばれます。必要なら UI 更新などを実施。 */
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                // ここは必須メソッド。最低限、何もしない実装でも OK。
                // 例：ログを出す、アプリ状態をリセット、キャッシュ破棄など。
                // Log.d("AuthClient", "Account changed: prior=$priorAccount, current=$currentAccount")
            }

            override fun onError(exception: MsalException) {
                callback.onError(exception)
            }
        })
    }

    /** サインアウト（現在のアカウント）。 */
    fun signOut(context: Context, onComplete: (Throwable?) -> Unit) {
        val app = getOrCreate(context)
        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() = onComplete(null)
            override fun onError(exception: MsalException) = onComplete(exception)
        })
    }
}