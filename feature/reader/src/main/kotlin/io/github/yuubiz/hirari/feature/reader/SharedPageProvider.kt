package io.github.yuubiz.hirari.feature.reader

import androidx.core.content.FileProvider

/**
 * Hands the files of ページを共有 in `cacheDir/share/` to other apps (docs/spec.md section 4.8). A class of its own,
 * so the merged manifest can hold another `FileProvider` beside it. The static `getUriForFile` reads the paths from
 * the provider's `FILE_PROVIDER_PATHS` meta-data, not from this constructor, so the manifest names them too.
 */
class SharedPageProvider : FileProvider(R.xml.share_paths)
