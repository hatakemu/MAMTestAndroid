# Microsoft Intune MAM Test アプリ セットアップガイド

本ガイドでは、GitHub からダウンロードした MAMTest プロジェクトを自分の環境でビルド・実行するための手順を説明します。

## 前提条件

- **Windows 10/11**: PowerShell 5.1 以降
- **Android Studio**: Arctic Fox 以降
- **JDK**: 11 以降 (Android Studio に同梱されているものを推奨)
- **Android SDK**: API Level 26 以降
- **Git for Windows**: コマンドライン操作用
- **Azure AD テナント**: Microsoft Intune ライセンス付き
- **Azure AD アプリ登録**: Mobile Application Management 用

## 1. プロジェクトのダウンロード

```powershell
git clone https://github.com/hatakemu/MAMTestAndroid.git
Set-Location MAMTestAndroid
```

## 2. 環境固有設定の変更

### 2.1 Azure AD テナント設定

**ファイル**: `app/src/main/java/com/hatakemu/android/mamtest/config/AppConfig.kt`

```kotlin
object AppConfig {
    // === 自分のテナントIDに変更 ===
    const val TENANT_ID: String = "YOUR_TENANT_ID_HERE"
    const val TENANT_AUTHORITY: String = "https://login.microsoftonline.com/$TENANT_ID"

    // === MAM サインイン スコープ（通常変更不要）===
    const val MAM_SIGNIN_SCOPE: String = "https://msmamservice.api.application/.default"
}
```

**設定値の取得方法**:
1. [Azure Portal](https://portal.azure.com) にアクセス
2. 「Azure Active Directory」→「概要」
3. 「テナント ID」をコピーして `YOUR_TENANT_ID_HERE` に貼り付け

### 2.2 Azure AD アプリ登録の作成

1. [Azure Portal](https://portal.azure.com) で「アプリの登録」→「新規登録」
2. 以下を設定:
   - **名前**: `MAMTest` (任意)
   - **サポートされているアカウントの種類**: 「この組織のディレクトリのみのアカウント」
   - **リダイレクト URI**: 「パブリック クライアント (モバイルとデスクトップ)」
3. 「登録」をクリック
4. 「アプリケーション (クライアント) ID」をメモ

### 2.3 MSAL設定ファイルの更新

**ファイル**: `app/src/debug/res/raw/msal_config.json`

```json
{
  "client_id": "YOUR_CLIENT_ID_HERE",
  "authorization_user_agent": "DEFAULT",
  "redirect_uri": "msauth://YOUR_PACKAGE_NAME/YOUR_DEBUG_SIGNATURE_HASH",
  "broker_redirect_uri_registered": true,
  "account_mode": "SINGLE",
  "authorities": [
    {
      "type": "AAD",
      "audience": {
        "type": "AzureADMyOrg",
        "tenant_id": "YOUR_TENANT_ID_HERE"
      }
    }
  ]
}
```

**ファイル**: `app/src/release/res/raw/msal_config.json`

```json
{
  "client_id": "YOUR_CLIENT_ID_HERE",
  "authorization_user_agent": "DEFAULT",
  "redirect_uri": "msauth://YOUR_PACKAGE_NAME/YOUR_RELEASE_SIGNATURE_HASH",
  "broker_redirect_uri_registered": true,
  "account_mode": "SINGLE",
  "authorities": [
    {
      "type": "AAD",
      "audience": {
        "type": "AzureADMyOrg",
        "tenant_id": "YOUR_TENANT_ID_HERE"
      }
    }
  ]
}
```

### 2.4 パッケージ名の変更（任意）

自分独自のパッケージ名を使用したい場合:

1. **`app/build.gradle.kts`** を編集:
```kotlin
android {
    namespace = "com.yourcompany.android.mamtest"
    defaultConfig {
        applicationId = "com.yourcompany.android.mamtest"
        // ...
    }
}
```

2. **AndroidManifest.xml** を編集:
```xml
<data
    android:scheme="msauth"
    android:host="com.yourcompany.android.mamtest"
    android:path="${redirectPath}" />
```

3. **パッケージ構造をリファクタリング**:
   - Android Studio で `com.hatakemu.android.mamtest` を右クリック
   - 「Refactor」→「Rename」で新しいパッケージ名に変更

### 2.5 署名ハッシュの取得と設定

#### デバッグ用署名ハッシュ

```powershell
# デバッグキーストアのSHA1/SHA256ハッシュを取得
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android | Select-String "SHA1|SHA256"
```

#### リリース用キーストアの作成

```powershell
# リリース用キーストアを作成
keytool -genkey -v -keystore release.keystore -alias key0 -keyalg RSA -keysize 2048 -validity 10000
```

**リリース用署名ハッシュ**:
```powershell
# リリースキーストアのハッシュを取得
keytool -list -v -keystore "C:\path\to\your\release.keystore" -alias key0 | Select-String "SHA1|SHA256"
```

#### build.gradle.kts の署名設定更新

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("C:\\path\\to\\your\\release.keystore")
        storePassword = System.getenv("AND_REL_KEYSTORE_PASS") ?: "YOUR_KEYSTORE_PASSWORD"
        keyAlias = "key0"
        keyPassword = System.getenv("AND_REL_KEY_PASS") ?: "YOUR_KEY_PASSWORD"
    }
}

