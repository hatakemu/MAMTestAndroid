package com.hatakemu.android.mamtest.config

/**
 * アプリ全体で共有する定数。
 * - MAM / テナント関連はここから参照します。
 * - MSAL の clientId / redirectUri 等は res/raw/msal_config.json で管理します。
 */
object AppConfig {
    // === Tenant / Authority ===
    const val TENANT_ID: String = "516f6912-3d81-47b6-8866-20353e6bfdda"
    const val TENANT_AUTHORITY: String = "https://login.microsoftonline.com/$TENANT_ID"

    // === アプリ Sign in 時に指定する Scopes ===
    const val MAM_SIGNIN_SCOPE: String = "https://msmamservice.api.application/.default"
}