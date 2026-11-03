package io.github.yuubiz.hirari.feature.text

/**
 * The turn asked for before the [TextReaderModel] it is for exists (docs/spec.md section 4.9, ページ分割). Of several,
 * the latest is kept, as the model keeps them; the model takes it over once it is built.
 */
internal class EarlyTurn {
    private var views: Int? = null

    fun queue(views: Int) {
        this.views = views
    }

    fun handOver(model: TextReaderModel) {
        val views = views ?: return
        this.views = null
        model.turn(views)
    }
}
