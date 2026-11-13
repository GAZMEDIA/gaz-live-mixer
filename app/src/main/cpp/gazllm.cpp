// GAZ AI built-in (offline) AI: a thin JNI bridge to llama.cpp.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "GazLLM", __VA_ARGS__)

namespace {
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex lock;
};
std::once_flag backend_once;

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, big.data(), (int32_t) big.size(), 0, false);
        return n > 0 ? big.substr(0, n) : std::string();
    }
    return std::string(buf, n);
}

// Make sure we only hand complete UTF-8 characters to Java
size_t valid_utf8_prefix(const std::string &s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
        last = i;
    }
    return last;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ng_gaz_ai_LocalLlm_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOG("model load failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = (uint32_t) nCtx;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOG("context failed"); return 0; }

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    return (jlong) e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ng_gaz_ai_LocalLlm_nativeGenerate(JNIEnv *env, jclass, jlong handle, jstring jprompt, jint maxTokens, jfloat temp) {
    auto *e = (Engine *) handle;
    if (!e) return env->NewStringUTF("");
    std::lock_guard<std::mutex> g(e->lock);

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    llama_memory_clear(llama_get_memory(e->ctx), true);

    // Tokenize (the prompt already contains the chat template's special tokens)
    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true) < 0)
        return env->NewStringUTF("");
    const int nCtx = (int) llama_n_ctx(e->ctx);
    const int budget = nCtx - maxTokens - 4;
    if ((int) toks.size() > budget && budget > 64) {
        // keep the start (instructions) and the end (latest messages)
        std::vector<llama_token> cut(toks.begin(), toks.begin() + budget / 3);
        cut.insert(cut.end(), toks.end() - (budget - budget / 3), toks.end());
        toks.swap(cut);
    }

    // Feed the prompt in chunks of n_batch
    const int nBatch = (int) llama_n_batch(e->ctx);
    for (size_t i = 0; i < toks.size(); i += nBatch) {
        int len = (int) std::min((size_t) nBatch, toks.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, len)) != 0) return env->NewStringUTF("");
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    for (int i = 0; i < maxTokens; i++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        out += piece(e->vocab, t);
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(smpl);
    out.resize(valid_utf8_prefix(out));
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ng_gaz_ai_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *e = (Engine *) handle;
    if (!e) return;
    std::lock_guard<std::mutex> g(e->lock);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    e->ctx = nullptr; e->model = nullptr;
    delete e;
}
