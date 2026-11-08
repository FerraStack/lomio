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
#include <functional>
#include <map>
#include <memory>
#include <random>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
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
    mtmd::context_ptr         mtmd;  // vision projector (mmproj), null for text-only models

    // What the KV cache (seq 0) holds, one entry per cell: text tokens, or negative
    // placeholders (see image_entry) for the cells of an image. cached_pos[i] is the position of
    // entry i (all cells of an image share the image's start position, so a cut never splits
    // one); n_past is the position the next token goes to. Without images, pos == index.
    std::vector<llama_token>  cached;
    std::vector<llama_pos>    cached_pos;
    llama_pos                 n_past = 0;

    // Decoded images of the last turn, keyed by file path (re-tokenizing a chat re-uses them).
    std::map<std::string, mtmd::bitmap_ptr> bitmaps;

    void clear_cached() {
        cached.clear();
        cached_pos.clear();
        n_past = 0;
    }

    // Keeps the first n entries of `cached` (no-op if there are fewer).
    void truncate_cached(size_t n) {
        if (n >= cached.size()) return;
        n_past = cached_pos[n];
        cached.resize(n);
        cached_pos.resize(n);
    }

    ~Session() {
        tmpls.reset();
        bitmaps.clear();
        mtmd.reset();
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

// A tokenized prompt: text tokens plus image chunks, flattened to one entry per KV cell so it
// can be compared with Session::cached for prefix reuse.
struct Prompt {
    std::vector<llama_token> tokens;  // image cells hold a negative per-image placeholder
    std::vector<llama_pos>   pos;     // position of each entry (image cells: the image's start)
    llama_pos                end_pos = 0;
    mtmd::input_chunks_ptr   chunks;  // owns the image chunks below (null for text-only prompts)
    std::map<size_t, const mtmd_input_chunk *> images;  // first entry index -> image chunk

    size_t size() const { return tokens.size(); }
    bool empty() const { return tokens.empty(); }

    void add_text(llama_token t) {
        tokens.push_back(t);
        pos.push_back(end_pos++);
    }
};

// Placeholder token for the cells of one image: negative (never a real token id) and derived
// from the image's content hash, so the same picture in the same place matches across turns.
llama_token image_entry(const char * id) {
    const size_t h = std::hash<std::string>{}(id != nullptr ? id : "");
    return -static_cast<llama_token>(h % 0x3FFFFFFF) - 2;
}

bool is_image_entry(llama_token t) { return t < -1; }

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

// Decodes prompt entries [from..] into seq 0: text n_batch tokens at a time, images through
// libmtmd (encode with the projector, then decode the embeddings). Only the last token gets
// logits. Returns 0 = ok, -100 = cancelled, otherwise the failing status. s->cached always
// matches what is in the KV cache up to the last fully decoded batch or image, so a cancelled
// prompt keeps the part that was already processed (issue #3). `on_batch(done)` runs after each
// batch or image.
template <typename F>
int decode_prompt(Session * s, const Prompt & prompt, size_t from, F && on_batch) {
    const int n_batch = static_cast<int>(llama_n_batch(s->ctx));
    const size_t n = prompt.size();
    common_batch batch(s->ctx);
    size_t i = from;
    while (i < n) {
        if (is_cancelled()) return -100;
        const auto img = prompt.images.find(i);
        if (img != prompt.images.end()) {
            const mtmd_input_chunk * chunk = img->second;
            const size_t n_tok = mtmd_input_chunk_get_n_tokens(chunk);
            llama_pos new_n_past = prompt.pos[i];
            const int64_t t0 = now_us();
            const int ret = mtmd_helper_eval_chunk_single(s->mtmd.get(), s->ctx, chunk, prompt.pos[i], 0, n_batch,
                                                          /*logits_last=*/false, &new_n_past);
            if (ret != 0) {
                if (is_cancelled()) return -100;
                LOGE("image eval failed: %d", ret);
                return -2;  // not a KV-full status: reported as an error
            }
            LOGI("image: %zu tokens encoded and decoded in %lld ms", n_tok, static_cast<long long>((now_us() - t0) / 1000));
            s->cached.insert(s->cached.end(), prompt.tokens.begin() + i, prompt.tokens.begin() + i + n_tok);
            s->cached_pos.insert(s->cached_pos.end(), n_tok, prompt.pos[i]);
            s->n_past = prompt.pos[i] + mtmd_input_chunk_get_n_pos(chunk);
            i += n_tok;
            on_batch(i);
            continue;
        }
        // Text up to n_batch tokens, stopping before the next image.
        size_t end = std::min(n, i + static_cast<size_t>(n_batch));
        const auto next_img = prompt.images.lower_bound(i);
        if (next_img != prompt.images.end() && next_img->first < end) end = next_img->first;
        batch.clear();
        for (size_t j = i; j < end; ++j) batch.add(prompt.tokens[j], prompt.pos[j], 0, j == n - 1);
        const int ret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (ret == 2) return -100;  // aborted by abort_callback (stop requested mid-batch)
        if (ret != 0) return ret;
        s->cached.insert(s->cached.end(), prompt.tokens.begin() + i, prompt.tokens.begin() + end);
        s->cached_pos.insert(s->cached_pos.end(), prompt.pos.begin() + i, prompt.pos.begin() + end);
        s->n_past = prompt.pos[end - 1] + 1;
        i = end;
        on_batch(end);
    }
    return 0;
}

// After a stopped or failed decode: drop whatever the KV cache holds past s->cached (an
// aborted batch can leave some of its micro-batches behind). Falls back to clearing everything
// when the memory can't drop a tail (recurrent models) or after a real decode error.
void settle_cache(Session * s, bool keep_prefix) {
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (keep_prefix && llama_memory_seq_rm(mem, 0, s->n_past, -1)) return;
    llama_memory_clear(mem, true);
    s->clear_cached();
}

// Media marker put into the chat template for image K: "<__lomio_media_<nonce>_K__>". The
// random nonce keeps user-typed text from ever matching it.
const std::string & media_marker_prefix() {
    static const std::string prefix = [] {
        std::random_device rd;
        return "<__lomio_media_" + std::to_string(rd() % 1000000) + "_";
    }();
    return prefix;
}

std::string media_marker(size_t k) { return media_marker_prefix() + std::to_string(k) + "__>"; }

// Tokenizes a rendered chat prompt. Without images this is the plain per-part tokenization
// (template text may contain special tokens, user input may not). With images, every media
// marker is replaced by the image's chunk from libmtmd (which also adds the model's own
// image begin/end tokens).
Prompt tokenize_prompt(Session * s, const common_chat_prompt & rendered, const std::vector<std::string> & image_paths) {
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    Prompt out;
    if (llama_vocab_get_add_bos(vocab)) out.add_text(llama_vocab_bos(vocab));

    if (image_paths.empty() || !s->mtmd) {
        for (const auto & part : rendered.parts) {
            for (llama_token t : common_tokenize(vocab, part.text, /*add_special=*/false, /*parse_special=*/!part.is_input)) {
                out.add_text(t);
            }
        }
        return out;
    }

    // Split every part on the markers; keep the pieces alive for mtmd_tokenize_from_parts.
    struct Piece { std::string text; bool special; int image = -1; };
    std::vector<Piece> pieces;
    const std::string & prefix = media_marker_prefix();
    for (const auto & part : rendered.parts) {
        size_t at = 0;
        while (at <= part.text.size()) {
            const size_t m = part.text.find(prefix, at);
            const size_t close = m == std::string::npos ? std::string::npos : part.text.find("__>", m + prefix.size());
            if (m == std::string::npos || close == std::string::npos) {
                if (at < part.text.size()) pieces.push_back({part.text.substr(at), !part.is_input});
                break;
            }
            if (m > at) pieces.push_back({part.text.substr(at, m - at), !part.is_input});
            const std::string idx = part.text.substr(m + prefix.size(), close - m - prefix.size());
            const bool numeric = !idx.empty() && idx.size() < 7 && idx.find_first_not_of("0123456789") == std::string::npos;
            const int k = numeric ? std::stoi(idx) : -1;
            if (k >= 0 && k < static_cast<int>(image_paths.size())) {
                pieces.push_back({"", false, k});
            } else {
                pieces.push_back({part.text.substr(m, close + 3 - m), !part.is_input});
            }
            at = close + 3;
        }
    }

    // Decode each picture once and keep it for the next turn.
    std::map<std::string, mtmd::bitmap_ptr> keep;
    for (const auto & p : pieces) {
        if (p.image < 0) continue;
        const std::string & path = image_paths[p.image];
        if (keep.count(path)) continue;
        auto it = s->bitmaps.find(path);
        if (it != s->bitmaps.end()) {
            keep.emplace(path, std::move(it->second));
            continue;
        }
        mtmd_helper_bitmap_wrapper w =
            mtmd_helper_bitmap_init_from_file(s->mtmd.get(), path.c_str(), false, mtmd_helper_init_opt_default());
        if (w.video_ctx != nullptr) mtmd_helper_video_free(w.video_ctx);
        if (w.bitmap == nullptr) throw JniError("can't read image: " + path);
        keep.emplace(path, mtmd::bitmap_ptr(w.bitmap));
    }
    s->bitmaps = std::move(keep);

    std::vector<mtmd_input_text> texts(pieces.size());
    std::vector<mtmd_input_part> parts(pieces.size());
    std::vector<const mtmd_input_part *> part_ptrs(pieces.size());
    for (size_t i = 0; i < pieces.size(); ++i) {
        if (pieces[i].image >= 0) {
            parts[i] = {nullptr, s->bitmaps.at(image_paths[pieces[i].image]).get()};
        } else {
            texts[i] = {pieces[i].text.c_str(), pieces[i].text.size(), false, pieces[i].special};
            parts[i] = {&texts[i], nullptr};
        }
        part_ptrs[i] = &parts[i];
    }
    out.chunks.reset(mtmd_input_chunks_init());
    const int32_t res = mtmd_tokenize_from_parts(s->mtmd.get(), out.chunks.get(), part_ptrs.data(), part_ptrs.size(),
                                                 /*add_special=*/false);
    if (res != 0) throw JniError("image tokenization failed (" + std::to_string(res) + ")");

    for (size_t c = 0, n_chunks = mtmd_input_chunks_size(out.chunks.get()); c < n_chunks; ++c) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(out.chunks.get(), c);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n_tok = 0;
            const llama_token * toks = mtmd_input_chunk_get_tokens_text(chunk, &n_tok);
            for (size_t j = 0; j < n_tok; ++j) out.add_text(toks[j]);
        } else {
            const size_t n_tok = mtmd_input_chunk_get_n_tokens(chunk);
            const llama_token entry = image_entry(mtmd_input_chunk_get_id(chunk));
            out.images.emplace(out.tokens.size(), chunk);
            out.tokens.insert(out.tokens.end(), n_tok, entry);
            out.pos.insert(out.pos.end(), n_tok, out.end_pos);
            out.end_pos += mtmd_input_chunk_get_n_pos(chunk);
        }
    }
    return out;
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
        mtmd_helper_log_set(log_callback, nullptr);
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

