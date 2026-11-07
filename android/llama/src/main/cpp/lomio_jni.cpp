// Lomio JNI bridge over llama.cpp (v0.9.0 API).
//
// Ideas (backend loading, batch decoding, bench loop) follow llama.cpp's own MIT-licensed
// examples/llama.android (ai_chat.cpp, Copyright (c) 2023-2026 The ggml authors); the code
// here is Lomio's own.
//
// Conventions:
//  * All strings cross the boundary as UTF-8 byte arrays (JNI's "modified UTF-8" mangles
//    emoji / supplementary characters).
//  * Every entry point catches C++ exceptions and rethrows dev.lomio.llama.LlamaException.
//  * The Kotlin side calls everything except nativeStop() from one dedicated thread.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "lomio-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Must match dev.lomio.llama.StopReason ordinals.
enum StopReason { STOP_EOS = 0, STOP_STRING = 1, STOP_MAX_TOKENS = 2, STOP_CONTEXT_FULL = 3, STOP_CANCELLED = 4, STOP_ERROR = 5 };

// Fallback when the GGUF's own template cannot be parsed.
const char * const CHATML_TEMPLATE =
    "{% for message in messages %}{{'<|im_start|>' + message['role'] + '\\n' + message['content'] + "
    "'<|im_end|>' + '\\n'}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}";

constexpr int64_t EMIT_INTERVAL_US = 50 * 1000;

// Cancellation is per operation: Kotlin numbers every chat/bench call (op id ≥ 1) and stop()
// cancels "everything up to id N". A stop that arrives before the native call starts is
// therefore not lost, and a stale stop can't cancel the next call.
std::atomic<int64_t> g_cancel_upto{0};
std::atomic<int64_t> g_current_op{0};  // 0 = no cancellable op running (e.g. load warm-up)

bool is_cancelled() {
    const int64_t cur = g_current_op.load();
    return cur > 0 && g_cancel_upto.load() >= cur;
}

void cancel_upto(int64_t id) {
    int64_t prev = g_cancel_upto.load();
    while (prev < id && !g_cancel_upto.compare_exchange_weak(prev, id)) {}
}

// Marks the running op for the duration of a native call.
struct OpScope {
    explicit OpScope(int64_t id) { g_current_op.store(id); }
    ~OpScope() { g_current_op.store(0); }
};

struct Session {
    llama_model *             model = nullptr;
    llama_context *           ctx   = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<llama_token>  cached;  // tokens currently in the KV cache (seq 0)

    ~Session() {
        tmpls.reset();
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

struct JniError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

void throw_java(JNIEnv * env, const std::string & msg) {
    if (env->ExceptionCheck()) return;  // keep the original exception
    jclass cls = env->FindClass("dev/lomio/llama/LlamaException");
    if (cls == nullptr) {
        env->ExceptionClear();
        cls = env->FindClass("java/lang/RuntimeException");
    }
    env->ThrowNew(cls, msg.c_str());
}

std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::string element_string(JNIEnv * env, jobjectArray arr, jsize i) {
    auto * elem = static_cast<jbyteArray>(env->GetObjectArrayElement(arr, i));
    std::string out = bytes_to_string(env, elem);
    if (elem != nullptr) env->DeleteLocalRef(elem);
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr == nullptr) throw JniError("out of memory");
    if (!s.empty()) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

Session * as_session(jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->model == nullptr || s->ctx == nullptr) throw JniError("no model loaded");
    return s;
}

// Runs fn, converting any C++ exception into a pending LlamaException.
template <typename R, typename F>
R guarded(JNIEnv * env, R fallback, F && fn) {
    try {
        return fn();
    } catch (const std::exception & e) {
        LOGE("native error: %s", e.what());
        throw_java(env, e.what());
    } catch (...) {
        throw_java(env, "unknown native error");
    }
    return fallback;
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 sequence.
size_t utf8_complete_len(const std::string & s, size_t len) {
    if (len == 0) return 0;
    size_t i = len;
    int back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (len - (i - 1) >= need) ? len : i - 1;
        }
        --i;
        ++back;
    }
    return len;
}

