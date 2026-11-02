package io.github.yuubiz.hirari.core.text

import kotlin.random.Random

private val sentences = listOf(
    "吾輩《わがはい》は猫である。",
    "名前はまだ無い。",
    "どこで生れたかとんと見当《けんとう》がつかぬ。",
    "「何でも薄暗いじめじめした所で、ニャーニャー泣いていた事だけは記憶している」",
    "吾輩はここで始めて人間というものを見た。",
    "しかもあとで聞くとそれは書生という人間中で一番｜獰悪《どうあく》な種族であったそうだ。",
    "この書生というのは時々我々を捕《つかま》えて煮《に》て食うという話である。",
    "第12話のABCとPython 3.12のこと――そして……",
    "彼は（ひどく）驚いた［＃「驚いた」に傍点］。",
    "いろ／＼な事があった。",
)

/** A deterministic Aozora-style text of about [chars] characters, with the notations layout cares about. */
fun sampleBook(chars: Int, seed: Int = 1): String {
    val random = Random(seed)
    val out = StringBuilder("吾輩は猫である\n夏目漱石\n\n")
    var chapter = 1
    while (out.length < chars) {
        out.append("［＃５字下げ］第").append(chapter).append("章［＃「第").append(chapter).append("章」は中見出し］\n\n")
        repeat(30 + random.nextInt(30)) {
            out.append("　")
            repeat(1 + random.nextInt(8)) { out.append(sentences[random.nextInt(sentences.size)]) }
            out.append('\n')
            if (random.nextInt(10) == 0) out.append('\n')
        }
        out.append("［＃改ページ］\n")
        chapter++
    }
    return out.toString()
}
