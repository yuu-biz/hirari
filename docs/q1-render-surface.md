# Q1 リーダーの描画面の試作と計測

`docs/spec.md` の 8.4 節に従って、2 つの試作を作り、計測した。この文書は、何をどう計ったか、エミュレータでの数値、暫定の判断、実機で計り直す手順を記録する。Q2（画素形式）の材料も同じ計測で取った。

## 結論

- **Q1**。暫定で案 A（Compose の `Canvas` と `graphicsLayer`）とする。
- **Q2**。`HARDWARE` を第一候補のまま保つ。
- 数値はエミュレータのものであり、判定には使えない（5.1 節）。実機で計り直して確定する (要検証)。

## 試作

試作は使い捨てである。本物のリーダーは、判断が決まったらこの試作を消して作り直す。

| 案 | 場所 | 描き方 | 動かし方 |
|---|---|---|---|
| A | `feature/reader` の `prototype/ComposeSurface.kt` | `Canvas` で `drawImage` する。今のビューと入ってくるビューを別の層にする | `graphicsLayer` の移動と拡大。ビューが変わったときだけ描き直す |
| B | `feature/reader` の `prototype/ViewSurface.kt` | 独自の `View` の `onDraw`。ビューごとに `RenderNode` へ 1 回だけ記録する | `RenderNode` の移動と拡大。`onDraw` は 2 つのノードを描くだけ |

- B は `AndroidView` で Compose の画面に載せる。本物のリーダーも Compose の画面に載るからである。
- API 28 には公開の `RenderNode` がない。B は API 28 では、毎フレームでキャンバスを変換してビットマップを描く。この経路は計測していない。
- 2 つの案は、めくりの状態（`Pager`）と準備済みのビュー（`PageStore`）を共有する。違うのは描き方と動かし方だけである。
- ページは 1080 × 2400 の合成画像で、メモリの中で作る。書庫は読まない。先読みの窓は今のページの 1 つ前から 2 つ先までで、専用のスレッドで準備する。
- 操作は、左半分のタップで進む、右半分のタップで戻る、指で引くと次のビューが付いてくる、ピンチで拡大する、の 4 つである。めくり効果は「重ねる」の 150 ms で、右綴じとして次のビューが左から入る。
- 効果の進みは入力イベントの時刻から数える。効果の最初のフレームから次のビューが少し見える。
- 画素形式は 3 通りを切り替えられる。`HARDWARE` は準備のときに `Bitmap.copy(HARDWARE)` で GPU に上げる。`ARGB_8888` はソフトウェアのまま持ち、最初に描くフレームで RenderThread が GPU に上げる。`ARGB_8888` に `prepareToDraw` を足した形も比べた。

試作は debug と benchmark のビルドにだけ入る。release のビルドは `feature/reader` に依存せず、経路も持たない。開き方は 2 つある。

- ホームのメニューの「Q1 prototype」。
- `adb shell am start -n io.github.yuubiz.hirari/.MainActivity --es prototype view-hardware`。値は `<compose|view>-<hardware|argb8888|argb8888prepared>` である。

画面の上端のラベルを押すと、次の組み合わせに替わる。

## 計測するもの

`benchmark` モジュールの `RenderSurfaceBenchmark` で計る。テストは 8 つある。

- **めくり**。案 2 つ × 画素形式 3 つで 6 つ。左半分を 300 ms ごとに 100 回タップする。
- **ピンチ**。案 2 つで 2 つ。`HARDWARE` だけ。画面全体で 0.6 の広げと縮めを 5 組行う。

どのテストも 3 回繰り返す。各回の前にアプリを止めて開き直し、1 秒待ってから計る。コンパイルは `CompilationMode.Full()` である。

数値の意味は次のとおり。`TurnMetric` は Perfetto のトレースを SQL で読む独自の計測で、`benchmark/src/main/kotlin/.../TurnMetric.kt` にある。