// Number of trailing bytes of `text` that could be the start of a stop string.
size_t partial_stop_len(const std::string & text, const std::vector<std::string> & stops) {
    size_t best = 0;
    for (const auto & stop : stops) {
        const size_t max_k = std::min(stop.size() - 1, text.size());
        for (size_t k = max_k; k > best; --k) {
            if (text.compare(text.size() - k, k, stop, 0, k) == 0) {
                best = k;
                break;
            }
        }
    }
    return best;
}

int default_threads() {
    const long cores = sysconf(_SC_NPROCESSORS_ONLN);
    return static_cast<int>(std::max(2L, std::min(4L, cores - 2)));
}

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

void log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO; break;
        default: break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

// Decodes tokens[from..] into seq 0, n_batch at a time; only the last token gets logits.
// Returns 0 = ok, -100 = cancelled, otherwise llama_process's status. s->cached always matches
// what is in the KV cache up to the last fully decoded batch, so a cancelled prompt keeps the
// part that was already processed (issue #3). `on_batch(done)` runs after each batch.
template <typename F>
int decode_prompt(Session * s, const std::vector<llama_token> & tokens, size_t from, F && on_batch) {
    const int n_batch = static_cast<int>(llama_n_batch(s->ctx));
    common_batch batch(s->ctx);
    for (size_t i = from; i < tokens.size(); i += n_batch) {
        if (is_cancelled()) return -100;
        const size_t end = std::min(tokens.size(), i + static_cast<size_t>(n_batch));
        batch.clear();
        for (size_t j = i; j < end; ++j) {
            batch.add(tokens[j], static_cast<llama_pos>(j), 0, j == tokens.size() - 1);
        }
        const int ret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (ret == 2) return -100;  // aborted by abort_callback (stop requested mid-batch)
        if (ret != 0) return ret;
        s->cached.insert(s->cached.end(), tokens.begin() + i, tokens.begin() + end);
        on_batch(end);
    }
    return 0;
}

// After a stopped or failed decode: drop whatever the KV cache holds past s->cached (an
// aborted batch can leave some of its micro-batches behind). Falls back to clearing everything
// when the memory can't drop a tail (recurrent models) or after a real decode error.
void settle_cache(Session * s, bool keep_prefix) {
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (keep_prefix && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(s->cached.size()), -1)) return;
    llama_memory_clear(mem, true);
    s->cached.clear();
}

void read_gguf_int(const gguf_context * g, const std::string & key, jlong & out) {
    const int64_t id = gguf_find_key(g, key.c_str());
    if (id < 0) return;
    auto scalar = [&](gguf_type t, const void * p, size_t i) -> int64_t {
        switch (t) {
            case GGUF_TYPE_UINT8:  return static_cast<const uint8_t *>(p)[i];
            case GGUF_TYPE_INT8:   return static_cast<const int8_t *>(p)[i];
            case GGUF_TYPE_UINT16: return static_cast<const uint16_t *>(p)[i];
            case GGUF_TYPE_INT16:  return static_cast<const int16_t *>(p)[i];
            case GGUF_TYPE_UINT32: return static_cast<const uint32_t *>(p)[i];
            case GGUF_TYPE_INT32:  return static_cast<const int32_t *>(p)[i];
            case GGUF_TYPE_UINT64: return static_cast<int64_t>(static_cast<const uint64_t *>(p)[i]);
            case GGUF_TYPE_INT64:  return static_cast<const int64_t *>(p)[i];
            default:               return -1;
        }
    };
    const gguf_type type = gguf_get_kv_type(g, id);
    if (type == GGUF_TYPE_ARRAY) {
        // e.g. per-layer head_count_kv: report the maximum
        const gguf_type at = gguf_get_arr_type(g, id);
        const size_t n = gguf_get_arr_n(g, id);
        const void * data = gguf_get_arr_data(g, id);
        int64_t best = -1;
        for (size_t i = 0; i < n; ++i) best = std::max(best, scalar(at, data, i));
        out = best;
    } else {
        out = scalar(type, gguf_get_val_data(g, id), 0);
    }
}

