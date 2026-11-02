package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.AppTheme
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReopenLastBook
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenSpot
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TextLatinMode
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TextMargin
import io.github.yuubiz.hirari.core.model.TextPaper
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.reflect.KProperty1

private const val DYNAMIC_COLOR_SDK = 31

private val spotOptions: Array<Pair<ScreenSpot, String>> = arrayOf(
    ScreenSpot.Off to "出さない", ScreenSpot.TopLeft to "左上", ScreenSpot.TopCenter to "上中央", ScreenSpot.TopRight to "右上",
    ScreenSpot.BottomLeft to "左下", ScreenSpot.BottomCenter to "下中央", ScreenSpot.BottomRight to "右下",
)

/** The commands of docs/spec.md section 4.8 that exist; 息で操作 follows the breath control in milestone M6. */
private val commandChoices: List<Option<ReaderCommand>> = listOf(
    ReaderCommand.Layout to "組み方",
    ReaderCommand.ShiftPairing to "組ずらし",
    ReaderCommand.ScaleMode to "拡大モード",
    ReaderCommand.Trim to "余白トリミング",
    ReaderCommand.Direction to "綴じ方向",
    ReaderCommand.Rotate to "回転",
    ReaderCommand.Orientation to "画面の向き",
    ReaderCommand.AddBookmark to "しおりを追加",
    ReaderCommand.Bookmarks to "しおり一覧",
    ReaderCommand.AutoPlay to "自動再生",
    ReaderCommand.ReverseOrder to "並びを逆に",
    ReaderCommand.ImageAdjust to "画像補正",
    ReaderCommand.MirrorControls to "操作の反転",
    ReaderCommand.StopOrder to "停止点の順序",
    ReaderCommand.Gutter to "綴じ目の表示",
    ReaderCommand.EditCommandBar to "コマンドバーの編集",
    ReaderCommand.BookSettings to "本ごとの設定",
    ReaderCommand.SetCover to "表紙にする",
    ReaderCommand.SharePage to "ページを共有",
    ReaderCommand.Settings to "設定",
).map { (command, label) -> Option(command, label) }

/**
 * Every setting of docs/spec.md section 4.10 that has an effect to choose, in the order and under the groups of the
 * spec. The breath control settings are not here until the feature exists (milestone M6).
 */
