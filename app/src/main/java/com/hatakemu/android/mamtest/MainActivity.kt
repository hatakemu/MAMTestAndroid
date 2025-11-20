@file:OptIn(ExperimentalMaterial3Api::class)
package com.hatakemu.android.mamtest

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.Logger
import com.microsoft.identity.client.exception.MsalException
import com.hatakemu.android.mamtest.auth.AuthClient
import com.hatakemu.android.mamtest.config.AppConfig
import com.microsoft.intune.mam.client.app.MAMComponents
import com.microsoft.intune.mam.policy.MAMComplianceManager


private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}


// Sign-in 用の MAM リソース
private const val MAM_SIGNIN_SCOPE = AppConfig.MAM_SIGNIN_SCOPE
private const val TENANT_ID = AppConfig.TENANT_ID
private const val TENANT_AUTHORITY = AppConfig.TENANT_AUTHORITY



class MainActivity : ComponentActivity() {

    /** 共有 **/
    private fun sharePlainText(subject: String, body: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(sendIntent, "Share text"))
    }

    /**
     * MSAL 現在アカウントを I/O スレッドで取得するヘルパ
     * @return Triple<upn, aadId, authority> or null
     */
    private suspend fun resolveMsalAccount(context: Context): Triple<String, String?, String?>? {
        val msal = AuthClient.getOrCreate(context.applicationContext)
        return withContext(Dispatchers.IO) {
            val acc = msal.currentAccount?.currentAccount ?: return@withContext null
            Triple(acc.username ?: return@withContext null, acc.id, acc.authority)
        }
    }


    /**
     * MAM の登録
     * @param upn  必須
     * @param aadId 任意（あればオーバーロードで渡す）
     */
    private fun enrollCurrentAccount(upn: String, aadId: String?) {
        val enrollmentManager = com.microsoft.intune.mam.client.app.MAMComponents.get(
            com.microsoft.intune.mam.policy.MAMEnrollmentManager::class.java
        )
        if (enrollmentManager == null) {
            Log.w("MAM-Enroll", "MAMEnrollmentManager is null")
            return
        }
        try {
            // registerAccountForMAM メソッドを取得
            val method = com.microsoft.intune.mam.policy.MAMEnrollmentManager::class.java.getMethod(
                "registerAccountForMAM",
                String::class.java, // upn
                String::class.java, // aadId
                String::class.java, // tenantId
                String::class.java  // authority
            )

            // 取得した registerAccountForMAM メソッドで MAM Enroll を実行
            val result = method.invoke(enrollmentManager, upn, aadId, TENANT_ID, TENANT_AUTHORITY)

            // ログ出力
            val msg = "registerAccountForMAM(upn=$upn, aadId=$aadId, tenantId=$TENANT_ID, authority=$TENANT_AUTHORITY) -> ${result?.toString() ?: "void"}"
            Log.i("MAM-Enroll", msg)

        } catch (e: Exception) {
            Log.e("MAM-Enroll", "Failed to register MAM: ${e.message}", e)
            throw e
        }
    }


    /**
     * MAM の登録解除
     * @param upn  必須
     * @param aadId 任意（あればオーバーロードで渡す）
     */
    private fun unenrollCurrentAccount(upn: String, aadId: String?) {
        val enrollmentManager = com.microsoft.intune.mam.client.app.MAMComponents.get(
            com.microsoft.intune.mam.policy.MAMEnrollmentManager::class.java
        )
        if (enrollmentManager == null) {
            Log.w("MAM-Unenroll", "MAMEnrollmentManager is null")
            return
        }
        try {
            if (aadId != null) {
                enrollmentManager.unregisterAccountForMAM(upn, aadId)
            } else {
                enrollmentManager.unregisterAccountForMAM(upn)
            }
            Log.i("MAM-Unenroll", "unregisterAccountForMAM called for $upn ($aadId)")
        } catch (e: Exception) {
            Log.e("MAM-Unenroll", "Failed to unregister MAM: ${e.message}", e)
            throw e
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MSAL 詳細ログ（デバッグ用途：PII を有効化中。検証後は false に戻してください）
        Logger.getInstance().setEnableLogcatLog(true)
        Logger.getInstance().setLogLevel(Logger.LogLevel.VERBOSE)
        Logger.getInstance().setEnablePII(true)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val activity = remember(context) { context.findActivity() }
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                // Editor UI 状態
                var text by remember { mutableStateOf(TextFieldValue("")) }
                var fileName by remember { mutableStateOf("memo.txt") }
                var fileList by remember { mutableStateOf(listOf<String>()) }
                var showFilePicker by remember { mutableStateOf(false) }

                // 認証状態（UI は MSAL currentAccount と同期）
                var signedInUser by remember { mutableStateOf<String?>(null) }
                var isSignedIn by remember { mutableStateOf(false) }

                // 起動時：ファイル一覧 + currentAccount 確認
                LaunchedEffect(Unit) {
                    fileList = withContext(Dispatchers.IO) {
                        context.filesDir.listFiles()
                            ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
                            ?.map { it.name }
                            ?.sorted()
                            ?: emptyList()
                    }
                    // currentAccount（I/O スレッドで取得）
                    val triple = resolveMsalAccount(applicationContext)
                    isSignedIn = triple != null
                    signedInUser = triple?.first
                }

                Scaffold(
                    topBar = { TopAppBar(title = { Text("MAM Test Editor") }) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        // ===== 操作ボタン行 =====
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // --- Sign in（対話：MAM リソース） ---
                            Button(
                                enabled = !isSignedIn,
                                onClick = {
                                    if (activity == null) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Activity を取得できませんでした")
                                        }
                                        return@Button
                                    }
                                    coroutineScope.launch {
                                        try {
                                            val triple = resolveMsalAccount(applicationContext)
                                            if (triple != null) {
                                                val upn = triple.first ?: ""
                                                val aadId = triple.second ?: ""

                                                isSignedIn = true
                                                signedInUser = upn

                                                Log.d("MSAL", "Current account found: UPN: $upn, AADID: $aadId")
                                                snackbarHostState.showSnackbar("すでにサインイン済みです ($upn)")

                                                // --- MAM Enrollment
                                                try {
                                                    // MAM Enrollment 実行
                                                    Log.d("MAM-Enroll", "Enroll called for $upn")
                                                    enrollCurrentAccount(upn, aadId)

                                                } catch (e: Exception) {
                                                    Log.e(
                                                        "MAM-Enroll",
                                                        "MAM Enrollment failed: ${e.message}",
                                                        e
                                                    )
                                                }

                                                // remediateCompliance を利用して MAM 適用
                                                if (upn.isNotBlank() && aadId.isNotBlank()) {
                                                    val complianceManager =
                                                        MAMComponents.get(MAMComplianceManager::class.java)
                                                    complianceManager?.remediateCompliance(
                                                        upn,
                                                        aadId,
                                                        TENANT_ID,
                                                        TENANT_AUTHORITY,
                                                        true
                                                    )
                                                    Log.i("MAM-Compliance", "remediateCompliance called for $upn")
                                                } else {
                                                    Log.e("MAM-Compliance", "Cannot remediate: UPN or AAD ID is empty")
                                                }

                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("MAM enrolled: $upn")
                                                }

                                            } else {
                                                AuthClient.signInInteractive(
                                                    activity = activity,
                                                    scopes = arrayOf(MAM_SIGNIN_SCOPE),
                                                    callback = object : AuthenticationCallback {
                                                        override fun onSuccess(result: IAuthenticationResult) {
                                                            val upn = result.account?.username ?: ""
                                                            val aadId = result.account?.id ?: ""
                                                            isSignedIn = true
                                                            signedInUser = upn

                                                            Log.d("MSAL", "SignIn Success: $upn, $aadId")

                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    "Signed in: ${upn ?: "unknown"}"
                                                                )
                                                            }

                                                            // --- MAM Enrollment
                                                            try {
                                                                Log.i("MAM-Enroll","UPN: $upn, AADID: $aadId")

                                                                // MAM Enrollment 実行
                                                                Log.d("MAM-Enroll", "Enroll called for $upn")
                                                                enrollCurrentAccount(upn, aadId)

                                                            } catch (e: Exception) {
                                                                Log.e(
                                                                    "MAM-Enroll",
                                                                    "MAM Enrollment failed: ${e.message}",
                                                                    e
                                                                )
                                                            }

                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar("Signed in & MAM enrolled: $upn")
                                                            }

                                                            // remediateCompliance を利用して MAM 適用
                                                            if (upn.isNotBlank() && aadId.isNotBlank()) {
                                                                val complianceManager =
                                                                    MAMComponents.get(MAMComplianceManager::class.java)
                                                                complianceManager?.remediateCompliance(
                                                                    upn,
                                                                    aadId,
                                                                    TENANT_ID,
                                                                    TENANT_AUTHORITY,
                                                                    true
                                                                )
                                                                Log.i("MAM-Compliance", "remediateCompliance called for $upn")
                                                            } else {
                                                                Log.e("MAM-Compliance", "Cannot remediate: UPN or AAD ID is empty")
                                                            }
                                                        }

                                                        override fun onError(exception: MsalException) {
                                                            Log.e(
                                                                "MSAL",
                                                                "SignIn Error: ${exception.errorCode} - ${exception.message}",
                                                                exception
                                                            )
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar("Sign-in 失敗: ${exception.errorCode}")
                                                            }
                                                        }

                                                        override fun onCancel() {
                                                            Log.w(
                                                                "MSAL",
                                                                "SignIn Cancelled by user"
                                                            )
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar("キャンセルされました")
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MSAL", "signInInteractive 呼び出し失敗", e)
                                            snackbarHostState.showSnackbar("Sign-in 呼び出しで例外: ${e.message}")
                                        }
                                    }
                                }
                            ) { Text("Sign in") }

                            // --- Sign out（Unenroll → Sign-out の順 / 個別エラーハンドル） ---
                            OutlinedButton(
                                enabled = isSignedIn,
                                onClick = {
                                    if (activity == null) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Activity を取得できませんでした")
                                        }
                                        return@OutlinedButton
                                    }
                                    coroutineScope.launch {
                                        var unenrollError: Exception? = null
                                        var signOutError: Exception? = null

                                        // 事前に（I/O で）UPN/AADID を取得
                                        val triple = resolveMsalAccount(applicationContext)
                                        val upn = triple?.first
                                        val aadId = triple?.second

                                        // 1. MAM Unenroll
                                        try {
                                            if (upn != null) {
                                                unenrollCurrentAccount(upn, aadId)
                                                Log.d("MAM-Unenroll", "Unenroll called")
                                            } else {
                                                Log.w(
                                                    "MAM-Unenroll",
                                                    "UPN が取得できず、Unenroll をスキップ"
                                                )
                                            }
                                        } catch (e: Exception) {
                                            unenrollError = e
                                            Log.e("MAM-Unenroll", "Unenroll Error: ${e.message}", e)
                                        }

                                        // 2. MSAL Sign-out（コールバックは MSAL 側から呼ばれる）
                                        try {
                                            AuthClient.signOut(activity)
                                            Log.d("MSAL", "Signed out")
                                        } catch (e: Exception) {
                                            signOutError = e
                                            Log.e("MSAL", "SignOut Error: ${e.message}", e)
                                        }

                                        // UI 状態更新
                                        isSignedIn = false
                                        signedInUser = null

                                        // Snackbar でどちらが失敗したか通知
                                        when {
                                            signOutError != null && unenrollError != null -> {
                                                snackbarHostState.showSnackbar(
                                                    "Sign-out & Unenroll 両方失敗: ${signOutError.message}, ${unenrollError.message}"
                                                )
                                            }

                                            signOutError != null -> snackbarHostState.showSnackbar("Sign-out 失敗: ${signOutError.message}")
                                            unenrollError != null -> snackbarHostState.showSnackbar(
                                                "Unenroll 失敗: ${unenrollError.message}"
                                            )

                                            else -> snackbarHostState.showSnackbar("Signed out & unenrolled")
                                        }
                                    }
                                }
                            ) { Text("Sign out") }
                        }
                        // 認証済み表示
                        if (isSignedIn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Account: ${signedInUser ?: "(unknown)"}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))


                        // ===== 以降は既存のエディタ UI =====
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it.ifBlank { "memo.txt" } },
                            label = { Text("ファイル名 (.txt)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("テキストを入力") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            minLines = 10
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showFilePicker = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("開く") }
                            Button(
                                onClick = {
                                    val ctx = context
                                    coroutineScope.launch {
                                        try {
                                            val safeName = fileName.trim().ifBlank { "memo.txt" }
                                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                            withContext(Dispatchers.IO) {
                                                File(ctx.filesDir, safeName).writeText(text.text)
                                            }
                                            fileList = withContext(Dispatchers.IO) {
                                                ctx.filesDir.listFiles()
                                                    ?.filter { it.isFile && it.name.endsWith(".txt", true) }
                                                    ?.map { it.name }
                                                    ?.sorted()
                                                    ?: emptyList()
                                            }
                                            snackbarHostState.showSnackbar("保存しました: $safeName")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("保存に失敗: ${e.message}")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (fileList.contains(fileName)) "上書き保存" else "保存(新規)")
                            }
                            OutlinedButton(
                                onClick = { (context.findActivity() as? MainActivity)?.sharePlainText(fileName, text.text) },
                                modifier = Modifier.weight(1f)
                            ) { Text("共有") }
                        }
                        if (showFilePicker) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("保存済みファイル", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                            ) {
                                items(fileList) { name ->
                                    ListItem(
                                        headlineContent = { Text(name) },
                                        modifier = Modifier.clickable {
                                            showFilePicker = false
                                            fileName = name
                                            val ctx = context
                                            coroutineScope.launch {
                                                try {
                                                    val content = withContext(Dispatchers.IO) {
                                                        File(ctx.filesDir, name).readText()
                                                    }
                                                    text = TextFieldValue(content)
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar("読み込み失敗: ${e.message}")
                                                }
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