jbyteArray read_gguf_str(JNIEnv * env, const gguf_context * g, const std::string & key) {
    const int64_t id = gguf_find_key(g, key.c_str());
    if (id < 0 || gguf_get_kv_type(g, id) != GGUF_TYPE_STRING) return nullptr;
    return string_to_bytes(env, gguf_get_val_str(g, id));
}

std::string model_meta(const llama_model * model, const char * key) {
    char buf[256];
    const int32_t n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    return n < 0 ? std::string() : std::string(buf);
}

}  // namespace

#define JNI_FN(ret, name) JNIEXPORT ret JNICALL Java_dev_lomio_llama_NativeBridge_##name

extern "C" {

JNI_FN(void, nativeInit)(JNIEnv * env, jobject, jbyteArray jlib_dir) {
    guarded<int>(env, 0, [&] {
        llama_log_set(log_callback, nullptr);
        const std::string dir = bytes_to_string(env, jlib_dir);
        LOGI("loading ggml backends from %s", dir.c_str());
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        return 0;
    });
}

JNI_FN(jbyteArray, nativeSystemInfo)(JNIEnv * env, jobject) {
    return guarded<jbyteArray>(env, nullptr, [&] { return string_to_bytes(env, llama_print_system_info()); });
}

JNI_FN(jlong, nativeLoad)(JNIEnv * env, jobject, jbyteArray jpath, jint n_ctx, jint n_threads, jint n_batch,
                         jint n_gpu_layers, jboolean use_mmap) {
    return guarded<jlong>(env, 0, [&]() -> jlong {
        const std::string path = bytes_to_string(env, jpath);
        auto s = std::make_unique<Session>();

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = n_gpu_layers;
        mp.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        s->model = llama_model_load_from_file(path.c_str(), mp);
        if (s->model == nullptr) throw JniError("failed to load model: " + path);

        const int threads = n_threads > 0 ? n_threads : default_threads();
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 4096;
        cp.n_batch = n_batch > 0 ? static_cast<uint32_t>(n_batch) : 512;
        cp.n_ubatch = std::min<uint32_t>(cp.n_batch, 512);
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.no_perf = false;
        // Lets stop() interrupt a long prompt batch instead of waiting for it to finish.
        cp.abort_callback = [](void *) -> bool { return is_cancelled(); };
        cp.abort_callback_data = nullptr;
        s->ctx = llama_init_from_model(s->model, cp);
        if (s->ctx == nullptr) throw JniError("failed to create context (n_ctx=" + std::to_string(cp.n_ctx) + "); try a smaller context");

        // Warm-up decode of one token: allocates compute buffers now, so a model that can't run
        // fails (or crashes) inside load - where the crash-loop guard is armed - not on first chat.
        {
            const llama_vocab * vocab = llama_model_get_vocab(s->model);
            llama_token tok = llama_vocab_bos(vocab);
            if (tok < 0) tok = 0;
            common_batch warm(s->ctx);
            warm.add(tok, 0, 0, true);
            const int wret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, warm.get());
            llama_memory_clear(llama_get_memory(s->ctx), true);
            if (wret != 0) throw JniError("warm-up decode failed (" + std::to_string(wret) + ")");
        }

        try {
            s->tmpls = common_chat_templates_init(s->model, "");
        } catch (const std::exception & e) {
            LOGW("chat template from GGUF unusable (%s), falling back to ChatML", e.what());
            s->tmpls = common_chat_templates_init(s->model, CHATML_TEMPLATE);
        }
        LOGI("model loaded: n_ctx=%u threads=%d", llama_n_ctx(s->ctx), threads);
        return reinterpret_cast<jlong>(s.release());
    });
}

JNI_FN(void, nativeFree)(JNIEnv * env, jobject, jlong handle) {
    guarded<int>(env, 0, [&] {
        delete reinterpret_cast<Session *>(handle);
        return 0;
    });
}