// jtemplate: optional Jinja chat template overriding the GGUF's (null = use the GGUF's).
// jmmproj: path of the vision projector GGUF, or null for a text-only load.
JNI_FN(jlong, nativeLoad)(JNIEnv * env, jobject, jbyteArray jpath, jint n_ctx, jint n_threads, jint n_batch,
                         jint n_gpu_layers, jboolean use_mmap, jbyteArray jtemplate, jbyteArray jmmproj) {
    return guarded<jlong>(env, 0, [&]() -> jlong {
        const std::string path = bytes_to_string(env, jpath);
        const std::string mmproj = bytes_to_string(env, jmmproj);
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
        // Some projectors (e.g. Gemma 3) need all of an image's tokens in one micro-batch.
        if (!mmproj.empty()) cp.n_batch = std::max<uint32_t>(cp.n_batch, 512);
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

        const std::string override_tmpl = jtemplate != nullptr ? bytes_to_string(env, jtemplate) : std::string();
        if (!override_tmpl.empty()) {
            try {
                auto t = common_chat_templates_init(s->model, override_tmpl);
                common_chat_format_example(t.get(), /*use_jinja=*/true, {});  // throws if it can't render
                s->tmpls = std::move(t);
                LOGI("using the chat template override (%zu bytes)", override_tmpl.size());
            } catch (const std::exception & e) {
                LOGW("chat template override unusable (%s), using the GGUF's", e.what());
            }
        }
        if (!s->tmpls) {
            try {
                s->tmpls = common_chat_templates_init(s->model, "");
            } catch (const std::exception & e) {
                LOGW("chat template from GGUF unusable (%s), falling back to ChatML", e.what());
                s->tmpls = common_chat_templates_init(s->model, CHATML_TEMPLATE);
            }
        }

        // Vision projector. Its warm-up encode runs here too, under the crash-loop guard.
        if (!mmproj.empty()) {
            mtmd_context_params mparams = mtmd_context_params_default();
            mparams.use_gpu = n_gpu_layers > 0;
            mparams.n_threads = threads;
            mparams.print_timings = false;
            mparams.warmup = true;
            const int64_t t0 = now_us();
            s->mtmd.reset(mtmd_init_from_file(mmproj.c_str(), s->model, mparams));
            if (!s->mtmd) throw JniError("failed to load the vision projector: " + mmproj);
            if (!mtmd_support_vision(s->mtmd.get())) {
                LOGW("projector %s has no vision input; ignoring it", mmproj.c_str());
                s->mtmd.reset();
            } else {
                LOGI("vision projector loaded in %lld ms", static_cast<long long>((now_us() - t0) / 1000));
            }
        }
        LOGI("model loaded: n_ctx=%u threads=%d vision=%d", llama_n_ctx(s->ctx), threads, s->mtmd ? 1 : 0);
        return reinterpret_cast<jlong>(s.release());
    });
}