buildTypes {
    getByName("debug") {
        manifestPlaceholders.put("redirectPath", "/YOUR_DEBUG_HASH")
    }
    getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        manifestPlaceholders.put("redirectPath", "/YOUR_RELEASE_HASH")
        // ...
    }
}
```

### 2.6 Azure AD アプリのリダイレクト URI 設定

1. Azure Portal の「アプリの登録」で作成したアプリを選択
2. 「認証」→「プラットフォームを追加」→「Android」
3. 以下を入力:
   - **パッケージ名**: `com.yourcompany.android.mamtest`
   - **署名ハッシュ**: 上記で取得したSHA1ハッシュ（コロンなし）

## 3. Microsoft Intune MAM SDK の設定

### 3.1 MAM ポリシーの有効化

1. [Microsoft Endpoint Manager admin center](https://endpoint.microsoft.com/) にアクセス
2. 「アプリ」→「アプリ保護ポリシー」→「作成ポリシー」→「Android」
3. 以下を設定:
   - **名前**: `MAMTest Policy`
   - **対象アプリ**: カスタムアプリとして追加
   - **アプリ パッケージ ID**: `com.yourcompany.android.mamtest`

### 3.2 対象ユーザーの設定

1. 作成したポリシーで「割り当て」を選択
2. 「含めるグループを選択」でテストユーザーまたはグループを追加

## 4. ビルドと実行

### 4.1 プロジェクトの同期

```powershell
# Android Studio で以下を実行、またはPowerShellから実行
.\gradlew.bat sync
```

### 4.2 デバッグビルド

```powershell
.\gradlew.bat assembleDebug
```

### 4.3 リリースビルド

```powershell
# 環境変数を設定（任意）
$env:AND_REL_KEYSTORE_PASS = "your_keystore_password"
$env:AND_REL_KEY_PASS = "your_key_password"

.\gradlew.bat assembleRelease
```

## 5. 動作確認

1. アプリを起動
2. 「Sign in」ボタンをタップ
3. Azure AD 認証が成功することを確認
4. MAM 登録が完了することを確認
5. テキスト編集機能が正常に動作することを確認

## 6. トラブルシューティング

### よくある問題

#### MSAL認証エラー
- `msal_config.json` の `client_id` と `tenant_id` を確認
- Azure AD アプリのリダイレクト URI 設定を確認
- 署名ハッシュが正しく設定されているか確認

#### MAM登録エラー
- Microsoft Intune ライセンスが有効か確認
- MAM ポリシーが正しく設定されているか確認
- ユーザーがポリシーの対象に含まれているか確認

#### ビルドエラー
- Android SDK とビルドツールが最新か確認
- `gradle.properties` の設定を確認
- キーストアのパスとパスワードが正しいか確認

### ログの確認

```powershell
# Android Studio の Logcat で以下のタグを監視
adb logcat -s MSAL MAM-SDK MAM-Enroll MAM-Token

# または PowerShell で特定のタグをフィルタリング
adb logcat | Select-String "MSAL|MAM-SDK|MAM-Enroll|MAM-Token"
```

## 7. 本番環境への展開

1. リリース用キーストアを安全な場所に保管
2. 環境変数またはCI/CDシステムでパスワードを管理
3. ProGuard/R8 の設定を適切に行う
4. セキュリティテストを実施

## 参考資料

- [Microsoft Authentication Library (MSAL) for Android](https://docs.microsoft.com/en-us/azure/active-directory/develop/msal-android-overview)
- [Microsoft Intune App SDK for Android](https://docs.microsoft.com/en-us/mem/intune/developer/app-sdk-android)
- [Azure AD アプリ登録](https://docs.microsoft.com/en-us/azure/active-directory/develop/quickstart-register-app)