| 名前 | 意味 | 対応する目標 |
|---|---|---|
| `turnShownMs` | タップのイベント時刻から、新しいビューを初めて描いたフレームが表示されるまで。終わりは、そのフレームの FrameTimeline の実際の区間（`actual_frame_timeline_slice`）の終わりである | P1 |
| `turnDrawMs` | タップのイベント時刻から、メインスレッドがそのフレームを記録し終えるまで | P1 の前半 |
| `turnRenderThreadMs` | そのフレームの RenderThread の `DrawFrames` 区間の長さ。`ARGB_8888` の GPU へのアップロードはここに入る | Q2 |
| `hirari.prepareAverageMs`、`hirari.prepareMaxMs` | 準備のトレース区間 `hirari.prepare` の平均と最大。合成画像を描く時間を含む。`HARDWARE` では GPU へのアップロードも含む | Q2 |
| `frameDurationCpuMs`、`frameOverrunMs` | Macrobenchmark の `FrameTimingMetric` | P2 |
| `timelineAppJankPercent` | 計った区間のアプリのフレームのうち、FrameTimeline の種別に `App Deadline Missed` を含むものの割合 | P2 |
| `timelineJankPercent` | 種別が `None` でないフレームの割合 | 参考 |
| `turnCount`、`turnShownCount`、`turnsSkipped` | 計れためくりの数、表示の時刻まで結び付いた数、準備が間に合わず飛ばしたタップの数 | 計測の確認 |

P1 は、次のように測る。

1. アプリはタップを受け取った時点で、非同期のトレース区間 `hirari.turn` を始める。イベント時刻からの遅れを区間の名前に入れる。区間はイベント時刻より前から始められないからである。
2. 新しいビューを初めて記録したところで区間を終える。
3. 区間の終わりを含む `Choreographer#doFrame <vsync id>` を探し、その vsync id の FrameTimeline の実際の区間の終わりを表示の時刻とする。

`frameOverrunMs` と `timelineAppJankPercent` は、どちらも P2 の「落ちたフレームの割合」の代わりである。`FrameTimingMetric` は割合を出さず、百分位数を出す。`frameOverrunMs` の P99 が 0 以下なら、遅れたフレームは 1% 未満である。

## エミュレータでの数値

**判定には使えない数値である。** 計測の流れが動くことの確認と、差の向きの目安にだけ使う。

条件は次のとおり。

- AVD は `FeedDeck_API37`（API 37、1080 × 2400、60 Hz、4 コア）。GPU はホストの GPU を通す変換（`ro.hardware.egl=emulation`）である。
- 起動のオプションは `-memory 2048` だが、端末の `MemTotal` は約 4 GB だった。
- 試行は 1 回の実行の中の 3 回である。値は 3 回分のめくり 300 回（ピンチは全フレーム）の百分位数か、3 回の中央値である。
- 60 Hz なので 1 vsync は 16.7 ms である。

めくり（P1 と Q2）。単位は ms。

| 変種 | `turnShownMs` P50 / P95 | `turnDrawMs` P50 / P95 | `turnRenderThreadMs` P50 / P95 | 準備の平均（中央値） |
|---|---|---|---|---|
| A `HARDWARE` | 16.9 / 32.4 | 8.4 / 22.3 | 7.2 / 10.9 | 46.8 |
| A `ARGB_8888` | 35.9 / 62.3 | 18.9 / 29.9 | 15.9 / 33.1 | 66.2 |
| A `ARGB_8888` と `prepareToDraw` | 31.1 / 45.2 | 19.4 / 29.2 | 10.3 / 21.3 | 56.6 |
| B `HARDWARE` | 20.0 / 41.6 | 11.0 / 27.0 | 8.0 / 17.3 | 58.5 |
| B `ARGB_8888` | 24.3 / 50.7 | 9.9 / 27.4 | 12.0 / 24.5 | 54.5 |
| B `ARGB_8888` と `prepareToDraw` | 27.7 / 43.2 | 15.7 / 27.9 | 8.7 / 18.6 | 57.9 |

フレーム（P2）。

| 変種 | `frameOverrunMs` P90 / P99 | `frameDurationCpuMs` P50 / P99 | `timelineAppJankPercent` の中央値（範囲） |
|---|---|---|---|
| A `HARDWARE` のめくり | 1.4 / 15.8 | 11.0 / 30.0 | 0.7%（0.2〜1.2） |
| A `ARGB_8888` のめくり | 34.8 / 78.9 | 20.4 / 80.1 | 11.4%（6.3〜13.4） |
| A `ARGB_8888` と `prepareToDraw` のめくり | 27.7 / 74.5 | 14.7 / 72.8 | 6.7%（3.3〜17.8） |
| B `HARDWARE` のめくり | 11.9 / 46.8 | 12.1 / 50.2 | 3.8%（0.7〜6.0） |
| B `ARGB_8888` のめくり | 13.9 / 50.1 | 12.6 / 53.2 | 5.0%（0.6〜6.2） |
| B `ARGB_8888` と `prepareToDraw` のめくり | 15.1 / 62.0 | 13.1 / 65.0 | 6.4%（2.2〜9.3） |
| A のピンチ | 30.9 / 64.4 | 21.1 / 62.1 | 4.2%（2.6〜17.7） |
| B のピンチ | 9.9 / 25.3 | 12.0 / 32.5 | 2.0%（1.3〜2.5） |

