package com.claude.drawterm.data

import org.json.JSONObject
import java.util.UUID

/** A saved server and how its session behaves. */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val cpu: String = "",
    val auth: String = "",
    val user: String = "glenda",
    val savePassword: Boolean = true,
    val aan: Boolean = true,
    val phoneStorage: Boolean = false,
    val command: String = "",
    val secstore: String = "",
    val encryption: String = "",
    val extraArgs: String = "",
    /** Whole-pixel scale; 0 is automatic. */
    val scale: Int = 0,
    val trackpad: Boolean = false,
    val extraKeys: Boolean = true,
    val fullscreen: Boolean = true,
    val keepAlive: Boolean = true,
    val lastUsed: Long = 0L,
) {
    val address: String
        get() = if (user.isBlank()) cpu.trim() else "${user.trim()}@${cpu.trim()}"

    val title: String
        get() = name.trim().ifBlank { address }

    /** drawterm's command line; [root] is what the server sees as /mnt/term. */
    fun args(root: String): List<String> = buildList {
        add("drawterm")
        add("-h"); add(cpu.trim())
        // without -a drawterm would stop to ask on its console
        add("-a"); add(auth.trim().ifBlank { cpu.trim() })
        if (user.isNotBlank()) { add("-u"); add(user.trim()) }
        add("-r"); add(root)
        if (aan) add("-p")
        if (secstore.isNotBlank()) { add("-s"); add(secstore.trim()) }
        if (encryption.isNotBlank()) { add("-e"); add(encryption.trim()) }
        addAll(splitArgs(extraArgs))
        // -c swallows the rest of the command line
        if (command.isNotBlank()) { add("-c"); add(command.trim()) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("cpu", cpu); put("auth", auth); put("user", user)
        put("savePassword", savePassword); put("aan", aan); put("phoneStorage", phoneStorage)
        put("command", command); put("secstore", secstore); put("encryption", encryption)
        put("extraArgs", extraArgs); put("scale", scale); put("trackpad", trackpad)
        put("extraKeys", extraKeys); put("fullscreen", fullscreen); put("keepAlive", keepAlive)
        put("lastUsed", lastUsed)
    }

    companion object {
        private val defaults = Profile(id = "")

        fun fromJson(o: JSONObject): Profile = with(defaults) {
            Profile(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", name),
                cpu = o.optString("cpu", cpu),
                auth = o.optString("auth", auth),
                user = o.optString("user", user),
                savePassword = o.optBoolean("savePassword", savePassword),
                aan = o.optBoolean("aan", aan),
                phoneStorage = o.optBoolean("phoneStorage", phoneStorage),
                command = o.optString("command", command),
                secstore = o.optString("secstore", secstore),
                encryption = o.optString("encryption", encryption),
                extraArgs = o.optString("extraArgs", extraArgs),
                scale = o.optInt("scale", scale),
                trackpad = o.optBoolean("trackpad", trackpad),
                extraKeys = o.optBoolean("extraKeys", extraKeys),
                fullscreen = o.optBoolean("fullscreen", fullscreen),
                keepAlive = o.optBoolean("keepAlive", keepAlive),
                lastUsed = o.optLong("lastUsed", lastUsed),
            )
        }

        /** Split on blanks, honouring '…' and "…" quotes. */
        fun splitArgs(s: String): List<String> {
            val out = mutableListOf<String>()
            val word = StringBuilder()
            var inWord = false
            var quote: Char? = null
            for (c in s) {
                when {
                    quote != null -> if (c == quote) quote = null else word.append(c)
                    c == '\'' || c == '"' -> { quote = c; inWord = true }
                    c.isWhitespace() -> if (inWord) { out += word.toString(); word.clear(); inWord = false }
                    else -> { word.append(c); inWord = true }
                }
            }
            if (inWord) out += word.toString()
            return out
        }
    }
}
