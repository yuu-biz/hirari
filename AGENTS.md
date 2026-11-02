# AGENTS.md

Hirari は、自炊した本（主に漫画）の画像書庫を読む Android アプリです。このファイルは、このリポジトリで作業する人とコーディングエージェントのための約束事をまとめたものです。

## 状態

`docs/spec.md` のマイルストーン M0（骨組みとライセンスの仕組み）が終わりました。次は M1（ローカルの ZIP と画像フォルダを読む）です。

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
