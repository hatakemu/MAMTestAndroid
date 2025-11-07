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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LocalContext から現在の Activity を安全に取得するユーティリティ。
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

class MainActivity : ComponentActivity() {

    // テキスト共有（他アプリへ）
    private fun sharePlainText(subject: String, body: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(sendIntent, "Share text"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MSAL のログ設定
        Logger.getInstance().setEnableLogcatLog(true)
        Logger.getInstance().setLogLevel(Logger.LogLevel.VERBOSE)
        Logger.getInstance().setEnablePII(true)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val activity = remember(context) { context.findActivity() }
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                // テキストエディタ状態
                var text by remember { mutableStateOf(TextFieldValue("")) }
                var fileName by remember { mutableStateOf("memo.txt") }
                var fileList by remember { mutableStateOf(listOf<String>()) }
                var showFilePicker by remember { mutableStateOf(false) }

                // 認証状態
                var signedInUser by remember { mutableStateOf<String?>(null) }

                // 要求スコープ
                val scopes = arrayOf("https://graph.microsoft.com/.default")

                // .txt ファイル一覧の取得
                LaunchedEffect(Unit) {
                    fileList = withContext(Dispatchers.IO) {
                        context.filesDir.listFiles()
                            ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
                            ?.map { it.name }
                            ?.sorted()
                            ?: emptyList()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("MAM Test Editor") },
                            actions = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                                    // Sign in
                                    Button(
                                        enabled = signedInUser == null,
                                        onClick = {
                                            if (activity == null) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Activity を取得できませんでした")
                                                }
                                                return@Button
                                            }

                                            if (signedInUser != null) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("すでにサインイン済みです")
                                                }
                                            } else {
                                                // suspend 関数はコルーチンから呼ぶ
                                                coroutineScope.launch {
                                                    try {
                                                        AuthClient.signInInteractive(
                                                            activity = activity,
                                                            scopes = scopes,
                                                            callback = object : AuthenticationCallback {
                                                                override fun onSuccess(result: IAuthenticationResult) {
                                                                    signedInUser = result.account?.username
                                                                    Log.d("MSAL", "SignIn Success: $signedInUser")
                                                                    // Snackbar はメインスレッドから
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
                                                    } catch (e: Exception) {
                                                        Log.e("MSAL", "signInInteractive 呼び出し失敗", e)
                                                        snackbarHostState.showSnackbar("Sign-in 呼び出しで例外: ${e.message}")
                                                    }
                                                }
                                            }
                                        }
                                    ) { Text("Sign in") }

                                    // Sign out
                                    OutlinedButton(
                                        onClick = {
                                            if (activity == null) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Activity を取得できませんでした")
                                                }
                                                return@OutlinedButton
                                            }

                                            // suspend 関数はコルーチンから呼ぶ（コールバックは渡さない）
                                            coroutineScope.launch {
                                                try {
                                                    AuthClient.signOut(activity)
                                                    signedInUser = null
                                                    Log.d("MSAL", "Signed out")
                                                    snackbarHostState.showSnackbar("Signed out")
                                                } catch (e: Exception) {
                                                    Log.e("MSAL", "SignOut Error: ${e.message}", e)
                                                    snackbarHostState.showSnackbar("Sign-out 失敗: ${e.message}")
                                                }
                                            }
                                        }
                                    ) { Text("Sign out") }
                                }
                            }
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        // サインイン済みユーザー名
                        if (signedInUser != null) {
                            Text("Account: $signedInUser", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // ファイル名
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it.ifBlank { "memo.txt" } },
                            label = { Text("ファイル名 (.txt)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // テキスト入力
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

                        // 操作ボタン
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                            Button(
                                onClick = { showFilePicker = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("開く") }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val safeName = fileName.trim().ifBlank { "memo.txt" }
                                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                            withContext(Dispatchers.IO) {
                                                File(context.filesDir, safeName).writeText(text.text)
                                            }
                                            fileList = withContext(Dispatchers.IO) {
                                                context.filesDir.listFiles()
                                                    ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
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
                                onClick = {
                                    (activity as? MainActivity)?.sharePlainText(fileName, text.text)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("共有") }
                        }

                        // ファイルピッカー
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
                                            coroutineScope.launch {
                                                try {
                                                    val content = withContext(Dispatchers.IO) {
                                                        File(context.filesDir, name).readText()
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
