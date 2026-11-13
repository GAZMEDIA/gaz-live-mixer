package ng.gaz.ai

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** GAZ AI's own built-in AI: a small open model (Qwen 2.5) running fully on the phone through llama.cpp. */
object LocalLlm {

    data class Model(val id: String, val label: String, val file: String, val url: String, val approxMb: Int)

    val MODELS = listOf(
        Model("small", "Small · about 0.4 GB · fast, works on most phones",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf", 400),
        Model("better", "Better · about 1.1 GB · smarter, needs 6 GB+ RAM",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf", 1100)
    )

    private const val PREF = "local_model"
    private var libOk: Boolean? = null
    private var handle = 0L
    private var loadedFile: String? = null
    private val lock = Any()
    @Volatile var downloading = false
        private set

    @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temp: Float): String
    @JvmStatic private external fun nativeFree(handle: Long)

    fun supported(): Boolean {
        libOk?.let { return it }
        val ok = runCatching { System.loadLibrary("gazllm"); true }.getOrDefault(false)
        libOk = ok
        return ok
    }

    fun selected(ctx: Context): Model = MODELS.firstOrNull { it.id == Prefs.str(ctx, PREF, "small") } ?: MODELS[0]
    fun select(ctx: Context, id: String) = Prefs.putStr(ctx, PREF, id)

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun fileFor(ctx: Context, m: Model) = File(dir(ctx), m.file)
    fun isReady(ctx: Context, m: Model = selected(ctx)) = fileFor(ctx, m).let { it.exists() && it.length() > 50_000_000 }
    fun anyReady(ctx: Context) = MODELS.any { isReady(ctx, it) }

    /** Download the model (resumes if interrupted). [progress] gets (doneBytes, totalBytes). */
    fun download(ctx: Context, m: Model, progress: (Long, Long) -> Unit) {
        if (downloading) throw IllegalStateException("A download is already running")
        downloading = true
        try {
            val dest = fileFor(ctx, m)
            val part = File(dest.path + ".part")
            var done = if (part.exists()) part.length() else 0L
            var url = m.url
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "GAZ-AI/1.0")
                if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 6) {
                    val loc = conn.getHeaderField("Location") ?: break
                    url = URL(URL(url), loc).toString()
                    conn.disconnect()
                    continue
                }
                break
            }
            val code = conn.responseCode
            if (code == 416) { part.renameTo(dest); return }        // already complete
            if (code !in 200..299) throw RuntimeException("Download failed (HTTP $code)")
            if (code == 200) done = 0                                // server ignored resume
            val total = done + conn.contentLengthLong.coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(done)
                out.seek(done)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (done - last > 2_000_000) { last = done; progress(done, total) }
                    }
                }
            }
            conn.disconnect()
            progress(done, total)
            if (total > 0 && done < total) throw RuntimeException("Download was interrupted. Tap Download again to continue.")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw RuntimeException("Could not save the model file")
        } finally {
            downloading = false
        }
    }

    fun delete(ctx: Context, m: Model) {
        synchronized(lock) {
            if (loadedFile == fileFor(ctx, m).path) { nativeFree(handle); handle = 0; loadedFile = null }
        }
        fileFor(ctx, m).delete()
        File(fileFor(ctx, m).path + ".part").delete()
    }

    private fun ensureLoaded(ctx: Context): Long {
        if (!supported()) throw Brain.BrainError("The built-in AI isn't supported on this phone's processor.")
        val m = if (isReady(ctx)) selected(ctx) else MODELS.firstOrNull { isReady(ctx, it) }
            ?: throw Brain.BrainError("Download the built-in AI first (GAZ AI settings → AI brain → Built-in AI).")
        val path = fileFor(ctx, m).path
        synchronized(lock) {
            if (handle != 0L && loadedFile == path) return handle
            if (handle != 0L) { nativeFree(handle); handle = 0 }
            val threads = Runtime.getRuntime().availableProcessors().let { (it - 2).coerceIn(2, 6) }
            val h = nativeLoad(path, 2048, threads)
            if (h == 0L) throw Brain.BrainError("Couldn't start the built-in AI. The phone may be low on memory: try the Small model.")
            handle = h; loadedFile = path
            return h
        }
    }

    /** Qwen chat format; returns the raw text the model writes. */
    fun chat(ctx: Context, system: String, user: String, maxTokens: Int = 200): String {
        val h = ensureLoaded(ctx)
        val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        val out = synchronized(lock) { nativeGenerate(h, prompt, maxTokens, 0.8f) }
        if (out.isBlank()) throw Brain.BrainError("The built-in AI gave no answer. Try again.")
        return out
    }
}