// Formats a conversation with a Jinja template and no model (template previews). Stateless.
JNI_FN(jbyteArray, nativeFormatChat)(JNIEnv * env, jobject, jbyteArray jtemplate, jbyteArray jbos, jbyteArray jeos,
                                    jobjectArray jroles, jobjectArray jcontents, jboolean add_generation_prompt) {
    return guarded<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        const std::string tmpl = bytes_to_string(env, jtemplate);
        if (tmpl.empty()) throw JniError("empty template");
        auto tmpls = common_chat_templates_init(nullptr, tmpl, bytes_to_string(env, jbos), bytes_to_string(env, jeos));
        common_chat_templates_inputs inputs;
        for (jsize i = 0, n = env->GetArrayLength(jroles); i < n; ++i) {
            common_chat_msg m;
            m.role = element_string(env, jroles, i);
            m.content = element_string(env, jcontents, i);
            inputs.messages.push_back(std::move(m));
        }
        inputs.add_generation_prompt = add_generation_prompt == JNI_TRUE;
        inputs.use_jinja = true;
        return string_to_bytes(env, common_chat_templates_apply(tmpls.get(), inputs).prompt.str());
    });
}

JNI_FN(void, nativeFree)(JNIEnv * env, jobject, jlong handle) {
    guarded<int>(env, 0, [&] {
        delete reinterpret_cast<Session *>(handle);
        return 0;
    });
}