val SettingsTable: List<Setting> = buildList {
    val reading = SettingGroup.Reading
    add(choice(GlobalSettings::readingDirection, reading, "綴じ方向", { s, v -> s.copy(readingDirection = v) },
        ReadingDirection.RightToLeft to "右綴じ", ReadingDirection.LeftToRight to "左綴じ"))
    add(choice(GlobalSettings::layoutMode, reading, "ページの組み方", { s, v -> s.copy(layoutMode = v) },
        LayoutMode.Single to "1 ページ", LayoutMode.Spread to "見開き", LayoutMode.SplitWide to "横長を分割", LayoutMode.Auto to "自動"))
    add(toggle(GlobalSettings::coverAlone, reading, "表紙を単独にする") { s, v -> s.copy(coverAlone = v) })
    add(choice(GlobalSettings::entryOrder, reading, "書庫内の並び", { s, v -> s.copy(entryOrder = v) },
        EntryOrder.Natural to "名前の自然順", EntryOrder.Stored to "格納順"))
    add(choice(GlobalSettings::atBookEnd, reading, "本の端での動作", { s, v -> s.copy(atBookEnd = v) },
        BookEndAction.Ask to "確認する", BookEndAction.NextBook to "次の本へ", BookEndAction.Close to "閉じる", BookEndAction.Stop to "止まる"))

    val image = SettingGroup.ImageDisplay
    add(choice(GlobalSettings::scaleMode, image, "拡大モード", { s, v -> s.copy(scaleMode = v) },
        ScaleMode.FitScreen to "画面に収める", ScaleMode.FitWidth to "幅に合わせる", ScaleMode.FitHeight to "高さに合わせる",
        ScaleMode.Original to "原寸", ScaleMode.Stretch to "画面いっぱい"))
    add(toggle(GlobalSettings::capAtOriginal, image, "拡大は原寸まで") { s, v -> s.copy(capAtOriginal = v) })
    add(choice(GlobalSettings::trimStrength, image, "余白トリミング", { s, v -> s.copy(trimStrength = v) },
        TrimStrength.Off to "オフ", TrimStrength.Weak to "弱", TrimStrength.Medium to "中", TrimStrength.Strong to "強", TrimStrength.Strongest to "最強"))
    add(choice(GlobalSettings::gutterStyle, image, "綴じ目の表示", { s, v -> s.copy(gutterStyle = v) },
        GutterStyle.None to "なし", GutterStyle.Gap to "すき間", GutterStyle.Shadow to "影"))
    add(
        ColorSetting(
            key = GlobalSettings::readerBackground.name,
            group = image,
            label = "背景色",
            presets = listOf(
                Option(ReaderBackground.Black, "黒"),
                Option(ReaderBackground.DarkGray, "濃い灰色"),
                Option(ReaderBackground.White, "白"),
                Option(ReaderBackground.Theme, "テーマに従う"),
            ),
            read = GlobalSettings::readerBackground,
            write = { s, v -> s.copy(readerBackground = v) },
            customOf = { (it as? ReaderBackground.Custom)?.color },
            custom = ReaderBackground::Custom,
        ),
    )
    add(slider(GlobalSettings::adjustLevel, image, "明るさ", { _ -> GlobalSettings.ADJUST_LEVEL.toList() }, { signed(it) }) { s, v -> s.copy(adjustLevel = v) })
    add(toggle(GlobalSettings::adjustSharpen, image, "シャープ") { s, v -> s.copy(adjustSharpen = v) })
    add(toggle(GlobalSettings::adjustMono, image, "グレースケール") { s, v -> s.copy(adjustMono = v) })
    add(toggle(GlobalSettings::adjustNegative, image, "白黒反転") { s, v -> s.copy(adjustNegative = v) })
    add(choice(GlobalSettings::turnEffect, image, "めくり効果", { s, v -> s.copy(turnEffect = v) },
        TurnEffect.None to "なし", TurnEffect.Slide to "スライド", TurnEffect.Cover to "重ねる", TurnEffect.Fade to "フェード"))
    add(slider(GlobalSettings::turnDurationMs, image, "めくりの長さ", { _ -> GlobalSettings.TURN_DURATION_MS.toList() }, { "$it ms" }) { s, v -> s.copy(turnDurationMs = v) })
    add(choice(GlobalSettings::stopOrder, image, "停止点の順序", { s, v -> s.copy(stopOrder = v) },
        StopOrder.RowsFirst to "行ごと（横に進んでから下へ）", StopOrder.ColumnsFirst to "列ごと（縦に進んでから横へ）"))
    add(
        slider(
            GlobalSettings::stopMaxStepPercent, image, "停止点の最大移動量", { _ -> GlobalSettings.STOP_MAX_STEP_PERCENT.toList() }, { "$it%" },
            note = "画面の大きさに対する割合です。縦と横に共通です。",
        ) { s, v -> s.copy(stopMaxStepPercent = v) },
    )
    add(choice(GlobalSettings::loupeZoom, image, "ルーペ倍率", { s, v -> s.copy(loupeZoom = v) },
        LoupeZoom.View150 to "表示の 1.5 倍", LoupeZoom.View200 to "表示の 2 倍", LoupeZoom.View250 to "表示の 2.5 倍", LoupeZoom.View300 to "表示の 3 倍",
        LoupeZoom.Source100 to "元画像の 1 倍", LoupeZoom.Source200 to "元画像の 2 倍", LoupeZoom.Source300 to "元画像の 3 倍"))
    add(choice(GlobalSettings::loupeFrame, image, "ルーペの大きさ", { s, v -> s.copy(loupeFrame = v) },
        LoupeFrame.Small to "小", LoupeFrame.Medium to "中", LoupeFrame.Large to "大"))
    add(slider(GlobalSettings::loupeHoldMs, image, "ルーペを出す長押しの長さ", { _ -> GlobalSettings.LOUPE_HOLD_MS.toList() }, { "$it ms" }) { s, v -> s.copy(loupeHoldMs = v) })

    val controls = SettingGroup.Controls
    add(choice(GlobalSettings::tapLayout, controls, "タップ領域の配置", { s, v -> s.copy(tapLayout = v) },
        TapLayout.OneSideBand to "片側の帯", TapLayout.BothSideBands to "両側の帯", TapLayout.TopAndBottom to "上下", TapLayout.TopAndBottomBands to "上下の帯"))
    add(
        slider(
            GlobalSettings::tapBandPercent, controls, "タップの帯の幅",
            { s -> (if (s.tapLayout.hasTwoBands) GlobalSettings.TAP_TWO_BAND_PERCENT else GlobalSettings.TAP_BAND_PERCENT).toList() },
            { "$it%" },
            note = "両側の帯と上下の帯では、1 本を 45% までにします。",
        ) { s, v -> s.copy(tapBandPercent = v) },
    )
    add(toggle(GlobalSettings::mirrorControls, controls, "左右を反転", note = "タップとスワイプの向きを入れ替えます。") { s, v -> s.copy(mirrorControls = v) })
    add(toggle(GlobalSettings::tapAdvancesStops, controls, "タップで停止点を進める") { s, v -> s.copy(tapAdvancesStops = v) })
    add(toggle(GlobalSettings::swipeToTurn, controls, "スワイプでめくる") { s, v -> s.copy(swipeToTurn = v) })
    add(choice(GlobalSettings::volumeKeys, controls, "音量キー", { s, v -> s.copy(volumeKeys = v) },
        VolumeKeys.Off to "使わない", VolumeKeys.DownAdvances to "下で進む", VolumeKeys.UpAdvances to "上で進む"))
    add(toggle(GlobalSettings::turnHaptics, controls, "めくりで振動", note = "ビューが変わるたびに短く震えます。") { s, v -> s.copy(turnHaptics = v) })
    add(
        slider(
            GlobalSettings::barHeightDp, controls, "バーの高さ", { _ -> GlobalSettings.BAR_HEIGHT_DP.toList() }, { "$it dp" },
            note = "コマンドバーとページバーに共通です。",
        ) { s, v -> s.copy(barHeightDp = v) },
    )
    add(
        OrderedSetSetting(
            key = GlobalSettings::commandBarItems.name,
            group = controls,
            label = "コマンドバーの項目",
            note = "最大 ${ReaderCommand.MAX_ITEMS} 個まで選べます。",
            choices = commandChoices,
            max = ReaderCommand.MAX_ITEMS,
            read = GlobalSettings::commandBarItems,
            write = { s, v -> s.copy(commandBarItems = v) },
        ),
    )
    add(slider(GlobalSettings::autoPlaySeconds, controls, "自動再生の間隔", { _ -> GlobalSettings.AUTO_PLAY_SECONDS.toValues() }, { "${decimalText(it)} 秒" }) { s, v -> s.copy(autoPlaySeconds = v) })

    val screen = SettingGroup.Screen
    add(choice(GlobalSettings::pageNumberSpot, screen, "ページ番号の位置", { s, v -> s.copy(pageNumberSpot = v) }, *spotOptions))
    add(choice(GlobalSettings::statusSpot, screen, "時刻と電池の位置", { s, v -> s.copy(statusSpot = v) }, *spotOptions))
    add(choice(GlobalSettings::overlayTextSize, screen, "重ねる文字の大きさ", { s, v -> s.copy(overlayTextSize = v) },
        OverlayTextSize.Small to "小", OverlayTextSize.Medium to "中", OverlayTextSize.Large to "大"))
    add(toggle(GlobalSettings::hideSystemBars, screen, "システムバーを隠す") { s, v -> s.copy(hideSystemBars = v) })
    add(choice(GlobalSettings::keepAwake, screen, "画面を消さない", { s, v -> s.copy(keepAwake = v) },
        KeepAwake.System to "端末の設定に従う", KeepAwake.TenMinutes to "最後の操作から 10 分", KeepAwake.Always to "常に"))
    add(choice(GlobalSettings::readerOrientation, screen, "画面の向き", { s, v -> s.copy(readerOrientation = v) },
        ReaderOrientation.System to "端末に従う", ReaderOrientation.Portrait to "縦", ReaderOrientation.Landscape to "横"))
    add(
        choice(
            GlobalSettings::readerBrightness, screen, "画面の明るさ", { s, v -> s.copy(readerBrightness = v) },
            *(listOf<Pair<Int?, String>>(null to "端末の設定") + GlobalSettings.READER_BRIGHTNESS.map { it to "$it%" }).toTypedArray(),
            note = "リーダーを開いている間だけ変えます。",
        ),
    )

    val text = SettingGroup.Text
    add(
        ChoiceSetting(
            key = GlobalSettings::textTypeface.name,
            group = text,
            label = "本文の書体",
            options = listOf(Option(TextTypeface.Mincho, "明朝体"), Option(TextTypeface.Gothic, "ゴシック体")),
            read = GlobalSettings::textTypeface,
            write = { s, v -> s.copy(textTypeface = v) },
            describe = { value ->
                when (value) {
                    TextTypeface.Mincho -> "明朝体"
                    TextTypeface.Gothic -> "ゴシック体"
                    is TextTypeface.Imported -> "取り込んだ書体（${value.fileName}）"
                }
            },
        ),
    )
    add(slider(GlobalSettings::textSizeSp, text, "文字の大きさ", { _ -> GlobalSettings.TEXT_SIZE_SP.toList() }, { "$it sp" }) { s, v -> s.copy(textSizeSp = v) })
    add(
        slider(
            GlobalSettings::textLineGap, text, "行間", { _ -> GlobalSettings.TEXT_LINE_GAP.toValues() }, { decimalText(it) },
            note = "行と行の間の空きを、文字の大きさに対する比で表します。",
        ) { s, v -> s.copy(textLineGap = v) },
    )
    add(
        slider(
            GlobalSettings::textLetterGap, text, "字間", { _ -> GlobalSettings.TEXT_LETTER_GAP.toValues() }, { decimalText(it) },
            note = "文字の大きさに対する比です。",
        ) { s, v -> s.copy(textLetterGap = v) },
    )
    add(
        ChoiceSetting(
            key = GlobalSettings::textMargin.name,
            group = text,
            label = "余白",
            note = "上下左右に共通です。",
            options = TextMargin.entries.map { Option(it, "${marginName(it)}（${it.dp} dp）") },
            read = GlobalSettings::textMargin,
            write = { s, v -> s.copy(textMargin = v) },
        ),
    )
    add(choice(GlobalSettings::textLayout, text, "組み方", { s, v -> s.copy(textLayout = v) },
        TextLayout.Single to "1 ページ", TextLayout.Spread to "見開き", TextLayout.Continuous to "連続", TextLayout.Auto to "自動"))
    add(choice(GlobalSettings::textLatinMode, text, "縦書き中の欧文", { s, v -> s.copy(textLatinMode = v) },
        TextLatinMode.TateChuYoko to "短い英数字を縦中横", TextLatinMode.Sideways to "横倒し", TextLatinMode.Upright to "1 字ずつ正立"))
    add(choice(GlobalSettings::textPaper, text, "紙の色", { s, v -> s.copy(textPaper = v) },
        TextPaper.White to "白", TextPaper.Cream to "生成り", TextPaper.Sepia to "セピア", TextPaper.Black to "黒"))
    add(optionalColor(GlobalSettings::textInkOverride, text, "文字色の上書き") { s, v -> s.copy(textInkOverride = v) })
    add(optionalColor(GlobalSettings::textPaperOverride, text, "背景色の上書き") { s, v -> s.copy(textPaperOverride = v) })

    val library = SettingGroup.Library
    add(choice(GlobalSettings::libraryView, library, "表示形式", { s, v -> s.copy(libraryView = v) },
        LibraryView.Grid to "グリッド", LibraryView.ThumbnailList to "サムネイル付きリスト", LibraryView.List to "リスト"))
    add(choice(GlobalSettings::thumbSize, library, "サムネイルの大きさ", { s, v -> s.copy(thumbSize = v) },
        ThumbSize.Small to "小", ThumbSize.Medium to "中", ThumbSize.Large to "大"))
    add(choice(GlobalSettings::librarySort, library, "並べ替え", { s, v -> s.copy(librarySort = v) },
        LibrarySort.Name to "名前", LibrarySort.Newest to "新しい順", LibrarySort.Oldest to "古い順"))
    add(toggle(GlobalSettings::foldersFirst, library, "フォルダを先に") { s, v -> s.copy(foldersFirst = v) })
    add(toggle(GlobalSettings::showDotFiles, library, "隠しファイルを表示") { s, v -> s.copy(showDotFiles = v) })
    add(toggle(GlobalSettings::allowFileEdits, library, "削除と名前の変更を許可") { s, v -> s.copy(allowFileEdits = v) })
    add(choice(GlobalSettings::reopenLastBook, library, "起動時に前回の本を開く", { s, v -> s.copy(reopenLastBook = v) },
        ReopenLastBook.Off to "しない", ReopenLastBook.Ask to "尋ねる", ReopenLastBook.Immediately to "すぐ開く"))
    add(
        choice(
            GlobalSettings::historyLimit, library, "履歴の件数", { s, v -> s.copy(historyLimit = v) },
            *GlobalSettings.HISTORY_LIMITS.map { it to if (it == 0) "残さない" else "$it 件" }.toTypedArray(),
        ),
    )
    add(choice(GlobalSettings::historySort, library, "履歴の並び", { s, v -> s.copy(historySort = v) },
        HistorySort.PathAscending to "パスの昇順", HistorySort.PathDescending to "パスの降順", HistorySort.Oldest to "古い順", HistorySort.Newest to "新しい順"))
    add(choice(GlobalSettings::bookmarkSort, library, "しおりの並び", { s, v -> s.copy(bookmarkSort = v) },
        BookmarkSort.PathAscending to "パスの昇順", BookmarkSort.PathDescending to "パスの降順", BookmarkSort.Oldest to "古い順",
        BookmarkSort.Newest to "新しい順", BookmarkSort.NameAscending to "名前の昇順", BookmarkSort.NameDescending to "名前の降順"))

    val appearance = SettingGroup.Appearance
    add(choice(GlobalSettings::appTheme, appearance, "テーマ", { s, v -> s.copy(appTheme = v) },
        AppTheme.System to "端末に従う", AppTheme.Light to "ライト", AppTheme.Dark to "ダーク"))
    add(toggle(GlobalSettings::dynamicColor, appearance, "壁紙の色を使う", minSdk = DYNAMIC_COLOR_SDK) { s, v -> s.copy(dynamicColor = v) })
    add(
        choice(
            GlobalSettings::fallbackCharset, appearance, "判定できないときの文字コード", { s, v -> s.copy(fallbackCharset = v) },
            FallbackCharset.Cp932 to "CP932（Shift_JIS）", FallbackCharset.Utf8 to "UTF-8", FallbackCharset.EucJp to "EUC-JP",
            FallbackCharset.Gbk to "GBK", FallbackCharset.Big5 to "Big5", FallbackCharset.EucKr to "EUC-KR",
        ),
    )
}

