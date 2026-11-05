# 公開の手順

Hirari 1.0 は、GitHub Releases で APK 1 個を配布します。Google Play と AAB での配布は行いません。APK は `arm64-v8a`、`armeabi-v7a`、`x86_64` をすべて含みます。

## 版番号

`app/build.gradle.kts` の `versionName` は、1.0.0 からセマンティックバージョニングに従います。リリース候補は `X.Y.Z-rc.N` とします。`versionCode` は候補を含め、配布のたびに増やす 1 つの整数です。以前に配布した値を使い直しません。Android が更新の前後を判定するためです。

## 署名の準備

リリース用の鍵はリポジトリの外に保管します。別の安全な場所にバックアップを置きます。同じ署名で更新を続けるため、鍵とパスワードを失わないようにします。

リポジトリの最上位に `keystore.properties` を作ります。このファイルは Git の対象外です。鍵、パスワード、このファイルはコミットしません。`keystore.properties.example` がある場合は、それを雛形にします。

```properties
storeFile=/path/outside/repository/hirari-release.jks
storePassword=REPLACE_WITH_STORE_PASSWORD
keyAlias=hirari
keyPassword=REPLACE_WITH_KEY_PASSWORD
```

`storeFile` は鍵の絶対パスに置き換えます。Windows でも区切りに `/` を使えます。残りの値も実際の鍵に合わせます。

## ビルド

必要な JDK と SDK は [AGENTS.md](../AGENTS.md) に従います。リポジトリの最上位で実行します。

Windows の PowerShell では次を使います。

```powershell
.\gradlew.bat check :app:assembleRelease
```

Linux では次を使います。

```sh
./gradlew check :app:assembleRelease
```

失敗したら公開を止めます。APK は `app/build/outputs/apk/release/app-release.apk` に出ます。署名されていない APK は配布しません。release は R8 でコードとリソースを縮めます。公開前に、署名した APK で起動と本の読み込みを確かめます。実機の確認が残る項目は [仕様書の 9 章](spec.md#9-マイルストーン)と [確認の台帳](verification.md)で確認します。

## APK の検査

Android SDK Build Tools の `apksigner` と `zipalign` を使います。Windows では `apksigner.bat` と `zipalign.exe` を使えます。

```sh
apksigner verify -v --print-certs app/build/outputs/apk/release/app-release.apk
apksigner verify -v --print-certs --min-sdk-version 24 app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

最初の検査で v3 の検証が `true` になることを確かめます。`--min-sdk-version 24` を付けた検査では、v2 と v3 がともに `true` になることを確かめます。APK の最小 API は 28 のままです。この引数は v2 の検証結果も見るために使います。

署名証明書の SHA-256 は次の値でなければなりません。`apksigner` は小文字で区切りなしの値を出します。

```text
1116351eafe09b6f99bff277b959ec1700e12aed7bd1bfd463c534ba2d574441
```

`keytool` の表記では次の値です。

```text
11:16:35:1E:AF:E0:9B:6F:99:BF:F2:77:B9:59:EC:17:00:E1:2A:ED:7B:D1:BF:D4:63:C5:34:BA:2D:57:44:41
```

`zipalign` の検査は `Verification successful` で終わることを確かめます。署名と整列の検査が通らなければ公開しません。署名後に APK の中身を変えません。署名が無効になるためです。

## 配布物の保管

検査した APK を `Hirari-X.Y.Z.apk` に改名します。`X.Y.Z` は `versionName` と同じ値にします。候補なら `Hirari-X.Y.Z-rc.N.apk` になります。

改名した APK の SHA-256 を計算します。Windows の PowerShell では次を使います。

```powershell
Get-FileHash -Algorithm SHA256 .\Hirari-X.Y.Z.apk
```

Linux では次を使います。

```sh
sha256sum Hirari-X.Y.Z.apk
```

この値は APK のハッシュです。証明書のハッシュと混同しません。

`app/build/outputs/mapping/release/mapping.txt` を版ごとに保管します。R8 で縮めた版の障害を読み解くために必要です。次のビルドで上書きされる前に、対応する APK とともに保守者が保管します。Release には添付しません。

## GitHub で公開する

1. 版番号と変更履歴を更新します。確認を終えた版をコミットし、push します。
2. そのコミットに `vX.Y.Z` のタグを付け、push します。候補は `vX.Y.Z-rc.N` とします。タグの push で CI が始まります。
3. タグの CI がすべて成功したことを確認します。失敗している間は Release を作りません。
4. 同じコミットから作り、検査した APK を使います。GitHub でそのタグの Release を作ります。候補はプレリリースにします。
5. 添付する成果物は `Hirari-X.Y.Z.apk` だけにします。候補は候補の名前を使います。`mapping.txt`、鍵、設定ファイルは添付しません。GitHub が自動で付けるソースアーカイブは APK ではありません。
6. 説明に、利用者に見える変更、APK の SHA-256、署名証明書の SHA-256 を書きます。次の確認コマンドと、デバッグ版からの移行手順も書きます。

```sh
apksigner verify --print-certs Hirari-X.Y.Z.apk
```

`0.0.1-debug.N` は開発用の鍵なので、1.0 を上書きできません。説明には次の順序を記載します。

1. 古い版で「書き出し」を開き、「読書位置、しおり、履歴も含める」を選んで保存します。
2. 古い版をアンインストールし、1.0 をインストールします。
3. 保存した設定を読み込みます。
4. SAF のフォルダを選び直し、SMB のパスワードを入力し直します。
5. 履歴としおりにある各本を、フォルダの一覧から一度開きます。本の索引は書き出しに含まれないため、それまでは「（索引にない本）」と出ます。
6. 書体を取り込み直します。

場所とその権限、お気に入り、ピン留め、選んだ表紙、取り込んだ書体、キャッシュは書き出しで引き継げないことも記載します。SMB のホスト、ポート、共有名、フォルダは含まれます。詳しい案内は [README](../README.md#001-のデバッグ版から移る) にあります。

## Android の開発者確認

保守者は Android Developer Console で本人確認とパッケージ名の登録を行います。登録に使うパッケージ名は `io.github.yuubiz.hirari` です。署名証明書の SHA-256 は、この文書の値を使います。APK 自体のハッシュではありません。所有の確認には、リリース用の鍵で署名した APK を使います。

登録するときに [Android の公式ガイド](https://developer.android.com/developer-verification/guides)で最新の要件を確かめます。必要な書類と手順は変わり得るためです。
