# AGENTS.md

Hirari は、自炊した本（主に漫画）の画像書庫を読む Android アプリです。このファイルは、このリポジトリで作業する人とコーディングエージェントのための約束事をまとめたものです。

## 状態

`docs/spec.md` の 9 章のマイルストーンは、どれも終わっていません。実機や NAS が要る確認が残っているか、コードが足りないためです。完了条件の分類と根拠は、9 章の「確認の状況」にあります。API 37 と API 28 のエミュレータでは、端末上の試験がすべて通りました。

- M0（骨組みとライセンスの仕組み）: コード完了。GitHub の CI での確認が 1 件残ります（`check` が通ることと、許可外の依存で失敗することを、手動実行で見ます）。
- M1（ローカルの ZIP と画像フォルダを読む）: 機能は動きます。ただし P1〜P4 を実際のリーダーで計るトレース区間とベンチマークが未作成です。実機での確認が 3 件残ります（P1〜P4、G2 の片手の手動試験、Q1 の計り直し）。
- M2（ライブラリ、履歴、しおり、サムネイル）: コード未完です。画像リーダーのしおりの追加と一覧が使えません。実機での確認が 3 件残ります（1000 冊のフォルダの P7 と P8、ファイル管理アプリから cbz を開く、書き出し、データの消去、読み込みの順）。
- M3（RAR、7z、PDF とすべての画像形式）: コード完了、確認が 3 件残ります（fuzz を延べ 24 時間、幅 800 で高さ 50,000 の画像のスクロールと P10 を実機で、圧縮した RAR 5 と RAR 4 の PPMd の実データ）。
- M4（SMB）: コード未完です。「端末に保存」がありません。実機と NAS での確認が 2 件残ります（基準 NAS の P4、読書中に Wi-Fi を切る）。
- M5（テキストリーダー）: コード完了、実機での確認が 1 件残ります（P9）。
- M6（残りのリーダー機能と仕上げ）: コード未完です。息での操作、操作ガイド、範囲を選んで表紙にする画面、縦の連続スクロール、アクセシビリティの点検、(要検証) の解消がありません。実機での確認が 2 件残ります（`coldStart` が 600 ms 以内、低位の確認端末と折りたたみ端末での点検）。

残りの端末での確認を動かすコマンドは次のとおりです。端末は `ANDROID_SERIAL` で 1 台に絞ります。

- 端末上の試験のすべて（API ごとに実行）: `./gradlew :core:archive:connectedDebugAndroidTest :core:image:connectedDebugAndroidTest :feature:text:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
- P9: `./gradlew :feature:text:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.feature.text.TextOnDeviceTest#aMegabyteTextReachesItsFirstPageWithTheSystemFontAndDrawsInk"` を流し、`adb logcat -d -s TextOnDeviceTest` で `P9 on this device` の行を読みます。
- P6（`coldStart`）と Q1 の計り直し: 下の表の起動の計測のコマンドに `-Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.StartupBenchmark` か `...RenderSurfaceBenchmark` を足します。
- fuzz の延べ 24 時間: GitHub の Actions で `fuzz.yml` を手動実行します。
- モンキー試験: 下の表のコマンドです。API 37 のエミュレータで 10,000 イベントが、落ち 0 件、ANR 0 件で終わっています。
- P1〜P4、P7、P8、P10、G2、NAS、Wi-Fi、ファイル管理アプリ、折りたたみ端末は、計るコマンドがありません。P1〜P4 はトレース区間の追加から始めます。そのほかは `docs/spec.md` の 5.2 節の計測方法に沿って、手で行います。

## ビルドと試験

必要なもの:

- JDK 21。`JAVA_HOME` で指します。
- Android SDK。`ANDROID_HOME` か、Git に入れない `local.properties` の `sdk.dir` で指します。
- SDK のパッケージは `platforms;android-37.0`、`build-tools;36.0.0`、`platform-tools`、`ndk;28.2.13676358`、`cmake;4.1.2` です。足りないパッケージはビルド中に取得せず、エラーで止まります（`android.builder.sdkDownload=false`）。`check` にも NDK が要ります。

コマンドはリポジトリの最上位で実行します。Windows では `./gradlew` を `.\gradlew.bat` に読み替えます。