/** The rows to show on a device with API level [sdk], grouped in the order of [SettingGroup]. */
fun settingsFor(sdk: Int): Map<SettingGroup, List<Setting>> =
    SettingsTable.filter { it.minSdk <= sdk }.groupBy { it.group }

private val TapLayout.hasTwoBands get() = this == TapLayout.BothSideBands || this == TapLayout.TopAndBottomBands

private fun marginName(margin: TextMargin) = when (margin) {
    TextMargin.Narrow -> "狭い"
    TextMargin.Medium -> "中"
    TextMargin.Wide -> "広い"
}

private fun signed(value: Int) = if (value > 0) "+$value" else "$value"

private fun toggle(
    property: KProperty1<GlobalSettings, Boolean>,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    write: (GlobalSettings, Boolean) -> GlobalSettings,
) = ToggleSetting(property.name, group, label, note, minSdk, property, write)

private fun <T> choice(
    property: KProperty1<GlobalSettings, T>,
    group: SettingGroup,
    label: String,
    write: (GlobalSettings, T) -> GlobalSettings,
    vararg options: Pair<T, String>,
    note: String? = null,
) = ChoiceSetting(
    key = property.name,
    group = group,
    label = label,
    note = note,
    options = options.map { (value, name) -> Option(value, name) },
    read = property,
    write = write,
)

private fun <T> slider(
    property: KProperty1<GlobalSettings, T>,
    group: SettingGroup,
    label: String,
    values: (GlobalSettings) -> List<T>,
    format: (T) -> String,
    note: String? = null,
    write: (GlobalSettings, T) -> GlobalSettings,
) = SliderSetting(property.name, group, label, note, 0, values, format, property, write)

/** A color that can also be left unset. */
private fun optionalColor(
    property: KProperty1<GlobalSettings, Rgb?>,
    group: SettingGroup,
    label: String,
    write: (GlobalSettings, Rgb?) -> GlobalSettings,
) = ColorSetting<Rgb?>(
    key = property.name,
    group = group,
    label = label,
    presets = listOf(Option(null, "未設定")),
    read = property,
    write = write,
    customOf = { it },
    custom = { it },
)
