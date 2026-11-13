package ng.gaz.ai

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Settings (SharedPreferences) and chat memory (SQLite). */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("gazai", Context.MODE_PRIVATE)

    fun str(c: Context, k: String, d: String = "") = p(c).getString(k, d) ?: d
    fun putStr(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()

    const val ENABLED = "enabled"
    const val ENGINE = "engine"            // gemini | ollama
    const val API_KEY = "api_key"
    const val MODEL = "model"
    const val OLLAMA_URL = "ollama_url"
    const val OLLAMA_MODEL = "ollama_model"
    const val MY_NAME = "my_name"
    const val ABOUT_ME = "about_me"
    const val STYLE = "style_samples"
    const val DEFAULT_TONE = "default_tone"
    const val DEFAULT_MODE = "default_mode" // off | suggest | auto
    const val AUTO_DELAY = "auto_delay"     // seconds
    const val AUTO_GROUPS = "auto_groups"

    const val DEFAULT_MODEL = "gemini-2.5-flash"
}

val TONES = listOf("Friendly", "Professional", "Romantic", "Playful", "Business", "Pidgin")
val MODES = listOf("off", "suggest", "auto")
fun modeLabel(m: String) = when (m) { "off" -> "Off"; "auto" -> "Auto-reply"; else -> "Suggest" }

data class Chat(
    val id: String, val pkg: String, val title: String, val appLabel: String, val isGroup: Boolean,
    val mode: String?, val tone: String?, val lastTime: Long, val lastText: String
)

