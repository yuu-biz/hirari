package io.github.yuubiz.hirari.feature.reader

import androidx.core.content.FileProvider

/**
 * Hands the files of ページを共有 in `cacheDir/share/` to other apps (docs/spec.md section 4.8). A class of its own,
 * so the merged manifest can hold another `FileProvider` beside it.
 */
class SharedPageProvider : FileProvider(R.xml.share_paths)