計測が仕事をしたことの確認。

- どのめくりのテストでも、各回の `turnCount` と `turnShownCount` は 100、`turnsSkipped` は 0 だった。100 回のタップがすべて準備済みのビューへのめくりになり、すべて表示の時刻まで結び付いた。
- 画面の写しで、どちらの案も次のビューが左から重なって入ることを確かめた。

数値の限界。

- **同じ変種でも、実行ごとの差が大きい。** A `HARDWARE` のめくりは 2 回実行した。起動直後のエミュレータでの 1 回目は、`turnShownMs` が P50 30.5、P95 49.1 で、`timelineAppJankPercent` の中央値は 9.9% だった。表の 2 回目は P50 16.9、P95 32.4、0.7% である。案 A と案 B の差は、この実行ごとの差より小さい。この計測では、案のあいだに差があるとは言えない。
- `timelineJankPercent` はどの変種でも 80% を超えた。エミュレータの表示の経路では、アプリのせいではない種別（バッファの詰まりなど）がほぼ全フレームに付く。P2 の判断には使わない。
- 速さを決めているのは、ホストの GPU を通す変換と 60 Hz の表示である。実機では、どちらも違う。
- 合成画像を描く時間が準備の時間の大半を占める。`HARDWARE` の変換だけの時間は、この計測では分けていない。

## 判断

8.4 節の判定は「P1 と P2 を満たす案のうち単純な方。両方満たせば A」である。

- エミュレータでは、どの変種も P1（1 vsync 以内、95 パーセンタイル）を満たさない。最も良い A `HARDWARE` でも P95 は 32.4 ms で、約 2 vsync である。
- P2 は、A `HARDWARE` のめくりだけが、`timelineAppJankPercent` の中央値で 1% を下回った。
- A は、エミュレータで明らかに B より悪いわけではない。めくりの差は実行ごとの差の中にある。ピンチは B の方が良く見えるが、1 回の実行の 3 回分だけである。
- A の方が単純である。画面の全体が Compose なので、状態の受け渡しと入力の扱いが 1 つの仕組みで済む。

以上から、Q1 は暫定で A とする。実機で A が P1 か P2 を満たさず、B が満たすときは B に替える。どちらも満たさないときは案 C を試す。

Q2 は `HARDWARE` を第一候補のまま保つ。どちらの案でも、`ARGB_8888` はめくりのフレームの RenderThread の時間を延ばした（A で P50 7.2 から 15.9 ms、B で 8.0 から 12.0 ms）。`prepareToDraw` を足すと差は縮むが、なくならない。差の向きは 2 つの案でそろっているが、大きさは実機で確かめる。

P1 の目標そのものも見直しが要る (要検証)。入力の処理、描画、合成、表示はそれぞれ vsync に合わせて進む。そのため、イベント時刻から表示まで 1 vsync に収めるのは、ふつうの描画の経路では難しいと考える。実機の数値を見て、5.2 節の P1 を「2 vsync 以内」などに直すか決める。

## 実機で計り直す

実機を 1 台だけ USB でつなぎ、リポジトリの最上位で次を実行する。Windows では `./gradlew` を `.\gradlew.bat` に読み替える。

```
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.yuubiz.hirari.benchmark.RenderSurfaceBenchmark
```

- 1 つのテストだけ流すときは、クラス名の後に `#composeHardwareTurns` のようにテスト名を付ける。
- エミュレータで流すときだけ、`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR` を足す。実機のコマンドには付けない。
- 全部で 8 つのテストがあり、エミュレータでは約 19 分かかった。
- 結果は `benchmark/build/outputs/connected_android_test_additional_output/` に出る。テストごとの要約の `.txt`、全体の `.json`、各回の Perfetto のトレースがある。
- 5.2 節の P1 は 200 回 × 3 試行と定める。既定のめくりは 100 回 × 3 回なので、実機で P1 を判定するときは `-Pandroid.testInstrumentationRunnerArguments.turns=200` を足す。P2 は既定の 100 回で計る。
- 計り直したら、この文書の数値と判断、`docs/spec.md` の 8.4 節と 10 章の Q1 と Q2 を直す。
