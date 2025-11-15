package com.hatakemu.android.mamtest.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hatakemu.android.mamtest.R

/**
 * MSAL (Android) 6.x シングルアカウント PCA ラッパー
 *
 * - 初期化: getOrCreate(context)
 * - 取得   : current()
 * - 対話サインイン: signInInteractive(...)
 * - サインアウト: signOut(...)
 *
 * 注意：
 * - msal_config.json は res/raw/msal_config.json に配置し、R.raw.msal_config で参照します。
 * - シングルアカウント PCA はアプリ全体で 1 インスタンスに保ちます。
 */
object AuthClient {

    @Volatile
    private var client: ISingleAccountPublicClientApplication? = null

    /**
     * 現在保持している MSAL シングルアカウント PCA を返します（未初期化なら null）。
     * MyMAMApp など、既に初期化済み前提で「存在チェックだけしたい」箇所で使います。
     */
    fun current(): ISingleAccountPublicClientApplication? = client

    /**
     * MSAL クライアントの取得（なければ作成）。
     * - I/O を伴うので Dispatchers.IO で実行します。
     * - マルチスレッド安全（ダブルチェック + synchronized）。
     */
    suspend fun getOrCreate(context: Context): ISingleAccountPublicClientApplication {
        current()?.let { return it }

        return withContext(Dispatchers.IO) {
            current() ?: synchronized(this@AuthClient) {
                current() ?: PublicClientApplication.createSingleAccountPublicClientApplication(
                    /* context = */ context,
                    /* configResId = */ R.raw.msal_config
                ).also { created ->
                    client = created
                }
            }
        }
    }

    /**
     * クライアントが必要な処理を安全に行うためのヘルパ。
     * - 必要なら初期化し、ラムダにクライアントを渡します。
     */
    suspend inline fun <T> withClient(
        context: Context,
        crossinline block: (ISingleAccountPublicClientApplication) -> T
    ): T {
        val app = getOrCreate(context)
        return block(app)
    }

    /**
     * 6.x 推奨の SignInParameters で対話サインイン。
     *
     * @param activity   UI スレッド上の Activity（必須）
     * @param scopes     要求スコープ（例: arrayOf("https://graph.microsoft.com/.default")）
     * @param callback   AuthenticationCallback
     * @param loginHint  任意のログインヒント
     * @param prompt     既定は SELECT_ACCOUNT
     */
    suspend fun signInInteractive(
        activity: Activity,
        scopes: Array<String>,
        callback: AuthenticationCallback,
        loginHint: String? = null,
        prompt: Prompt = Prompt.SELECT_ACCOUNT
    ) {
        val app = getOrCreate(activity.applicationContext)

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

        // 6.x：SignInParameters を渡して signIn() を呼ぶ
        app.signIn(params)
    }

    /**
     * サインアウト。コールバックは MSAL 側から呼ばれます。
     */
    suspend fun signOut(activity: Activity) {
        val app = getOrCreate(activity.applicationContext)
        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                // 必要ならクリーンアップ処理
            }

            override fun onError(exception: MsalException) {
                // ログ等
            }
        })
    }
}