// longs: [nParams, sizeBytes, nCtxTrain, nCtx, nLayer, supportsThinking(0/1)]
// strings: [description, architecture, chatTemplate]
JNI_FN(void, nativeModelInfo)(JNIEnv * env, jobject, jlong handle, jlongArray jlongs, jobjectArray jstrings) {
    guarded<int>(env, 0, [&] {
        Session * s = as_session(handle);
        bool thinking = false;
        try {
            thinking = s->tmpls && common_chat_templates_support_enable_thinking(s->tmpls.get());
        } catch (...) {}
        jlong vals[6] = {
            static_cast<jlong>(llama_model_n_params(s->model)),
            static_cast<jlong>(llama_model_size(s->model)),
            llama_model_n_ctx_train(s->model),
            static_cast<jlong>(llama_n_ctx(s->ctx)),
            llama_model_n_layer(s->model),
            thinking ? 1 : 0,
        };
        env->SetLongArrayRegion(jlongs, 0, 6, vals);

        char desc[256];
        llama_model_desc(s->model, desc, sizeof(desc));
        env->SetObjectArrayElement(jstrings, 0, string_to_bytes(env, desc));
        const std::string arch = model_meta(s->model, "general.architecture");
        if (!arch.empty()) env->SetObjectArrayElement(jstrings, 1, string_to_bytes(env, arch));
        const char * tmpl = llama_model_chat_template(s->model, nullptr);
        if (tmpl != nullptr) env->SetObjectArrayElement(jstrings, 2, string_to_bytes(env, tmpl));
        return 0;
    });
}

// longs (-1 = missing): [fileType, nCtxTrain, nLayer, nEmbd, nHead, nHeadKv, keyLength, valueLength, nTensors, nParams]
// strings: [architecture, name, sizeLabel, chatTemplate]
JNI_FN(jboolean, nativeReadGguf)(JNIEnv * env, jobject, jbyteArray jpath, jlongArray jlongs, jobjectArray jstrings) {
    return guarded<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
        const std::string path = bytes_to_string(env, jpath);
        gguf_init_params params{/*no_alloc =*/true, /*ctx =*/nullptr};
        gguf_context * g = gguf_init_from_file(path.c_str(), params);
        if (g == nullptr) return JNI_FALSE;
        std::unique_ptr<gguf_context, void (*)(gguf_context *)> guard(g, gguf_free);

        std::string arch;
        {
            const int64_t id = gguf_find_key(g, "general.architecture");
            if (id >= 0 && gguf_get_kv_type(g, id) == GGUF_TYPE_STRING) arch = gguf_get_val_str(g, id);
        }
        jlong v[10];
        std::fill(std::begin(v), std::end(v), -1);
        read_gguf_int(g, "general.file_type", v[0]);
        if (!arch.empty()) {
            read_gguf_int(g, arch + ".context_length", v[1]);
            read_gguf_int(g, arch + ".block_count", v[2]);
            read_gguf_int(g, arch + ".embedding_length", v[3]);
            read_gguf_int(g, arch + ".attention.head_count", v[4]);
            read_gguf_int(g, arch + ".attention.head_count_kv", v[5]);
            read_gguf_int(g, arch + ".attention.key_length", v[6]);
            read_gguf_int(g, arch + ".attention.value_length", v[7]);
        }
        const int64_t n_tensors = gguf_get_n_tensors(g);
        int64_t n_params = 0;
        for (int64_t i = 0; i < n_tensors; ++i) {
            const int64_t * ne = gguf_get_tensor_ne(g, i);
            int64_t n = 1;
            for (int d = 0; d < GGML_MAX_DIMS; ++d) n *= ne[d];
            n_params += n;
        }
        v[8] = n_tensors;
        v[9] = n_params;
        env->SetLongArrayRegion(jlongs, 0, 10, v);

        if (!arch.empty()) env->SetObjectArrayElement(jstrings, 0, string_to_bytes(env, arch));
        env->SetObjectArrayElement(jstrings, 1, read_gguf_str(env, g, "general.name"));
        env->SetObjectArrayElement(jstrings, 2, read_gguf_str(env, g, "general.size_label"));
        env->SetObjectArrayElement(jstrings, 3, read_gguf_str(env, g, "tokenizer.chat_template"));
        return JNI_TRUE;
    });
}

