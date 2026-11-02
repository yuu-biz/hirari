package io.github.yuubiz.hirari.core.model

/** Where taps go back and forward in the reader (docs/spec.md section 4.8, タップ領域). */
enum class TapLayout { OneSideBand, BothSideBands, TopAndBottom, TopAndBottomBands }

/** How the reader moves from one view to the next (docs/spec.md section 4.8, めくり効果). */
enum class TurnEffect { None, Slide, Cover, Fade }

/** Which volume key advances the reader, if any (docs/spec.md section 4.8, キー操作). */
enum class VolumeKeys { Off, DownAdvances, UpAdvances }
