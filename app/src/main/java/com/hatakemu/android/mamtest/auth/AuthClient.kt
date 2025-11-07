package com.hatakemu.android.mamtest.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hatakemu.android.mamtest.R

/**
 * MSAL 6.x 最終版 AuthClient
 *
 * - サインイン: SignInParameters + app.signIn(params)
 * - サインアウト: app.signOut(callback)
 *
 * 注意:
 *  - Interactive な API は UI スレッドから呼ばれますが、ここでは suspend 化しつつ、
 *    実処理は MSAL 側のコールバックで完結します（UI は呼び出し側でハンドリング）。
 *  - getOrCreate() は初回のみ MSAL クライアントを生成し、以降はキャッシュを返します。
 */
object AuthClient {
    private var client: ISingleAccountPublicClientApplication? = null

    /**
     * MSAL クライアントの初期化／取得
     * - 公開設定は res/raw/msal_config.json（例: R.raw.msal_config）を前提
     */
    suspend fun getOrCreate(context: Context): ISingleAccountPublicClientApplication {
        return withContext(Dispatchers.IO) {
            if (client == null) {
                client = PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    R.raw.msal_config
                )
            }
            client!!
        }
    }

    /**
     * 6.x 推奨の対話的サインイン。
     *
     * 旧 API（signIn(activity, loginHint, scopes, callback)）は非推奨のため使用しません。
     * 必要な情報は SignInParameters にまとめて渡します。
     *
     * @param activity  表示中の Activity（必須）
     * @param scopes    要求スコープ（例: arrayOf("https://graph.microsoft.com/.default")）
     * @param callback  結果を受け取る AuthenticationCallback
     * @param loginHint 任意のログインヒント（メールアドレス等）
     * @param prompt    任意のプロンプト（既定は SELECT_ACCOUNT）
     */
    suspend fun signInInteractive(
        activity: Activity,
        scopes: Array<String>,
        callback: AuthenticationCallback,
        loginHint: String? = null,
        prompt: Prompt = Prompt.SELECT_ACCOUNT
    ) {
        val app = getOrCreate(activity.applicationContext)

        // 6.x では SignInParameters を用いて signIn() を呼び出す
        val params = SignInParameters.builder()
            .withActivity(activity)
            .withScopes(scopes.toMutableList())
            .withCallback(callback)
            .withPrompt(prompt)
            .apply {
                if (!loginHint.isNullOrBlank()) {
                    withLoginHint(loginHint)
                }
            }
            .build()

        app.signIn(params)
    }

    /**
     * サインアウト。
     * - シングルアカウントモードの signOut はコールバックで完了通知を返します。
     * - 呼び出し側（UI）の状態更新はコールバック後に行ってください。
     */
    suspend fun signOut(activity: Activity) {
        val app = getOrCreate(activity.applicationContext)
        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                // 必要であればクリーンアップ処理をここに
            }
            override fun onError(exception: MsalException) {
                // ログ出力など（呼び出し側でもハンドリングしてください）
            }
        })
    }
}
