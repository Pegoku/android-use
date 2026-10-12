package dev.pegoku.androiduse.agent

/** Which shell commands the agent may run freely, which need approval, and which are never allowed. */
object ShellPolicy {

    /** Splits a command line like a POSIX shell would for plain words and quotes (no expansion). */
    fun tokenize(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null && c == quote -> quote = null
                quote == '"' && c == '\\' && i + 1 < command.length && command[i + 1] in "\"\\$`" -> current.append(command[++i])
                quote != null -> current.append(c)
                c == '\'' || c == '"' -> { quote = c; inWord = true }
                c == '\\' && i + 1 < command.length -> { current.append(command[++i]); inWord = true }
                c.isWhitespace() -> if (inWord) { args += current.toString(); current.clear(); inWord = false }
                else -> { current.append(c); inWord = true }
            }
            i++
        }
        require(quote == null) { "Unterminated quote" }
        if (inWord) args += current.toString()
        return args
    }

    private val READ_ONLY_DUMPSYS = setOf(
        "battery", "power", "connectivity", "wifi", "audio", "display", "location", "deviceidle",
        "telephony.registry", "uimode", "meminfo", "cpuinfo", "diskstats", "bluetooth_manager", "package",
    )

    /** Device-state queries that can't change anything or reveal personal content. */
    fun isReadOnly(argv: List<String>): Boolean {
        if (argv.isEmpty() || argv.any { arg -> arg.any { it.isISOControl() } }) return false
        val (cmd, sub) = argv[0] to argv.getOrNull(1)
        return when (cmd) {
            "getprop" -> argv.size <= 2
            "settings" -> sub == "get" || sub == "list"
            "dumpsys" -> sub in READ_ONLY_DUMPSYS
            "pm" -> sub == "list" || sub == "path"
            "wm" -> (sub == "size" || sub == "density") && argv.size == 2
            "cmd" -> sub == "wifi" && argv.getOrNull(2) == "status" && argv.size == 3
            "date", "uptime", "df", "id", "whoami" -> true
            "ip" -> sub == "addr" || sub == "address" || sub == "route"
            else -> false
        }
    }

    private val FORBIDDEN = listOf(
        Regex("""(^|[\s;&|(`])rm\s"""),
        Regex("""\bpm\s+(uninstall|clear|disable(-user)?|suspend|hide|revoke|reset-permissions)\b"""),
        Regex("""\bcmd\s+package\s+(uninstall|clear|disable|suspend|hide)\b"""),
        Regex("""\b(reboot|shutdown|setprop|mkfs\S*|dd|wipe)\b"""),
        Regex("""\bsvc\s+power\s+(reboot|shutdown)\b"""),
        Regex("""\bcontent\s+(delete|update)\b"""),
        Regex("""\bsettings\s+(delete|reset)\b"""),
        Regex("""MASTER_CLEAR|FACTORY_RESET""", RegexOption.IGNORE_CASE),
        Regex("""\b(moe\.shizuku|dev\.pegoku\.androiduse)\b"""),
    )

    /** Returns why a command is refused outright, or null if it may be considered. */
    fun forbiddenReason(command: String): String? =
        FORBIDDEN.firstOrNull { it.containsMatchIn(command) }?.let {
            "Refused: this command can delete data, break the device or tamper with Android Use/Shizuku (matched `${it.pattern}`)."
        }
}
