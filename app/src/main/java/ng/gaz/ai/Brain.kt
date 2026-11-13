package ng.gaz.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Talks to the free AI: Google Gemini (free tier) or Ollama running on your own PC. */
object Brain {

    private val TONE_GUIDE = mapOf(
        "Friendly" to "warm, relaxed and friendly, like texting a good friend",
        "Professional" to "polite, clear and professional, but still human and not stiff",
        "Romantic" to "sweet, caring and gently flirty, respectful and never crude",
        "Playful" to "light, witty and playful, with a little teasing and humour",
        "Business" to "short, confident and businesslike: clear next steps, prices or times only if already mentioned",
        "Pidgin" to "Nigerian Pidgin English, natural and friendly, the way people really text in Naija"
    )

    class BrainError(msg: String) : Exception(msg)

    fun suggest(ctx: Context, chat: Chat, history: List<Msg>, tone: String): List<String> {
        val name = Prefs.str(ctx, Prefs.MY_NAME).ifBlank { "me" }
        val about = Prefs.str(ctx, Prefs.ABOUT_ME)
        val style = Prefs.str(ctx, Prefs.STYLE)

        val system = buildString {
            append("You write chat replies on behalf of $name. Write exactly as $name would type them on ${chat.appLabel}.\n")
            if (about.isNotBlank()) append("About $name: $about\n")
            if (style.isNotBlank()) append("Examples of how $name really writes (copy this style, spelling habits and length):\n$style\n")
            append("Tone for this chat: ${TONE_GUIDE[tone] ?: TONE_GUIDE["Friendly"]}.\n")
            append(
                """
                Rules:
                - Sound like a real person texting, not an assistant. Short, like real chat messages (usually one or two sentences).
                - Match the other person's language: English, Pidgin, or a mix, as they use it.
                - Read the whole conversation. Answer what they actually said or asked, including earlier questions that were never answered.
                - Do not repeat lines or openings already used in the conversation. Avoid one-word replies like "Ok".
                - Keep the chat going naturally, often with a light question.
                - Never invent facts, promises, money, meetings or plans for $name. If something needs $name's decision, keep it open.
                - Never say you are an AI. No hashtags. Emojis only if they fit the style.
                - Give 3 clearly different options.
                Answer only with JSON: {"replies":["option 1","option 2","option 3"]}
                """.trimIndent()
            )
        }
        val convo = buildString {
            append("Chat on ${chat.appLabel} with ${chat.title}")
            append(if (chat.isGroup) " (group chat)" else "")
            append(". Oldest first:\n")
            for (m in history) append(if (m.fromMe) "Me: " else "${m.sender.ifBlank { chat.title }}: ").append(m.text).append('\n')
            append("\nWrite 3 options for my next message.")
        }

        val raw = when (Prefs.str(ctx, Prefs.ENGINE, "gemini")) {
            "ollama" -> ollama(ctx, system, convo)
            else -> gemini(ctx, system, convo)
        }
        return parseReplies(raw)
    }