JNI_FN(void, nativeStop)(JNIEnv *, jobject, jlong upto_op_id) {
    cancel_upto(static_cast<int64_t>(upto_op_id));
}

// floats: [temperature, topP, minP, repeatPenalty, presencePenalty, frequencyPenalty]
// ints:   [topK, repeatLastN, maxTokens]
// statsOut (doubles): [promptTokens, cachedTokens, generatedTokens, ttftMs, promptTps, genTps, stopReason]
// callback: object with `void onUpdate(byte[] content, byte[] reasoning)`, called throttled
// while generating and exactly once more with the final parse, and
// `void onPromptProgress(int done, int total)`, called before and after each prompt batch
// (`done` counts tokens in the KV cache, reused prefix included).
JNI_FN(void, nativeChat)(JNIEnv * env, jobject, jlong handle, jobjectArray jroles, jobjectArray jcontents,
                        jobjectArray jreasonings, jboolean enable_thinking, jfloatArray jfloats, jintArray jints,
                        jlong seed, jobjectArray jstops, jobject callback, jdoubleArray jstats, jlong op_id) {
    guarded<int>(env, 0, [&] {
        Session * s = as_session(handle);
        OpScope op(static_cast<int64_t>(op_id));

        jclass cb_cls = env->GetObjectClass(callback);
        jmethodID on_update = env->GetMethodID(cb_cls, "onUpdate", "([B[B)V");
        if (on_update == nullptr) throw JniError("callback has no onUpdate([B[B)V");
        jmethodID on_progress = env->GetMethodID(cb_cls, "onPromptProgress", "(II)V");
        if (on_progress == nullptr) throw JniError("callback has no onPromptProgress(II)V");

        // --- inputs -------------------------------------------------------------------
        std::vector<common_chat_msg> all_msgs;
        const jsize n_msgs = env->GetArrayLength(jroles);
        for (jsize i = 0; i < n_msgs; ++i) {
            common_chat_msg m;
            m.role = element_string(env, jroles, i);
            m.content = element_string(env, jcontents, i);
            m.reasoning_content = element_string(env, jreasonings, i);
            all_msgs.push_back(std::move(m));
        }

        float f[6];
        env->GetFloatArrayRegion(jfloats, 0, 6, f);
        jint iv[3];
        env->GetIntArrayRegion(jints, 0, 3, iv);
        std::vector<std::string> stops;
        for (jsize i = 0, n = env->GetArrayLength(jstops); i < n; ++i) {
            std::string st = element_string(env, jstops, i);
            if (!st.empty()) stops.push_back(std::move(st));
        }

        const llama_vocab * vocab = llama_model_get_vocab(s->model);
        const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
        const int max_tokens = iv[2];
        // Room left for the reply; older turns are dropped until the prompt leaves this much.
        const int reserve = max_tokens > 0 ? std::min(max_tokens, n_ctx / 2) : n_ctx / 4;
        const size_t keep_head = (!all_msgs.empty() && all_msgs[0].role == "system") ? 1 : 0;

        std::unique_ptr<common_chat_session> chat;
        std::vector<llama_token> prompt;
        size_t drop = 0;
        for (;;) {
            common_chat_templates_inputs inputs;
            inputs.messages.assign(all_msgs.begin(), all_msgs.begin() + keep_head);
            inputs.messages.insert(inputs.messages.end(), all_msgs.begin() + keep_head + drop, all_msgs.end());
            inputs.add_generation_prompt = true;
            inputs.use_jinja = true;
            inputs.enable_thinking = enable_thinking == JNI_TRUE;
            inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
            try {
                chat = std::make_unique<common_chat_session>(s->tmpls.get(), vocab, inputs);
            } catch (const std::exception & e) {
                LOGW("template apply failed (%s), retrying with ChatML", e.what());
                s->tmpls = common_chat_templates_init(s->model, CHATML_TEMPLATE);
                chat = std::make_unique<common_chat_session>(s->tmpls.get(), vocab, inputs);
            }

            // tokenize: template text may contain special tokens, user input may not
            prompt.clear();
            if (llama_vocab_get_add_bos(vocab)) prompt.push_back(llama_vocab_bos(vocab));
            for (const auto & part : chat->prompt().parts) {
                auto toks = common_tokenize(vocab, part.text, /*add_special=*/false, /*parse_special=*/!part.is_input);
                prompt.insert(prompt.end(), toks.begin(), toks.end());
            }

            const size_t remaining = all_msgs.size() - keep_head - drop;
            if (static_cast<int>(prompt.size()) + reserve <= n_ctx || remaining <= 1) break;
            // Drop the oldest turn (never the system prompt), restarting at a user message.
            ++drop;
            while (keep_head + drop + 1 < all_msgs.size() && all_msgs[keep_head + drop].role != "user") ++drop;
        }
        if (drop > 0) LOGI("context: dropped %zu oldest messages to fit n_ctx=%d", drop, n_ctx);
        for (const auto & st : chat->additional_stops()) {
            if (!st.empty()) stops.push_back(st);
        }

        double stats[7] = {static_cast<double>(prompt.size()), 0, 0, 0, 0, 0, STOP_EOS};
        auto finish_stats = [&] { env->SetDoubleArrayRegion(jstats, 0, 7, stats); };
        auto emit = [&](const std::string & content, const std::string & reasoning) {
            jbyteArray c = string_to_bytes(env, content);
            jbyteArray r = string_to_bytes(env, reasoning);
            env->CallVoidMethod(callback, on_update, c, r);
            env->DeleteLocalRef(c);
            env->DeleteLocalRef(r);
            if (env->ExceptionCheck()) {  // collector gone: stop quietly
                env->ExceptionClear();
                cancel_upto(static_cast<int64_t>(op_id));
            }
        };

        auto progress = [&](size_t done) {
            env->CallVoidMethod(callback, on_progress, static_cast<jint>(done), static_cast<jint>(prompt.size()));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                cancel_upto(static_cast<int64_t>(op_id));
            }
        };

        if (prompt.empty() || static_cast<int>(prompt.size()) >= n_ctx - 4) {
            LOGW("prompt (%zu tokens) does not fit context %d", prompt.size(), n_ctx);
            stats[6] = STOP_CONTEXT_FULL;
            emit("", "");
            finish_stats();
            return 0;
        }

        // --- KV cache prefix reuse ------------------------------------------------------
        size_t n_keep = 0;
        while (n_keep < s->cached.size() && n_keep < prompt.size() && s->cached[n_keep] == prompt[n_keep]) ++n_keep;
        if (n_keep == prompt.size()) --n_keep;  // always decode at least one token for fresh logits
        llama_memory_t mem = llama_get_memory(s->ctx);
        if (n_keep < s->cached.size()) {
            if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_keep), -1)) {
                llama_memory_clear(mem, true);  // e.g. recurrent models can't drop a tail
                n_keep = 0;
            }
            s->cached.resize(n_keep);
        }
        stats[1] = static_cast<double>(n_keep);

        const int64_t t_start = now_us();
        progress(n_keep);
        int ret = decode_prompt(s, prompt, n_keep, progress);
        const int64_t t_prompt = now_us();
        if (ret != 0) {
            // Stopped (or out of KV slots): keep the prefix decoded so far for the next turn.
            const bool keep = ret == -100 || ret == 1;
            settle_cache(s, keep);
            if (keep) LOGI("prompt decode stopped; keeping %zu cached tokens", s->cached.size());
            stats[6] = ret == -100 ? STOP_CANCELLED : (ret == 1 ? STOP_CONTEXT_FULL : STOP_ERROR);
            if (!keep) LOGE("prompt decode failed: %d", ret);
            emit("", "");
            finish_stats();
            return 0;
        }
        const double pp_secs = std::max(1e-6, (t_prompt - t_start) / 1e6);
        stats[4] = (prompt.size() - n_keep) / pp_secs;

        // --- sampler -------------------------------------------------------------------
        common_params_sampling sp;
        sp.temp = f[0];
        sp.top_p = f[1];
        sp.min_p = f[2];
        sp.penalty_repeat = f[3];
        sp.penalty_present = f[4];
        sp.penalty_freq = f[5];
        sp.top_k = iv[0];
        sp.penalty_last_n = iv[1];
        sp.seed = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        chat->apply_sampling(sp);
        const std::set<llama_token> preserved = sp.preserved_tokens;
        std::unique_ptr<common_sampler, void (*)(common_sampler *)> smpl(common_sampler_init(s->model, sp),
                                                                         common_sampler_free);
        if (!smpl) throw JniError("failed to initialise sampler");

        // --- generation loop -------------------------------------------------------------
        common_batch batch(s->ctx);
        common_chat_input raw;  // generated text + token ids
        size_t fed = 0;         // bytes of `raw` already fed to the parser
        std::string content, reasoning;
        auto feed_upto = [&](size_t end) {
            if (end <= fed) return;
            try {
                const common_chat_msg & m = chat->feed(raw.substr(fed, end - fed));
                content = m.content;
                reasoning = m.reasoning_content;
            } catch (const std::exception & e) {
                LOGW("partial parse failed: %s", e.what());
                content = raw.text.substr(0, end);
            }
            fed = end;
        };

        int n_gen = 0;
        int64_t t_first = 0, t_last_emit = 0;
        StopReason reason = STOP_EOS;
        llama_pos pos = static_cast<llama_pos>(prompt.size());
        for (;;) {
            if (is_cancelled()) { reason = STOP_CANCELLED; break; }
            if (max_tokens > 0 && n_gen >= max_tokens) { reason = STOP_MAX_TOKENS; break; }
            if (pos >= n_ctx) { reason = STOP_CONTEXT_FULL; break; }

            const llama_token tok = common_sampler_sample(smpl.get(), s->ctx, -1);
            common_sampler_accept(smpl.get(), tok, true);
            if (n_gen == 0) t_first = now_us();
            if (llama_vocab_is_eog(vocab, tok)) { reason = STOP_EOS; break; }
            ++n_gen;

            const size_t before = raw.size();
            raw.append(common_token_to_piece(vocab, tok, preserved.count(tok) > 0), tok);

            // stop strings: search only the region that could contain a new match
            size_t stop_at = std::string::npos;
            for (const auto & st : stops) {
                const size_t from = before >= st.size() ? before - st.size() + 1 : 0;
                const size_t p = raw.text.find(st, from);
                if (p != std::string::npos && p < stop_at) stop_at = p;
            }
            if (stop_at != std::string::npos) {
                raw.truncate(stop_at);
                reason = STOP_STRING;
                break;
            }

            batch.clear();
            batch.add(tok, pos, 0, true);
            ret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
            if (ret != 0) {
                reason = ret == 1 ? STOP_CONTEXT_FULL : (ret == 2 ? STOP_CANCELLED : STOP_ERROR);
                if (ret != 1 && ret != 2) LOGE("decode failed: %d", ret);
                settle_cache(s, ret == 1 || ret == 2);
                break;
            }
            s->cached.push_back(tok);
            ++pos;

            const int64_t t = now_us();
            if (t - t_last_emit >= EMIT_INTERVAL_US) {
                // hold back bytes that may still become a stop string or are an incomplete UTF-8 sequence
                const size_t safe = utf8_complete_len(raw.text, raw.size() - partial_stop_len(raw.text, stops));
                feed_upto(safe);
                emit(content, reasoning);
                t_last_emit = t;
            }
        }
        const int64_t t_end = now_us();

        // final parse over everything (incomplete UTF-8 at the very end is dropped)
        const size_t final_len = utf8_complete_len(raw.text, raw.size());
        try {
            const common_chat_msg & m = chat->finish(raw.substr(fed, final_len - std::min(fed, final_len)));
            content = m.content;
            reasoning = m.reasoning_content;
        } catch (const std::exception & e) {
            LOGW("final parse failed: %s", e.what());
            content = raw.text.substr(0, final_len);
        }
        emit(content, reasoning);

        stats[2] = n_gen;
        stats[3] = t_first > 0 ? (t_first - t_start) / 1000.0 : 0;
        const double tg_secs = t_first > 0 ? std::max(1e-6, (t_end - t_first) / 1e6) : 0;
        stats[5] = tg_secs > 0 ? n_gen / tg_secs : 0;
        stats[6] = reason;
        finish_stats();
        return 0;
    });
}

