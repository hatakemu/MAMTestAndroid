package com.hatakemu.android.mamtest.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AuthClient {

    // まずは任意フォルダ保存も視野に入れてスコープを広めに
    // AppFolderのみで行くなら Files.ReadWrite.AppFolder に置換してください
    private val SCOPES = arrayOf("User.Read", "Files.ReadWrite", "offline_access", "openid", "profile")

    private var app: ISingleAccountPublicClientApplication? = null

    fun init(context: Context) {
        if (app != null) return
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : PublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) { app = application }
                override fun onError(exception: MsalException) { exception.printStackTrace() }
            }
        )
    }

    suspend fun signIn(activity: Activity): String = suspendCancellableCoroutine { cont ->
        val application = app ?: return@suspendCancellableCoroutine cont.resumeWithException(
            IllegalStateException("MSAL not initialized")
        )

        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(SCOPES.toList())
            .build()

        application.acquireToken(params, object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) { cont.resume(result.accessToken) }
            override fun onError(exception: MsalException) { cont.resumeWithException(exception) }
            override fun onCancel() { cont.resumeWithException(Cancellation("User cancelled")) }
        })
    }

    suspend fun acquireTokenSilent(context: Context): String? = suspendCancellableCoroutine { cont ->
        val application = app ?: return@suspendCancellableCoroutine cont.resume(null)
        application.currentAccount { accountResult ->
            val account = accountResult?.currentAccount ?: return@currentAccount cont.resume(null)
            val silent = AcquireTokenSilentParameters.Builder()
                .withScopes(SCOPES.toList())
                .forAccount(account)
                .build()
            application.acquireTokenSilentAsync(silent, object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) { cont.resume(result.accessToken) }
                override fun onError(exception: MsalException) { cont.resume(null) }
                override fun onCancel() { cont.resume(null) }
            })
        }
    }

    fun signOut(onDone: (() -> Unit)? = null) {
        app?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() { onDone?.invoke() }
            override fun onError(exception: MsalException) { exception.printStackTrace(); onDone?.invoke() }
        })
    }

    class Cancellation(message: String) : Exception(message)
}