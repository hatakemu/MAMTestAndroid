import android.util.Log
import com.microsoft.intune.mam.policy.MAMServiceAuthenticationCallbackExtended
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.exception.MsalException

private const val TENANT_ID =
    "516f6912-3d81-47b6-8866-20353e6bfdda"
private const val TENANT_AUTHORITY =
    "https://login.microsoftonline.com/$TENANT_ID"

/**
 * CallbackExtended を “直接” 実装する版
 * - 5引数版 & 3引数版の acquireToken を両方 override する
 * - 3引数版は 5引数版に委譲
 * - デバッグ用途：トークン/PII を無マスクでログ出力（検証後は必ずマスクに戻してください）
 */
private class MyMAMAuthCallback : MAMServiceAuthenticationCallbackExtended {

    // 5引数版： (upn, aadId, tenantId?, authority?, resourceId)
    override fun acquireToken(
        upn: String,
        aadId: String,
        tenantId: String?,          // ← String! は Kotlin では ? で受ける
        authority: String?,         // ← 同上
        resourceId: String
    ): String? {
        if (resourceId.isBlank()) {
            Log.e("MAM-Token", "resourceId is blank")
            return null
        }

        Log.d(
            "MAM-Token",
            "AcquireToken(5) requested: upn=$upn, aadId=$aadId, tenantId=$tenantId, " +
                    "authority=$authority, resourceId=$resourceId, scopes=[$resourceId/.default]"
        )

        val msal = com.hatakemu.android.mamtest.auth.AuthClient.current()
            ?: run {
                Log.e("MAM-Token", "MSAL client is null (AuthClient.current())")
                return null
            }

        val account: IAccount = msal.currentAccount?.currentAccount?.let { acc ->
            if (acc.id == aadId || acc.username.equals(upn, ignoreCase = true)) acc else null
        } ?: run {
            Log.w("MAM-Token", "No MSAL account for aadId=$aadId / upn=$upn")
            return null
        }

        val effectiveAuthority = when {
            !account.authority.isNullOrBlank() -> account.authority!!
            !authority.isNullOrBlank()         -> authority
            else                               -> TENANT_AUTHORITY
        }

        val scopes = listOf("$resourceId/.default")

        return try {
            val params = AcquireTokenSilentParameters.Builder()
                .forAccount(account)
                .fromAuthority(effectiveAuthority)
                .withScopes(scopes)
                .build()

            val result = msal.acquireTokenSilent(params)
            val token = result?.accessToken

            if (token.isNullOrBlank()) {
                Log.e("MAM-Token", "Silent token is null/blank")
                null
            } else {
                Log.i(
                    "MAM-Token",
                    "Token acquired (UNMASKED): aud=$resourceId, authority=$effectiveAuthority, " +
                            "tenant=${tenantId ?: TENANT_ID}, scopes=${scopes.joinToString()}, " +
                            "upn=$upn, aadId=$aadId, token=$token"
                )
                token
            }
        } catch (ex: MsalException) {
            Log.e("MAM-Token", "Silent token error: code=${ex.errorCode}, msg=${ex.message}", ex)
            null
        } catch (t: Throwable) {
            Log.e("MAM-Token", "Unexpected failure in acquireToken(5)", t)
            null
        }
    }

    // 3引数版： (upn, aadId, resourceId)
    override fun acquireToken(
        upn: String,
        aadId: String,
        resourceId: String
    ): String? {
        // tenantId / authority が未提示の古い呼び出しに対応：
        // 5引数版に委譲（tenantId=null, authority=null）
        return acquireToken(
            upn = upn,
            aadId = aadId,
            tenantId = null,
            authority = null,
            resourceId = resourceId
        )
    }
}
