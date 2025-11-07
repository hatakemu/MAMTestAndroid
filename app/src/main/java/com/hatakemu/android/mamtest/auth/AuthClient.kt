package com.hatakemu.android.mamtest.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hatakemu.android.mamtest.R
object AuthClient {
    private var client: ISingleAccountPublicClientApplication? = null

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

    suspend fun signInInteractive(
        activity: Activity,
        scopes: Array<String>,
        callback: AuthenticationCallback
    ) {
        val app = getOrCreate(activity.applicationContext)
        app.signIn(activity, null, scopes, callback)
    }

    suspend fun signOut(activity: Activity) {
        val app = getOrCreate(activity.applicationContext)
        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {}
            override fun onError(exception: MsalException) {}
        })
    }
}