| 目的 | コマンド |
|---|---|
| ビルド、単体試験、lint のすべて（CI と同じ） | `./gradlew check` |
| デバッグ版の APK | `./gradlew :app:assembleDebug` |
| 書庫の端末上の試験（接続した端末かエミュレータで動く） | `./gradlew :core:archive:connectedDebugAndroidTest` |
| コーパスの生成（`tools/corpus/build/corpus` に出る） | `./gradlew :tools:corpus:generateCorpus` |
| 起動の計測（実機で。エミュレータの値は使わない） | `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark` |
| Baseline Profile の生成（接続した端末かエミュレータで動く。`app/src/main/generated/baselineProfiles/` に出る） | `./gradlew :app:generateBaselineProfile` |
| 端末の fuzz（接続した端末かエミュレータで。`--args` の `--minutes` が時間、`--seed` が種） | `./gradlew :tools:fuzz:fuzz -Pandroid.injected.build.abi=x86_64 --args="--minutes 10"` |
| モンキー試験（デバッグ版の APK とコーパスの本を端末に入れて実行する） | `./gradlew :tools:fuzz:monkey -Pandroid.injected.build.abi=x86_64 --args="--events 10000"` |
| ホストの fuzz（clang と libFuzzer が要る。Linux で動かす） | `bash tools/fuzz/native/run.sh archive 600` か `jxl 600` |

- モジュールどうしの依存は `docs/spec.md` の 8.2 節の表に従います。表にない依存を足すと、`build-logic` の検査で設定の段階で失敗します。
- `check` はライセンスも検査します。Maven の依存は Licensee が、`build-logic` の許可リスト（`Licenses.kt`）と照らします。`third_party/` の下のディレクトリは、`verifyThirdParty` が `third_party/manifest.toml` と照らします。
- ライセンスの検査が効くことを CI で確かめるときは、捨てブランチで許可外の依存（例えば LGPL の `org.mariadb.jdbc:mariadb-java-client`）を `:app` に足し、そのブランチで CI を手動実行して失敗を見ます。確かめたらブランチを消します。
- Baseline Profile の生成は、端末のフォルダを選ぶ画面（システムの DocumentsUI）を UiAutomator で操作します。API 33 以上の端末かエミュレータで、root なしで動きます。エミュレータで流すときは `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR` を足します。生成物は端末の種類によらないので、エミュレータで作ったものをコミットしてかまいません。
- エミュレータは PC のメモリを大きく使います。端末上の試験は必要なときに 1 台ずつ、対象の試験だけを流します。挙動はできるだけ JVM の単体試験で確かめます。
- 端末を 2 台以上つないでいるときは、`ANDROID_SERIAL` で使う 1 台を指してから端末を使うタスクを動かします。`connected...` のタスクは、つないでいる全台にアプリを入れ直して動かすので、別の作業が使っている端末のアプリを書き換えます。起動の計測は、アプリの中の `androidx.profileinstaller` の受信部へ shell から放送を送ります。その端末にデバッグ版が入っていると、受信部がメインスレッドでディスクを読み、StrictMode（`docs/spec.md` の 6.3 節）でプロセスが止まります。モンキー試験はこれを落ちとして数えます。
- CI（`.github/workflows/ci.yml`）は、`v` で始まるタグの push と手動実行のときだけ動きます。
- fuzz とモンキー試験は、`.github/workflows/fuzz.yml` を手動で実行して回します（毎晩は回しません）。延べ 24 時間は実行ごとの時間の合計で、実行ごとに時間の記録（`fuzz-time-log` の成果物）へ足されます。詳しくは `docs/spec.md` の 6.2 節です。

## 要求は仕様書が決める

- 挙動の唯一の出所は `docs/spec.md` です。
- 仕様にない挙動を足すときや、仕様と違う挙動にするときは、先に `docs/spec.md` を直します。コードと同じコミットで直しても構いません。
- 仕様の (要検証) と未決事項（10 章）を決めたときは、決めた内容と根拠を仕様に書きます。
- 他のアプリのソースコードを読んで写したり、別の言語に書き換えて取り込んだりしません。挙動の根拠は仕様書と公開されている標準や API の文書だけです。

## 依存とライセンス

- 配布物に入れてよいライセンスは、Apache-2.0、MIT、BSD-2-Clause、BSD-3-Clause、ISC、Zlib、0BSD、bzip2-1.0.6、Unicode-3.0 です。書体だけ OFL-1.1 も使えます。出所が明らかな public domain のコードも、`third_party/` のネイティブのソースに限って使えます（`docs/spec.md` の 8.5 節）。
- GPL（全版）、LGPL、AGPL、SSPL、RAR の公式展開ソースのライセンス、ライセンスが不明なものは使いません。これらのソースから移したコードやアルゴリズムの書き写しも入れません。
- 依存を足すときは、推移的な依存とネイティブの依存も含めてライセンスを確かめます。確かめた結果は `docs/spec.md` の 8.5 節の表に書きます。

## 技術の前提

- Kotlin、Jetpack Compose、Material 3 を使います。
- minSdk は 28、targetSdk は最新の安定版です。
- モジュール構成とスレッドモデルは `docs/spec.md` の 8 章に従います。

## コミット

- 1 コミットは 1 つの目的にし、ビルドと試験が通る状態で積みます。
- コミットメッセージは英語の命令形で、1 行目を 72 文字以内にします。
- 個人の環境のパス、資格情報、署名鍵はコミットしません。
