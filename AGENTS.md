# AGENTS.md

Hirari は、自炊した本（主に漫画）の画像書庫を読む Android アプリです。このファイルは、このリポジトリで作業する人とコーディングエージェントのための約束事をまとめたものです。

## 状態

`docs/spec.md` の 9 章のマイルストーンのうち、完了条件をすべて確認できたのは M0 だけです。ほかはコードが揃い、実機、NAS、実データ、人の手が要る確認が残っています。完了条件ごとの分類と根拠は、9 章の「確認の状況」にあります。仕様書の (要検証) は、`docs/verification.md` の台帳に 1 件ずつ載せています。仕様書を直したら、`bash tools/verification/list.sh --check` で台帳と件数が合うことを確かめます。

- M0（骨組みとライセンスの仕組み）: 完了。GitHub の CI で `check` が通ることと、許可外の依存で失敗することを確かめました。
- M1（ローカルの ZIP と画像フォルダを読む）: 機能は動きます。ただし P1〜P4 を実際のリーダーで計るトレース区間とベンチマークがありません。実機での確認が 3 件残ります（P1〜P4、進むときと戻るときの組み方の手動試験と G2 の片手の手動試験、Q1 と Q2 の計り直し）。
- M2（ライブラリ、履歴、しおり、サムネイル）: コード完了です。実機での確認が 3 件残ります（1000 冊のフォルダの P7 と P8、ファイル管理アプリから cbz を開く、書き出し、データの消去、読み込みの順）。
- M3（RAR、7z、PDF とすべての画像形式）: コード完了、確認が 3 件残ります（fuzz は延べ 24 時間のうち 0.30 時間、幅 800 で高さ 50,000 の画像のスクロールと P10 を実機で、圧縮した RAR 5 と RAR 4 の PPMd の実データ）。
- M4（SMB）: コード完了です。保存の取り消しに小さな穴が 1 つあります（サーバーが応答しないまま待っているとき、タイムアウトまで書きかけの `.part` を消しません）。実機と NAS での確認が 3 件残ります（完了条件の 2 件は、基準 NAS の P4 と、読書中に Wi-Fi を切ることです。もう 1 件は、端末に保存の保存先を選ぶ画面と実際の提供元です）。
- M5（テキストリーダー）: コード完了、実機での確認が 1 件残ります（P9）。
- M6（残りのリーダー機能と仕上げ）: コード完了です。実機での確認が 5 件残ります（`coldStart` が 600 ms 以内、息での操作の 10 回に 9 回、低位の確認端末での点検、折りたたみ端末での点検、実機の TalkBack での点検）。

端末上の試験とモンキー試験は、2026-11-03 に `77af990` の上で流して、すべて通りました（API 37 と API 28 のエミュレータ。結果の表は `docs/spec.md` 9 章の「確認の状況」）。縦の連続スクロール、息での操作、操作ガイド、端末に保存を含みます。モンキー試験は API 37 で 10,000 イベント、落ち 0 件、ネイティブの落ち 0 件、ANR 0 件でした。流し直すときは、端末を `ANDROID_SERIAL` で 1 台に絞ります。

- 端末上の試験のすべて（API 37 と API 28 のそれぞれで実行）: `./gradlew :core:archive:connectedDebugAndroidTest :core:image:connectedDebugAndroidTest :feature:text:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest`。`:feature:reader` と `:feature:library` に端末上の試験はありません。Gradle は端末への導入に失敗しても成功と出すことがあるので、各モジュールの `build/outputs/androidTest-results/connected/debug/` に結果の XML が出たことも見ます。
- モンキー試験（API 37 のエミュレータで 10,000 イベント。落ち 0 件、ANR 0 件を確かめる）: 下の表のモンキー試験のコマンドに `--serial emulator-5554` を足します。約 1 時間 23 分かかります。

残りの確認を動かすコマンドと手順は次のとおりです。