// out (doubles): [ppTps, ppStd, tgTps, tgStd]; returns backend description
JNI_FN(jbyteArray, nativeBench)(JNIEnv * env, jobject, jlong handle, jint pp, jint tg, jint reps, jdoubleArray jout,
                               jlong op_id) {
    return guarded<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        Session * s = as_session(handle);
        OpScope op(static_cast<int64_t>(op_id));
        const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
        const int n_batch = static_cast<int>(llama_n_batch(s->ctx));
        pp = std::max(1, std::min(pp, n_ctx - 1));
        tg = std::max(1, std::min(tg, n_ctx - 1));
        reps = std::max(1, reps);
        const llama_token tok = llama_vocab_bos(llama_model_get_vocab(s->model)) >= 0
                                    ? llama_vocab_bos(llama_model_get_vocab(s->model)) : 0;
        llama_memory_t mem = llama_get_memory(s->ctx);
        s->cached.clear();  // the bench overwrites the KV cache

        common_batch batch(s->ctx);
        double pp_sum = 0, pp_sq = 0, tg_sum = 0, tg_sq = 0;
        for (int r = 0; r < reps; ++r) {
            if (is_cancelled()) throw JniError("benchmark cancelled");
            llama_memory_clear(mem, false);
            const int64_t t0 = now_us();
            for (int i = 0; i < pp; i += n_batch) {
                batch.clear();
                const int end = std::min(pp, i + n_batch);
                for (int j = i; j < end; ++j) batch.add(tok, j, 0, j == pp - 1);
                if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) throw JniError("decode failed (pp)");
            }
            const int64_t t1 = now_us();

            llama_memory_clear(mem, false);
            const int64_t t2 = now_us();
            for (int i = 0; i < tg; ++i) {
                if (is_cancelled()) throw JniError("benchmark cancelled");
                batch.clear();
                batch.add(tok, i, 0, true);
                if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) throw JniError("decode failed (tg)");
            }
            const int64_t t3 = now_us();

            const double pps = pp / std::max(1e-6, (t1 - t0) / 1e6);
            const double tps = tg / std::max(1e-6, (t3 - t2) / 1e6);
            pp_sum += pps; pp_sq += pps * pps;
            tg_sum += tps; tg_sq += tps * tps;
        }
        llama_memory_clear(mem, true);

        auto stddev = [&](double sum, double sq) {
            if (reps < 2) return 0.0;
            const double mean = sum / reps;
            return std::sqrt(std::max(0.0, (sq - reps * mean * mean) / (reps - 1)));
        };
        jdouble out[4] = {pp_sum / reps, stddev(pp_sum, pp_sq), tg_sum / reps, stddev(tg_sum, tg_sq)};
        env->SetDoubleArrayRegion(jout, 0, 4, out);

        std::string backends;
        for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
            if (!backends.empty()) backends += ",";
            backends += ggml_backend_reg_name(ggml_backend_reg_get(i));
        }
        return string_to_bytes(env, backends);
    });
}

}  // extern "C"