    fun parseReplies(raw: String): List<String> {
        var t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start > 0) t = t.substring(start)
        val arr: JSONArray = runCatching {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("replies")
        }.getOrElse { throw BrainError("The AI gave an unexpected answer. Try again.") }
        val out = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        if (out.isEmpty()) throw BrainError("The AI returned no replies. Try again.")
        return out.take(3)
    }

    private fun gemini(ctx: Context, system: String, user: String): String {
        val key = Prefs.str(ctx, Prefs.API_KEY).trim().trim('"', '\'')
        if (key.isEmpty()) throw BrainError("Add your free Gemini API key: tap \"Get a free key\", copy it and paste it in GAZ AI.")
        if (!key.startsWith("AIza")) throw BrainError("That doesn't look like a Gemini API key. It should start with \"AIza\". Copy it again from Google AI Studio.")
        val model = Prefs.str(ctx, Prefs.MODEL, Prefs.DEFAULT_MODEL).trim().ifEmpty { Prefs.DEFAULT_MODEL }
        return try {
            geminiCall(key, model, system, user)
        } catch (e: ModelMissing) {
            // The model name was retired or isn't available for this key: find one that works and remember it
            val better = pickModel(key) ?: throw BrainError("No Gemini model is available for your key right now. Try again later.")
            Prefs.putStr(ctx, Prefs.MODEL, better)
            geminiCall(key, better, system, user)
        }
    }

    private class ModelMissing : Exception()

    /** Ask Google which models this key can use and pick a fast, free one. */
    private fun pickModel(key: String): String? {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=" + URLEncoder.encode(key, "UTF-8"))
            .openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { return null } finally { c.disconnect() }
        val arr = JSONObject(text).optJSONArray("models") ?: return null
        val usable = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { m ->
            val methods = m.optJSONArray("supportedGenerationMethods")
            methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
        }.map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && listOf("image", "tts", "audio", "live", "embedding", "vision").none { bad -> it.contains(bad) } }
        fun version(n: String) = Regex("""gemini-(\d+(?:\.\d+)?)""").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val stable = usable.filter { !it.contains("preview") && !it.contains("exp") }
        val pool = stable.ifEmpty { usable }
        return pool.filter { it.contains("flash") && !it.contains("lite") }.maxByOrNull { version(it) }
            ?: pool.filter { it.contains("flash") }.maxByOrNull { version(it) }
            ?: pool.maxByOrNull { version(it) }
    }

    private fun geminiCall(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", 0.9).put("responseMimeType", "application/json"))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8")
        val (code, text) = post(url, body.toString(), 30_000)
        if (code == 400 && text.contains("API_KEY_INVALID")) throw BrainError("Google says your Gemini API key is not valid. Create a new key in Google AI Studio and paste it again.")
        if (code == 404 || (code == 400 && text.contains("model", ignoreCase = true) && text.contains("not", ignoreCase = true) && text.contains("found", ignoreCase = true))) throw ModelMissing()
        if (code == 403) throw BrainError("Google refused the key (403): " + errMsg(text) + ". Make sure the key is from aistudio.google.com/apikey.")
        if (code == 400 && text.contains("location", ignoreCase = true)) throw BrainError("Gemini isn't available from your current network location: " + errMsg(text))
        if (code == 429) throw BrainError("Gemini free limit reached for now. Wait a minute and try again.")
        if (code !in 200..299) throw BrainError("Gemini error $code: " + errMsg(text))
        val j = JSONObject(text)
        val cands = j.optJSONArray("candidates") ?: throw BrainError("Gemini gave no answer (it may have blocked the message).")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw BrainError("Gemini gave an empty answer.")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    private fun ollama(ctx: Context, system: String, user: String): String {
        var base = Prefs.str(ctx, Prefs.OLLAMA_URL).trim().trimEnd('/')
        if (base.isEmpty()) throw BrainError("Add your PC's Ollama address in settings, e.g. 192.168.1.20:11434")
        if (!base.startsWith("http")) base = "http://$base"
        val model = Prefs.str(ctx, Prefs.OLLAMA_MODEL, "llama3.2").trim().ifEmpty { "llama3.2" }
        val body = JSONObject()
            .put("model", model).put("stream", false).put("format", "json")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val (code, text) = try { post("$base/api/chat", body.toString(), 90_000) }
            catch (e: Exception) { throw BrainError("Can't reach your PC at $base. Is Ollama running and is the phone on the same Wi-Fi?") }
        if (code !in 200..299) throw BrainError("Ollama error $code: " + errMsg(text))
        return JSONObject(text).optJSONObject("message")?.optString("content") ?: throw BrainError("Ollama gave an empty answer.")
    }

    private fun errMsg(text: String) = runCatching {
        JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("error")
    }.getOrNull()?.take(200) ?: text.take(200)

    private fun post(url: String, json: String, timeout: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = timeout
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