- P1〜P4: 実際のリーダーにトレース区間とベンチマークがあります（`ReaderBenchmark`）。下の表の P1 から P4 のコマンドで流します。測定は 5.1 節の中位の基準端末で、当面は 検証用の端末 A です。
- P6（`coldStart`）: `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.StartupBenchmark` を実機で流します。
- Q1 と Q2 の計り直し: 同じコマンドの `class=` を `io.github.yuubiz.hirari.benchmark.RenderSurfaceBenchmark` に替えて、実機で流します。
- P7: 1000 件のフォルダを選び、Perfetto で `Hirari.listFolder` のトレース区間を見ます。
- P8: `./gradlew :core:image:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailsDeviceTest"` を流し、`adb logcat -d -s ThumbnailsDeviceTest` で `P8:` の行を読みます。1000 冊のフォルダでの表示は目で見ます。
- P9: `./gradlew :feature:text:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.feature.text.TextOnDeviceTest#aMegabyteTextReachesItsFirstPageWithTheSystemFontAndDrawsInk"` を流し、`adb logcat -d -s TextOnDeviceTest` で `P9 on this device` の行を読みます。
- P10: `./gradlew :tools:corpus:generateStandardCorpus` で標準コーパスを作り、`./gradlew :tools:corpus:generateCorpus` で幅 800 で高さ 50,000 の画像 `tall.png` を含むコーパスを作ります。実機で最後まで読みながら、10 ページごとに `adb shell dumpsys meminfo io.github.yuubiz.hirari` を記録します。
- G2 の片手の手動試験: 4 章の操作表を片手で一巡します。めくり、ページ移動、組ずらし、しおり追加を含みます。見開きで、進むときは今のビューの後ろから、戻るときは今のビューの前から組むこと（`docs/spec.md` の 2.5 節）も、このとき手で確かめます。
- 折りたたみ端末の点検: 検証用の端末 B で、見開きと、折りたたみと展開での組み直しを見ます。
- 基準 NAS の P4: NAS の SMB の場所を登録し、一覧のタップから最初のページの描画までを計ります。NAS が手元にないときの試験には、`tools/smb-fixture` の Docker の Samba を使いますが、P4 の判定には使えません。
- 読書中に Wi-Fi を切る: SMB の本を読みながら Wi-Fi を切り、「再接続しています」が出てアプリが終了しないこと、準備済みのページが読めること、Wi-Fi を戻すと自動で再接続することを見ます。
- 端末に保存の保存先: SMB の場所のファイルとフォルダを、端末の保存領域、SD カード、クラウドの提供元に保存します。
- ファイル管理アプリから cbz を開く、書き出し、データの消去、読み込み: 実機の手動です。
- 息での操作の 10 回に 9 回: 設定の「息でめくる」をオンにし、静かな部屋で短く 10 回吹いて、9 回以上めくれることを見ます。倍率と下限は、ここで調整します。
- 実機の TalkBack の点検: `docs/verification.md` の A1〜A7 を順に行います。
- 低位の確認端末の点検: 当面は API 28 の AVD（メモリ 2 GB）で、端末上の試験のすべてと、`./gradlew :tools:fuzz:monkey -Pandroid.injected.build.abi=x86_64 --args="--events 10000 --standard tools/corpus/build/standard-corpus/standard.zip"` を流して、落ちないことを見ます。API 28 か 29 で RAM 3 GB の実機があれば、メモリ予算を P10 の手順で見ます。
- 圧縮した RAR 5 と RAR 4 の PPMd の実データ: 手元の書庫を端末の SAF で開き、結果を `docs/spec.md` の 3.3 節と 6.2 節に書きます。
- fuzz の延べ 24 時間: GitHub の Actions で `fuzz.yml` を手動実行します。`gh workflow run fuzz.yml -f minutes=240 -f host_minutes=30 -f monkey_events=0` を、前の実行が終わってから、あと 5 回ほど続けます（1 回に端末の fuzz が 240 分、ホストの fuzz が `archive` と `jxl` で 30 分ずつです）。合計はジョブの要約に出ます。
- そのほかの (要検証) の確かめ方は、`docs/verification.md` の台帳の「決める手段」の列にあります。

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
| 標準コーパス（`docs/spec.md` の 5.1 節）の生成。P1 から P4 と P10 の計測が読む（`tools/corpus/build/standard-corpus/standard.zip` に出る。約 350 MB） | `./gradlew :tools:corpus:generateStandardCorpus` |
| 標準コーパスを計測する端末に置く（1 度だけ。置いていなければ、計測は小さなページの代用品を作って動くが、5.2 節の目標とは比べられない） | `adb -s <serial> shell mkdir -p /sdcard/hirari-perf` のあと `adb -s <serial> push tools/corpus/build/standard-corpus/standard.zip /sdcard/hirari-perf/` |
| 起動の計測（P6。実機で。エミュレータの値は使わない） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.StartupBenchmark` |
| P1 めくりの遅れ（200 回 × 3 試行。実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ReaderBenchmark#p1TurnLatency` |
| P2 めくりのジャンク（100 回 × 3 試行。実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ReaderBenchmark#p2TurnJank` |
| P3 先読みしていないページへの移動（ページパネルで 50 回 × 3 試行。実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ReaderBenchmark#p3JumpLatency` |
| P4 本を開いてから最初のページ（ローカル。実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ReaderBenchmark#p4FirstPage` |
| P4 の SMB（サーバーが要る。`smbHost`、`smbShare`、`smbBook` を渡さなければ飛ばす。`smbPort`、`smbFolder`、`smbUser`、`smbPassword` は任意） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ServerBenchmark#p4FirstPageOnServer -Pandroid.testInstrumentationRunnerArguments.smbHost=<host> -Pandroid.testInstrumentationRunnerArguments.smbShare=<share> -Pandroid.testInstrumentationRunnerArguments.smbBook=standard.zip` |
| P7 1000 項目のフォルダの一覧（実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.LibraryBenchmark#p7ListThousandItems` |
| P8 サムネイル。生成（実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.LibraryBenchmark#p8CoversGenerated` |
| P8 サムネイル。ディスクのキャッシュから（実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.LibraryBenchmark#p8CoversFromStore` |
| P9 1 MB のテキストの最初のページ（実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.TextBenchmark#p9TextFirstPage` |
| P10 メモリ（標準コーパスを最後まで。10 ページごとの `dumpsys meminfo` は logcat のタグ `HirariBench` に出る。実機で） | `ANDROID_SERIAL=<serial> ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.ReaderBenchmark#p10Memory` |
| P11 SMB の場所の ZIP の表紙（要求の数とバイト数。`tools/smb-fixture` の Samba に対する JVM の試験で、端末は要らない。fixture を起こしていなければ飛ばす） | `bash tools/smb-fixture/fixture.sh up` のあと `./gradlew :core:archive:testDebugUnitTest --tests '*SmbCoverTest*' --rerun $(bash tools/smb-fixture/fixture.sh props)` |
| Baseline Profile の生成（接続した端末かエミュレータで動く。`app/src/main/generated/baselineProfiles/` に出る） | `./gradlew :app:generateBaselineProfile` |
| 端末の fuzz（接続した端末かエミュレータで。`--args` の `--minutes` が時間、`--seed` が種） | `./gradlew :tools:fuzz:fuzz -Pandroid.injected.build.abi=x86_64 --args="--minutes 10"` |
| モンキー試験（デバッグ版の APK とコーパスの本を端末に入れて実行する） | `./gradlew :tools:fuzz:monkey -Pandroid.injected.build.abi=x86_64 --args="--events 10000"` |
| ホストの fuzz（clang と libFuzzer が要る。Linux で動かす） | `bash tools/fuzz/native/run.sh archive 600` か `jxl 600` |

