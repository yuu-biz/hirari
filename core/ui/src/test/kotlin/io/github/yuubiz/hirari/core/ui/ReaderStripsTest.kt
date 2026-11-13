package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.BarLayout
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Size
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ReaderStripsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun textNumbersAndCommandsStayAccessibleInTheirOwnStrips() {
        val regions = ReaderRegions(Size(411f, 914f), 60f, bars = BarLayout.Shown)
        val row = CommandCells(listOf("目次", "検索", "しおりを追加", "しおり一覧", "文字の大きさ", "組み方", "画面の向き"),
            "閉じる", "設定", "その他", regions.commandRow!!)
        var commands = 0
        var pages = 0
        val items = mutableListOf<String>()
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 914.dp)) {
                ReaderStrips(ReaderBackground.White, "本の表題", "約12 / 約300", 0.04f,
                    ReadingDirection.RightToLeft, 16, regions, row, { it }, { BarIcons.Close }, "操作を開く", "ページを開く",
                    { commands++ }, { pages++ }, { items += it })
            }
        }
        for (label in listOf("閉じる", "目次", "その他", "設定")) {
            assertEquals(Role.Button, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
            rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        }
        assertEquals(listOf("閉じる", "目次", "その他", "設定"), items)
        rule.onNodeWithText("本の表題", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("約12 / 約300").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, commands)
        assertEquals(1, pages)
    }
}
