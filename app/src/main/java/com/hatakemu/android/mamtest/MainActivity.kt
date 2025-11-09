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
import com.hatakemu.android.mamtest.auth.AuthClient
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.Logger
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.AcquireTokenSilentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** LocalContext から現在の Activity を安全に取得 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Silent 取得時のフォールバック用（tenant 固定 Authority） */
private const val TENANT_AUTHORITY =
    "https://login.microsoftonline.com/516f6912-3d81-47b6-8866-20353e6bfdda"

/** Stage 4 の対話同意（interactive）で使う MAM リソース */
private const val MAM_RESOURCE_ID = "https://manage.microsoft.com"

class MainActivity : ComponentActivity() {

    /** 共有（既存） */
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
     * MAM の登録解除（MSAL には触れない）
     * @param upn  必須
     * @param aadId 任意（あればオーバーロードで渡す）
     */
    private fun unenrollCurrentAccount(upn: String, aadId: String?) {
        val mgr = com.microsoft.intune.mam.client.app.MAMComponents.get(
            com.microsoft.intune.mam.policy.MAMEnrollmentManager::class.java
        )
        if (mgr == null) {
            Log.w("MAM-Unenroll", "MAMEnrollmentManager is null")
            return
        }
        try {
            if (aadId != null) {
                mgr.unregisterAccountForMAM(upn, aadId)
            } else {
                mgr.unregisterAccountForMAM(upn)
            }
            Log.i("MAM-Unenroll", "unregisterAccountForMAM called for $upn ($aadId)")
        } catch (e: Exception) {
            Log.e("MAM-Unenroll", "Failed to unregister MAM: ${e.message}", e)
            throw e
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MSAL ログ設定（デバッグ用途）
        Logger.getInstance().setEnableLogcatLog(true)
        Logger.getInstance().setLogLevel(Logger.LogLevel.VERBOSE)
        Logger.getInstance().setEnablePII(true)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val activity = remember(context) { context.findActivity() }
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                // Editor UI 状態（既存）
                var text by remember { mutableStateOf(TextFieldValue("")) }
                var fileName by remember { mutableStateOf("memo.txt") }
                var fileList by remember { mutableStateOf(listOf<String>()) }
                var showFilePicker by remember { mutableStateOf(false) }

                // 認証状態（UI は MSAL currentAccount と同期）
                var signedInUser by remember { mutableStateOf<String?>(null) }
                var isSignedIn by remember { mutableStateOf(false) }

                // Graph のデフォルト（.default）
                val graphDefaultScopes = listOf("https://graph.microsoft.com/.default")
                // Interactive は MAM リソースの .default
                val mamDefaultScopes = listOf("$MAM_RESOURCE_ID/.default")

                // 起動時：ファイル一覧 + currentAccount 確認
                LaunchedEffect(Unit) {
                    // ファイル一覧
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
                            // --- Sign in（interactive は MAM リソース） ---
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
                                                isSignedIn = true
                                                signedInUser = triple.first
                                                snackbarHostState.showSnackbar("すでにサインイン済みです")
                                            } else {
                                                AuthClient.signInInteractive(
                                                    activity = activity,
                                                    scopes = mamDefaultScopes.toTypedArray(),
                                                    callback = object : AuthenticationCallback {
                                                        override fun onSuccess(result: IAuthenticationResult) {
                                                            signedInUser = result.account?.username
                                                            isSignedIn = true
                                                            Log.d("MSAL", "SignIn Success: $signedInUser")
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    "Signed in: ${signedInUser ?: "unknown"}"
                                                                )
                                                            }
                                                        }
                                                        override fun onError(exception: MsalException) {
                                                            Log.e(
                                                                "MSAL",
                                                                "SignIn Error: ${exception.errorCode} - ${exception.message}"
                                                            )
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    "Sign-in 失敗: ${exception.errorCode}"
                                                                )
                                                            }
                                                        }
                                                        override fun onCancel() {
                                                            Log.w("MSAL", "SignIn Cancelled by user")
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

                                        // 1. MAM Unenroll（MSAL に触れない）
                                        try {
                                            if (upn != null) {
                                                unenrollCurrentAccount(upn, aadId)
                                                Log.d("MAM-Unenroll", "Unenroll called")
                                            } else {
                                                Log.w("MAM-Unenroll", "UPN が取得できず、Unenroll をスキップ")
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
                                            signOutError != null -> {
                                                snackbarHostState.showSnackbar("Sign-out 失敗: ${signOutError.message}")
                                            }
                                            unenrollError != null -> {
                                                snackbarHostState.showSnackbar("Unenroll 失敗: ${unenrollError.message}")
                                            }
                                            else -> {
                                                snackbarHostState.showSnackbar("Signed out & unenrolled")
                                            }
                                        }
                                    }
                                }
                            ) { Text("Sign out") }

                            // --- MAM Enroll（既存名: Dump tokens & Enroll → MAM Enroll） ---
                            Button(
                                enabled = isSignedIn,
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val msal = AuthClient.getOrCreate(applicationContext)
                                            // Graph silent（.default）。authority は account.authority を優先
                                            val (account, graphToken) = withContext(Dispatchers.IO) {
                                                val acc = msal.currentAccount?.currentAccount
                                                    ?: throw IllegalStateException("MSAL current account is null")
                                                val effectiveAuthority =
                                                    acc.authority?.takeIf { it.isNotBlank() } ?: TENANT_AUTHORITY
                                                val silent = AcquireTokenSilentParameters.Builder()
                                                    .forAccount(acc)
                                                    .fromAuthority(effectiveAuthority)
                                                    .withScopes(graphDefaultScopes)
                                                    .build()
                                                val res = msal.acquireTokenSilent(silent)
                                                val tok = res?.accessToken
                                                    ?: throw IllegalStateException("Graph token is null")
                                                acc to tok
                                            }
                                            // デバッグ出力（本番はマスク推奨）
                                            Log.i(
                                                "Sign-in Token",
                                                "authority=${account.authority ?: TENANT_AUTHORITY} " +
                                                        "scope=${graphDefaultScopes.joinToString()} token=${graphToken}"
                                            )
                                            // Enroll（SDK 側の resourceId/.default 要求は MyMAMApp の Proxy が Silent で応答）
                                            val upn = signedInUser!!
                                            val aadId = account.id
                                            val res = MAMInterop.register(upn, aadId)
                                            snackbarHostState.showSnackbar("Enroll invoked: $res")
                                        } catch (e: Exception) {
                                            Log.e("MAM-Flow", "MAM Enroll failed: ${e.message}", e)
                                            snackbarHostState.showSnackbar("MAM Enroll 失敗: ${e.message}")
                                        }
                                    }
                                }
                            ) { Text("MAM Enroll") }
                        }

                        // 認証済み表示
                        if (isSignedIn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Account: ${signedInUser ?: "(unknown)"}",
                                style = MaterialTheme.typography.bodyMedium
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