// longs: [nParams, sizeBytes, nCtxTrain, nCtx, nLayer, supportsThinking(0/1), supportsVision(0/1)]
// strings: [description, architecture, chatTemplate]
JNI_FN(void, nativeModelInfo)(JNIEnv * env, jobject, jlong handle, jlongArray jlongs, jobjectArray jstrings) {
    guarded<int>(env, 0, [&] {
        Session * s = as_session(handle);
        bool thinking = false;
        try {
            thinking = s->tmpls && common_chat_templates_support_enable_thinking(s->tmpls.get());
        } catch (...) {}
        jlong vals[7] = {
            static_cast<jlong>(llama_model_n_params(s->model)),
            static_cast<jlong>(llama_model_size(s->model)),
            llama_model_n_ctx_train(s->model),
            static_cast<jlong>(llama_n_ctx(s->ctx)),
            llama_model_n_layer(s->model),
            thinking ? 1 : 0,
            s->mtmd ? 1 : 0,
        };
        env->SetLongArrayRegion(jlongs, 0, 7, vals);

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
// images: image file paths of all messages in order; imageCounts[i]: how many belong to message i
// (they go before its text). Ignored when the model was loaded without a vision projector.
JNI_FN(void, nativeChat)(JNIEnv * env, jobject, jlong handle, jobjectArray jroles, jobjectArray jcontents,
                        jobjectArray jreasonings, jobjectArray jimages, jintArray jimage_counts,
                        jboolean enable_thinking, jfloatArray jfloats, jintArray jints,
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
        std::vector<std::string> image_paths;  // index k <-> media_marker(k)
        const jsize n_msgs = env->GetArrayLength(jroles);
        std::vector<jint> image_counts(static_cast<size_t>(n_msgs), 0);
        if (env->GetArrayLength(jimage_counts) == n_msgs && n_msgs > 0) {
            env->GetIntArrayRegion(jimage_counts, 0, n_msgs, image_counts.data());
        }
        jsize next_image = 0;
        const jsize n_images = env->GetArrayLength(jimages);
        for (jsize i = 0; i < n_msgs; ++i) {
            common_chat_msg m;
            m.role = element_string(env, jroles, i);
            m.content = element_string(env, jcontents, i);
            m.reasoning_content = element_string(env, jreasonings, i);
            if (image_counts[i] > 0 && s->mtmd) {
                // Images first, then the text: the order most vision chat templates expect.
                for (jint k = 0; k < image_counts[i] && next_image < n_images; ++k) {
                    image_paths.push_back(element_string(env, jimages, next_image++));
                    m.content_parts.push_back({"media_marker", media_marker(image_paths.size() - 1), ""});
                }
                if (!m.content.empty()) m.content_parts.push_back({"text", m.content, ""});
                m.content.clear();
            } else {
                next_image += std::max<jint>(0, image_counts[i]);
            }
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
        Prompt prompt;
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

            prompt = tokenize_prompt(s, chat->prompt(), image_paths);

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
        while (n_keep < s->cached.size() && n_keep < prompt.size() && s->cached[n_keep] == prompt.tokens[n_keep] &&
               s->cached_pos[n_keep] == prompt.pos[n_keep]) {
            ++n_keep;
        }
        if (n_keep == prompt.size()) --n_keep;  // always decode at least one token for fresh logits
        // Never keep part of an image: back off to its first cell.
        while (n_keep > 0 && is_image_entry(prompt.tokens[n_keep]) && prompt.tokens[n_keep - 1] == prompt.tokens[n_keep]) {
            --n_keep;
        }
        llama_memory_t mem = llama_get_memory(s->ctx);
        if (n_keep < s->cached.size()) {
            if (!llama_memory_seq_rm(mem, 0, s->cached_pos[n_keep], -1)) {
                llama_memory_clear(mem, true);  // e.g. recurrent models can't drop a tail
                n_keep = 0;
            }
            s->truncate_cached(n_keep);
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
        llama_pos pos = s->n_past;
        for (;;) {
            if (is_cancelled()) { reason = STOP_CANCELLED; break; }
            if (max_tokens > 0 && n_gen >= max_tokens) { reason = STOP_MAX_TOKENS; break; }
            if (static_cast<int>(s->cached.size()) >= n_ctx) { reason = STOP_CONTEXT_FULL; break; }

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
            s->cached_pos.push_back(pos);
            s->n_past = ++pos;

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
        s->clear_cached();  // the bench overwrites the KV cache

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
