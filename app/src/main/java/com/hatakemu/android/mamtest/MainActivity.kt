@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hatakemu.android.mamtest

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * シンプルなテキストエディター（SAF/OneDrive なし）:
 * - 入力/コピー&ペースト（TextField 標準）
 * - アプリ内ストレージ（filesDir）へ保存/上書き
 * - 保存済みファイル一覧から読み込み
 * - 共有（ACTION_SEND）
 *
 * 権限不要。後で MSAL + Graph の実装へ差し替えやすい構成。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EditorScreen(
                    listFiles = { listInternalFiles() },
                    readFile = { name -> readFromInternalFile(name) },
                    writeFile = { name, content -> writeToInternalFile(name, content) },
                    shareText = { subject, body -> sharePlainText(subject, body) }
                )
            }
        }
    }

    /** filesDir 内の .txt 一覧を取得 */
    private suspend fun listInternalFiles(): List<String> = withContext(Dispatchers.IO) {
        filesDir
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.name }
            ?.toList()
            ?: emptyList()
    }

    /** UTF-8 で読み込み（無ければ空文字） */
    private suspend fun readFromInternalFile(fileName: String): String = withContext(Dispatchers.IO) {
        val file = File(filesDir, fileName)
        if (!file.exists()) return@withContext ""
        file.readText(charset = StandardCharsets.UTF_8)
    }

    /** UTF-8 で保存/上書き（ファイル名の安全化含む） */
    private suspend fun writeToInternalFile(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val safe = fileName.trim().ifBlank { "memo.txt" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = File(filesDir, safe)
        file.writeText(content, charset = StandardCharsets.UTF_8)
    }

    /** テキスト共有（OneDrive/メール/Teams 等へ渡せる） */
    private fun sharePlainText(subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, "Share text"))
    }
}

@Composable
private fun EditorScreen(
    listFiles: suspend () -> List<String>,
    readFile: suspend (String) -> String,
    writeFile: suspend (String, String) -> Unit,
    shareText: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(TextFieldValue("")) }
    var fileName by remember { mutableStateOf("memo.txt") }
    var currentFile by remember { mutableStateOf<String?>(null) }

    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var showFilePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 初期ロード：保存済みファイル一覧
    LaunchedEffect(Unit) {
        files = listFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MAM Test Editor") }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(12.dp)
                .fillMaxSize()
        ) {
            // ファイル名
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it.ifBlank { "memo.txt" } },
                label = { Text("ファイル名 (.txt)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // 本文（標準のコピー＆ペーストが利用可能）
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("テキストを入力") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                minLines = 12
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 開く：アプリ内ファイル一覧
                Button(
                    onClick = { showFilePicker = true },
                    modifier = Modifier.weight(1f)
                ) { Text("開く") }

                // 保存：新規/上書き（IOは coroutine で実行し、そこで try-catch）
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val name = fileName.trim().ifBlank { "memo.txt" }
                                writeFile(name, text.text)
                                currentFile = name
                                files = listFiles() // 一覧更新
                                snackbarHostState.showSnackbar("保存しました: $name")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("保存に失敗: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (currentFile == null) "保存(新規)" else "上書き保存") }

                // 共有：他アプリへテキスト送信
                OutlinedButton(
                    onClick = { shareText(fileName, text.text) },
                    modifier = Modifier.weight(1f)
                ) { Text("共有") }
            }

            // 保存済みファイル一覧（ピッカー表示）
            if (showFilePicker) {
                Spacer(Modifier.height(12.dp))
                Text("保存済みファイル", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    items(files) { name ->
                        ListItem(
                            headlineContent = { Text(name) },
                            modifier = Modifier
                                .clickable {
                                    showFilePicker = false
                                    currentFile = name
                                    fileName = name
                                    scope.launch {
                                        try {
                                            val content = readFile(name)
                                            text = TextFieldValue(content)
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("読み込み失敗: ${e.message}")
                                        }
                                    }
                                }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}