data class Msg(val fromMe: Boolean, val sender: String, val text: String, val time: Long)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "gazai.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, pkg TEXT, title TEXT, app TEXT, is_group INTEGER, mode TEXT, tone TEXT, last_time INTEGER, last_text TEXT, suggestions TEXT)")
        db.execSQL("CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT, from_me INTEGER, sender TEXT, text TEXT, time INTEGER)")
        db.execSQL("CREATE INDEX msgs_chat ON msgs(chat_id, time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
    }

    @Synchronized
    fun upsertChat(id: String, pkg: String, title: String, app: String, isGroup: Boolean, lastTime: Long, lastText: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("pkg", pkg); put("title", title); put("app", app); put("is_group", if (isGroup) 1 else 0)
            put("last_time", lastTime); put("last_text", lastText)
        }
        if (db.update("chats", cv, "id=?", arrayOf(id)) == 0) {
            cv.put("id", id)
            db.insert("chats", null, cv)
        }
    }

    @Synchronized
    fun setChatField(id: String, field: String, value: String?) {
        val cv = ContentValues().apply { if (value == null) putNull(field) else put(field, value) }
        writableDatabase.update("chats", cv, "id=?", arrayOf(id))
    }

    fun chat(id: String): Chat? = readableDatabase.rawQuery(
        "SELECT id,pkg,title,app,is_group,mode,tone,last_time,last_text FROM chats WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) rowToChat(c) else null }

    fun chats(limit: Int = 50): List<Chat> = readableDatabase.rawQuery(
        "SELECT id,pkg,title,app,is_group,mode,tone,last_time,last_text FROM chats ORDER BY last_time DESC LIMIT $limit", null
    ).use { c -> val l = ArrayList<Chat>(); while (c.moveToNext()) l.add(rowToChat(c)); l }

    private fun rowToChat(c: android.database.Cursor) = Chat(
        c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getInt(4) == 1,
        c.getString(5), c.getString(6), c.getLong(7), c.getString(8) ?: ""
    )

    fun suggestions(id: String): List<String> = readableDatabase.rawQuery(
        "SELECT suggestions FROM chats WHERE id=?", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) emptyList()
        else runCatching {
            val a = org.json.JSONArray(c.getString(0)); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun setSuggestions(id: String, list: List<String>) =
        setChatField(id, "suggestions", org.json.JSONArray(list).toString())

    /** Adds a message unless we already have it. Returns true if it is new. */
    @Synchronized
    fun addMsg(chatId: String, m: Msg): Boolean {
        val db = writableDatabase
        // Same text from the same side in the last 10 minutes = duplicate (notifications repeat old lines)
        val dup = db.rawQuery(
            "SELECT 1 FROM msgs WHERE chat_id=? AND from_me=? AND text=? AND time>? LIMIT 1",
            arrayOf(chatId, if (m.fromMe) "1" else "0", m.text, (m.time - 10 * 60_000).toString())
        ).use { it.moveToFirst() }
        if (dup) return false
        db.insert("msgs", null, ContentValues().apply {
            put("chat_id", chatId); put("from_me", if (m.fromMe) 1 else 0)
            put("sender", m.sender); put("text", m.text); put("time", m.time)
        })
        db.execSQL("DELETE FROM msgs WHERE chat_id=? AND id NOT IN (SELECT id FROM msgs WHERE chat_id=? ORDER BY time DESC LIMIT 200)", arrayOf(chatId, chatId))
        return true
    }

    /**
     * Merge messages read from the screen into the saved history, keeping order:
     * new lines at the bottom are appended, older lines found by scrolling up are put before.
     */
    @Synchronized
    fun mergeScreen(chatId: String, seen: List<Msg>) {
        if (seen.isEmpty()) return
        val have = recent(chatId, 200)
        fun same(a: Msg, b: Msg) = a.fromMe == b.fromMe && a.text == b.text
        val now = System.currentTimeMillis()
        val db = writableDatabase
        fun ins(m: Msg, t: Long) = db.insert("msgs", null, ContentValues().apply {
            put("chat_id", chatId); put("from_me", if (m.fromMe) 1 else 0); put("sender", m.sender); put("text", m.text); put("time", t)
        })
        if (have.isEmpty()) {
            seen.forEachIndexed { i, m -> ins(m, now - (seen.size - i) * 1000L) }
            return
        }
        // 1) screen starts with the end of what we have -> append the rest
        for (k in minOf(have.size, seen.size) downTo 1) {
            val tail = have.subList(have.size - k, have.size)
            if ((0 until k).all { same(tail[it], seen[it]) }) {
                val newOnes = seen.subList(k, seen.size)
                newOnes.forEachIndexed { i, m -> ins(m, maxOf(now, have.last().time + 1) + i) }
                return
            }
        }
        // 2) screen ends with the start of what we have -> older history, put it before
        for (k in minOf(have.size, seen.size) downTo 1) {
            val head = have.subList(0, k)
            val sTail = seen.subList(seen.size - k, seen.size)
            if ((0 until k).all { same(head[it], sTail[it]) }) {
                val older = seen.subList(0, seen.size - k)
                val first = have.first().time
                older.forEachIndexed { i, m -> ins(m, first - (older.size - i) * 1000L) }
                return
            }
        }
        // 3) no overlap: add lines we don't know yet at the end
        val known = have.map { (if (it.fromMe) "1" else "0") + it.text }.toHashSet()
        var t = maxOf(now, have.last().time + 1)
        for (m in seen) if (known.add((if (m.fromMe) "1" else "0") + m.text)) ins(m, t++)
    }

    fun count(chatId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM msgs WHERE chat_id=?", arrayOf(chatId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun recent(chatId: String, n: Int = 20): List<Msg> = readableDatabase.rawQuery(
        "SELECT from_me,sender,text,time FROM msgs WHERE chat_id=? ORDER BY time DESC, id DESC LIMIT $n", arrayOf(chatId)
    ).use { c ->
        val l = ArrayList<Msg>()
        while (c.moveToNext()) l.add(Msg(c.getInt(0) == 1, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
        l.reverse(); l
    }

    fun autoRepliesSince(chatId: String, since: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM msgs WHERE chat_id=? AND from_me=1 AND sender='GAZ AI' AND time>?",
        arrayOf(chatId, since.toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
