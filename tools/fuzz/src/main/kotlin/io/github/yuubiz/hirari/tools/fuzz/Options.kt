package io.github.yuubiz.hirari.tools.fuzz

/** `--name value` pairs and `--flag`s, with defaults for what is missing. */
class Arguments(args: Array<String>) {
    private val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            val name = args[i].removePrefix("--")
            require(args[i].startsWith("--")) { "unexpected argument '${args[i]}'" }
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                values[name] = args[i + 1]
                i += 2
            } else {
                values[name] = "true"
                i++
            }
        }
    }

    fun string(name: String): String? = values[name]

    fun string(name: String, default: String): String = values[name] ?: default

    fun double(name: String, default: Double): Double = values[name]?.toDouble() ?: default

    fun long(name: String, default: Long): Long = values[name]?.toLong() ?: default

    fun int(name: String, default: Int): Int = values[name]?.toInt() ?: default

    fun flag(name: String): Boolean = values[name] == "true"

    /** The names given that are not in [known]. */
    fun unknown(known: Set<String>): Set<String> = values.keys - known
}