- モジュールどうしの依存は `docs/spec.md` の 8.2 節の表に従います。表にない依存を足すと、`build-logic` の検査で設定の段階で失敗します。
- `check` はライセンスも検査します。Maven の依存は Licensee が、`build-logic` の許可リスト（`Licenses.kt`）と照らします。`third_party/` の下のディレクトリは、`verifyThirdParty` が `third_party/manifest.toml` と照らします。
- ライセンスの検査が効くことを CI で確かめるときは、捨てブランチで許可外の依存（例えば LGPL の `org.mariadb.jdbc:mariadb-java-client`）を `:app` に足し、そのブランチで CI を手動実行して失敗を見ます。確かめたらブランチを消します。
- Baseline Profile の生成は、端末のフォルダを選ぶ画面（システムの DocumentsUI）を UiAutomator で操作します。API 33 以上の端末かエミュレータで、root なしで動きます。エミュレータで流すときは `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR` を足します。生成物は端末の種類によらないので、エミュレータで作ったものをコミットしてかまいません。
- 5.2 節の目標の計測は、上の `connectedBenchmarkAndroidTest` の行です。`ANDROID_SERIAL=<serial>` と `enabledRules=macrobenchmark` は全部の行に付けます（Windows の PowerShell では `$env:ANDROID_SERIAL="<serial>"` を先に置き、`./gradlew` を `.\gradlew.bat` に読み替えます）。**エミュレータの数値は目標の判定に使いません**（`docs/spec.md` の 5.1 節）。計測の仕組みが動くことを確かめるときだけ、エミュレータで `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR` を足し、数値は非公式と書き添えます。実機のコマンドには付けません。`turns`、`jumps`、`iterations` を `-Pandroid.testInstrumentationRunnerArguments.` で渡すと、回数を減らせます（動作の確認用。判定は既定の回数で行う）。計測は端末の共有ストレージの上位に `hirari-perf` から始まる名前のフォルダを作り、場所として加えます。毎回アプリのデータを消し、`connected...` のタスクがアプリを入れ直すので、**その実機の本アプリの設定、履歴、しおりは消えます**。区間の数は試行の数と合うはずです（`hirari.firstPageCount` と `hirari.textFirstPageCount` は 1 回の試行で 1）。0 なら、計る区間の終わりより前に区間が閉じなかったので、試験の待ち時間（`SETTLE_MS`）を伸ばします。場所を加える操作が止まったときは、メッセージの「the screen shows …」と、端末の `/sdcard/hirari-failure.png`（失敗した画面の写し）を見ます。フォルダ選択画面が最近使った場所で開く機種は、`hirari-perf` から 3 回戻る操作の内に見えないことがあります。
- エミュレータは `-gpu swiftshader_indirect` を付けて起動します（例: `emulator -avd <AVD> -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect`）。AVD の `hw.gpu.mode=auto` は、ウィンドウを出すとホストの GPU を選びます。API 28 の AVD をホストの GPU で動かすと、GL の翻訳が返すエラーで描画のスレッドが落ちます（`glTexSubImage2D` の `GL_INVALID_VALUE`、`glFinish` や `glViewport` の 0x506。システム UI も同じように落ちる）。これはアプリの退行ではありません。
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
