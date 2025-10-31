#include <jni.h>
#include <android/log.h>
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <iomanip>
#include <ios>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

constexpr const char * kTag = "PeerChatEngine";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

enum class StopReason {
    None,
    Eos,
    StopSequence,
    MaxTokens,
    Error,
};

struct EngineMetrics {
    int prompt_tokens = 0;
    int generation_tokens = 0;
    double ttfs_ms = 0.0;
    double prefill_ms = 0.0;
    double decode_ms = 0.0;
    double total_ms = 0.0;
    double tps = 0.0;
    double prompt_tps = 0.0;
    double context_used_pct = 0.0;
    bool truncated = false;
};

struct EngineState {
    std::mutex mutex;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;
    int n_gpu_layers = 0;
    bool use_vulkan = true;
    EngineMetrics metrics;
    StopReason stop_reason = StopReason::None;
    std::string stop_sequence;
    std::atomic<bool> should_abort{false};
};

struct StopBuffer {
    explicit StopBuffer(const std::vector<std::string> & stops) : stops_(stops) {
        max_stop_ = 0;
        for (const auto & s : stops_) {
            max_stop_ = std::max(max_stop_, s.size());
        }
    }

    std::string push(const std::string & piece, bool & hit_stop, std::string & matched) {
        hit_stop = false;
        matched.clear();
        pending_.append(piece);
        if (max_stop_ == 0) {
            std::string emit = pending_;
            pending_.clear();
            return emit;
        }
        for (const auto & stop : stops_) {
            if (stop.empty()) {
                continue;
            }
            if (pending_.size() >= stop.size() &&
                pending_.compare(pending_.size() - stop.size(), stop.size(), stop) == 0) {
                hit_stop = true;
                matched = stop;
                std::string emit = pending_.substr(0, pending_.size() - stop.size());
                pending_.clear();
                return emit;
            }
        }
        if (pending_.size() >= max_stop_) {
            const size_t emit_len = pending_.size() - (max_stop_ - 1);
            std::string emit = pending_.substr(0, emit_len);
            pending_.erase(0, emit_len);
            return emit;
        }
        return std::string();
    }

    std::string flush() {
        std::string emit = pending_;
        pending_.clear();
        return emit;
    }

private:
    std::vector<std::string> stops_;
    size_t max_stop_ = 0;
    std::string pending_;
};

struct StreamContext {
    JNIEnv * env = nullptr;
    jobject callback = nullptr;
    jmethodID on_token = nullptr;
};

struct GenerationRequest {
    std::string prompt;
    std::string system_prompt;
    float temperature = 0.8f;
    float top_p = 0.9f;
    int top_k = 40;
    int max_tokens = 512;
    std::vector<std::string> stops;
};

struct GenerationSummary {
    EngineMetrics metrics;
    StopReason reason = StopReason::None;
    std::string stop_sequence;
    bool success = false;
};

EngineState g_state;
bool g_backend_initialized = false;

struct SummaryCommit {
    EngineState & state;
    GenerationSummary & summary;
    ~SummaryCommit() {
        state.metrics = summary.metrics;
        state.stop_reason = summary.reason;
        state.stop_sequence = summary.stop_sequence;
    }
};

bool ensure_backend_init() {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized (Vulkan expected)");
    }
    return true;
}

bool file_exists(const char * path) {
    if (!path) return false;
    struct stat st {};
    if (stat(path, &st) != 0) {
        return false;
    }
    return S_ISREG(st.st_mode);
}

void reset_metrics_locked() {
    g_state.metrics = EngineMetrics{};
    g_state.stop_reason = StopReason::None;
    g_state.stop_sequence.clear();
    g_state.should_abort.store(false, std::memory_order_relaxed);
}

void unload_locked() {
    // Clear abort flag before cleanup
    g_state.should_abort.store(false, std::memory_order_relaxed);
    
    // Clean up context
    if (g_state.ctx) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    if (g_state.model) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    reset_metrics_locked();
}

// Abort callback function for llama context
bool abort_callback_handler(void * data) {
    (void) data;
    return g_state.should_abort.load(std::memory_order_relaxed);
}

std::string stop_reason_to_string(StopReason reason) {
    switch (reason) {
        case StopReason::None: return "none";
        case StopReason::Eos: return "eos";
        case StopReason::StopSequence: return "stop_sequence";
        case StopReason::MaxTokens: return "max_tokens";
        case StopReason::Error: return "error";
    }
    return "unknown";
}

std::string escape_json(const std::string & input) {
    std::ostringstream oss;
    for (char ch : input) {
        switch (ch) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\b': oss << "\\b"; break;
            case '\f': oss << "\\f"; break;
            case '\n': oss << "\\n"; break;
            case '\r': oss << "\\r"; break;
            case '\t': oss << "\\t"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    oss << "\\u"
                        << std::hex << std::uppercase << std::setw(4) << std::setfill('0')
                        << static_cast<int>(static_cast<unsigned char>(ch))
                        << std::dec << std::nouppercase;
                } else {
                    oss << ch;
                }
        }
    }
    return oss.str();
}

std::string build_metrics_json_locked() {
    const EngineMetrics & m = g_state.metrics;
    std::ostringstream oss;
    oss.setf(std::ios::fixed);
    oss.precision(3);
    oss << "{";
    oss << "\"nCtx\":" << g_state.n_ctx << ",";
    oss << "\"nThreads\":" << g_state.n_threads << ",";
    oss << "\"nGpuLayers\":" << g_state.n_gpu_layers << ",";
    oss << "\"useVulkan\":" << (g_state.use_vulkan ? "true" : "false") << ",";
    oss << "\"promptTokens\":" << m.prompt_tokens << ",";
    oss << "\"generationTokens\":" << m.generation_tokens << ",";
    oss << "\"ttfsMs\":" << m.ttfs_ms << ",";
    oss << "\"prefillMs\":" << m.prefill_ms << ",";
    oss << "\"decodeMs\":" << m.decode_ms << ",";
    oss << "\"totalMs\":" << m.total_ms << ",";
    oss << "\"tps\":" << m.tps << ",";
    oss << "\"promptTps\":" << m.prompt_tps << ",";
    oss << "\"contextUsedPct\":" << m.context_used_pct << ",";
    oss << "\"truncated\":" << (m.truncated ? "true" : "false") << ",";
    oss << "\"stopReason\":\"" << stop_reason_to_string(g_state.stop_reason) << "\",";
    oss << "\"stopSequence\":\"" << escape_json(g_state.stop_sequence) << "\"";
    oss << "}";
    return oss.str();
}

std::string jstring_to_utf8(JNIEnv * env, jstring js) {
    if (!js) return std::string();
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : "");
    env->ReleaseStringUTFChars(js, c);
    return s;
}

bool emit_chunk(StreamContext * stream, const std::string & text, bool done) {
    if (!stream || !stream->callback || !stream->on_token) {
        return true;
    }
    if (!done && text.empty()) {
        return true;
    }
    jstring jChunk = stream->env->NewStringUTF(text.c_str());
    if (!jChunk) {
        LOGE("failed to allocate chunk string");
        return false;
    }
    stream->env->CallVoidMethod(stream->callback, stream->on_token, jChunk, done ? JNI_TRUE : JNI_FALSE);
    stream->env->DeleteLocalRef(jChunk);
    if (stream->env->ExceptionCheck()) {
        LOGE("exception thrown from token callback");
        stream->env->ExceptionClear();
        return false;
    }
    return true;
}

struct StreamDoneGuard {
    explicit StreamDoneGuard(StreamContext * ctx) : stream(ctx) {}
    ~StreamDoneGuard() {
        if (stream) {
            emit_chunk(stream, "", true);
        }
    }
    void dismiss() { stream = nullptr; }
    StreamContext * stream;
};

bool prepare_prompt_tokens(const llama_vocab * vocab,
                           const std::string & text,
                           std::vector<llama_token> & out_tokens) {
    out_tokens.resize(text.size() + 8);
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                               out_tokens.data(), static_cast<int>(out_tokens.size()),
                               /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) {
        out_tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           out_tokens.data(), static_cast<int>(out_tokens.size()),
                           true, true);
    }
    if (n < 0) {
        return false;
    }
    out_tokens.resize(static_cast<size_t>(n));
    return true;
}

bool contains_case_insensitive(const std::string & haystack, const std::string & needle) {
    if (needle.empty()) return false;
    auto it = std::search(
        haystack.begin(), haystack.end(),
        needle.begin(), needle.end(),
        [](char ch1, char ch2) {
            return std::tolower(static_cast<unsigned char>(ch1)) ==
                   std::tolower(static_cast<unsigned char>(ch2));
        });
    return it != haystack.end();
}

std::string read_meta_value(const llama_model * model, const char * key) {
    if (!model || !key) return {};
    char buf[2048];
    int32_t written = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    if (written < 0) {
        return {};
    }
    if (written >= static_cast<int32_t>(sizeof(buf))) {
        std::vector<char> big(static_cast<size_t>(written) + 1);
        llama_model_meta_val_str(model, key, big.data(), big.size());
        return std::string(big.data());
    }
    return std::string(buf);
}

bool generate_internal(const GenerationRequest & req,
                       StreamContext * stream,
                       std::string * out_text,
                       GenerationSummary & summary) {
    StreamDoneGuard done_guard(stream);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    summary = GenerationSummary{};
    SummaryCommit commit{g_state, summary};

    if (!g_state.ctx || !g_state.model) {
        summary.reason = StopReason::Error;
        return false;
    }

    ensure_backend_init();
    
    // Reset abort flag for new generation
    g_state.should_abort.store(false, std::memory_order_relaxed);
    
    // Set abort callback for graceful cancellation during generation
    llama_set_abort_callback(g_state.ctx, abort_callback_handler, nullptr);
    
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    llama_set_n_threads(g_state.ctx, g_state.n_threads, g_state.n_threads);

    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
    if (!vocab) {
        LOGE("vocab unavailable");
        summary.reason = StopReason::Error;
        return false;
    }

    std::string full_prompt = req.system_prompt.empty()
            ? req.prompt
            : (req.system_prompt + "\n\n" + req.prompt);

    std::vector<llama_token> prompt_tokens;
    if (!prepare_prompt_tokens(vocab, full_prompt, prompt_tokens)) {
        LOGE("failed to tokenize prompt");
        summary.reason = StopReason::Error;
        return false;
    }

    llama_batch prefill = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));

    const double t_start_ms = llama_time_us() / 1000.0;
    const double t_prefill_start_ms = t_start_ms;
    if (llama_decode(g_state.ctx, prefill) != 0) {
        LOGE("prefill decode failed");
        summary.reason = StopReason::Error;
        return false;
    }
    const double t_prefill_end_ms = llama_time_us() / 1000.0;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    if (!sampler) {
        LOGE("failed to init sampler chain");
        summary.reason = StopReason::Error;
        return false;
    }

    if (req.temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (req.top_k > 0) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(req.top_k));
        }
        if (req.top_p > 0.0f && req.top_p < 1.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(req.top_p, 1));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(req.temperature));
        const uint32_t seed = static_cast<uint32_t>(llama_time_us() & 0xFFFFFFFFULL);
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));
    }

    StopBuffer stop_buffer(req.stops);
    summary.metrics.prompt_tokens = static_cast<int>(prompt_tokens.size());
    summary.metrics.prefill_ms = t_prefill_end_ms - t_prefill_start_ms;
    if (summary.metrics.prefill_ms > 0.0) {
        summary.metrics.prompt_tps = (summary.metrics.prompt_tokens * 1000.0) / summary.metrics.prefill_ms;
    }

    const double t_decode_start_ms = llama_time_us() / 1000.0;

    for (int i = 0; i < req.max_tokens; ++i) {
        // Check abort flag before each token generation
        if (g_state.should_abort.load(std::memory_order_relaxed)) {
            summary.reason = StopReason::Error;
            summary.metrics.truncated = true;
            break;
        }
        
        const llama_token token = llama_sampler_sample(sampler, g_state.ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) {
            summary.reason = StopReason::Eos;
            break;
        }

        char buffer[512];
        const int32_t written = llama_token_to_piece(vocab, token, buffer, static_cast<int32_t>(sizeof(buffer)), 0, false);
        std::string piece;
        if (written > 0) {
            piece.assign(buffer, static_cast<size_t>(written));
        }

        bool hit_stop = false;
        std::string matched_stop;
        std::string emit = stop_buffer.push(piece, hit_stop, matched_stop);
        if (!emit.empty()) {
            if (!emit_chunk(stream, emit, false)) {
                summary.reason = StopReason::Error;
                break;
            }
            if (out_text) {
                out_text->append(emit);
            }
        }

        if (summary.metrics.generation_tokens == 0) {
            summary.metrics.ttfs_ms = llama_time_us() / 1000.0 - t_start_ms;
        }
        summary.metrics.generation_tokens += 1;

        llama_token to_feed = token;
        llama_batch cont = llama_batch_get_one(&to_feed, 1);
        if (llama_decode(g_state.ctx, cont) != 0) {
            LOGE("decode failed during generation");
            summary.reason = StopReason::Error;
            summary.metrics.truncated = true;
            break;
        }

        if (hit_stop) {
            summary.reason = StopReason::StopSequence;
            summary.stop_sequence = matched_stop;
            break;
        }
    }

    llama_sampler_free(sampler);

    if (summary.reason == StopReason::None) {
        summary.reason = summary.metrics.generation_tokens >= req.max_tokens
                ? StopReason::MaxTokens
                : StopReason::None;
    }

    const double t_decode_end_ms = llama_time_us() / 1000.0;
    summary.metrics.decode_ms = t_decode_end_ms - t_decode_start_ms;
    summary.metrics.total_ms = t_decode_end_ms - t_start_ms;
    if (summary.metrics.decode_ms > 0.0 && summary.metrics.generation_tokens > 0) {
        summary.metrics.tps = (summary.metrics.generation_tokens * 1000.0) / summary.metrics.decode_ms;
    }
    if (g_state.n_ctx > 0) {
        const double used = static_cast<double>(summary.metrics.prompt_tokens + summary.metrics.generation_tokens);
        summary.metrics.context_used_pct = (used * 100.0) / static_cast<double>(g_state.n_ctx);
    }
    summary.metrics.truncated = summary.metrics.truncated || (summary.reason == StopReason::MaxTokens);

    std::string tail = stop_buffer.flush();
    if (!tail.empty() && summary.reason != StopReason::Error) {
        if (!emit_chunk(stream, tail, false)) {
            summary.reason = StopReason::Error;
        } else if (out_text) {
            out_text->append(tail);
        }
    }

    summary.success = summary.reason != StopReason::Error;
    if (summary.success && stream) {
        emit_chunk(stream, "", true);
        done_guard.dismiss();
    }
    return summary.success;
}

std::string detect_model_metadata(const char * path) {
    if (!path || !file_exists(path)) {
        return "{}";
    }

    ensure_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    llama_model * mdl = llama_model_load_from_file(path, mparams);
    if (!mdl) {
        LOGE("failed to load model metadata for %s", path);
        return "{}";
    }

    const int32_t n_ctx_train = llama_model_n_ctx_train(mdl);
    const int32_t n_layer = llama_model_n_layer(mdl);
    const int32_t n_embd = llama_model_n_embd(mdl);
    const llama_vocab * vocab = llama_model_get_vocab(mdl);
    const int32_t n_vocab = vocab ? llama_vocab_n_tokens(vocab) : 0;

    std::string arch = read_meta_value(mdl, "general.architecture");
    std::string chat_template = read_meta_value(mdl, "tokenizer.chat_template");
    if (chat_template.empty()) {
        chat_template = read_meta_value(mdl, "llama.chat_template");
    }
    std::string tokenizer_model = read_meta_value(mdl, "tokenizer.ggml.model");
    std::string tags = read_meta_value(mdl, "general.tags");
    std::string capabilities = read_meta_value(mdl, "general.capabilities");
    std::string reasoning_flag = read_meta_value(mdl, "general.capabilities.reasoning");

    bool reasoning = contains_case_insensitive(reasoning_flag, "true") ||
                     contains_case_insensitive(capabilities, "reasoning") ||
                     contains_case_insensitive(tags, "reasoning") ||
                     contains_case_insensitive(chat_template, "<think>") ||
                     contains_case_insensitive(chat_template, "<reasoning>");

    std::ostringstream oss;
    oss << "{" 
        << "\"arch\":\"" << escape_json(arch) << "\"," 
        << "\"nCtxTrain\":" << n_ctx_train << ","
        << "\"nLayer\":" << n_layer << ","
        << "\"nEmbd\":" << n_embd << ","
        << "\"nVocab\":" << n_vocab << ","
        << "\"chatTemplate\":\"" << escape_json(chat_template) << "\"," 
        << "\"tokenizerModel\":\"" << escape_json(tokenizer_model) << "\"," 
        << "\"reasoning\":" << (reasoning ? "true" : "false") << ","
        << "\"tags\":\"" << escape_json(tags) << "\"";
    oss << "}";

    llama_model_free(mdl);
    return oss.str();
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_peerchat_engine_EngineNative_init(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    ensure_backend_init();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peerchat_engine_EngineNative_loadModel(JNIEnv * env, jobject thiz,
                                                jstring jModelPath,
                                                jint nThreads,
                                                jint nCtx,
                                                jint nGpuLayers,
                                                jboolean useVulkan) {
    (void) thiz;
    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!file_exists(path)) {
        LOGE("model path not found: %s", path ? path : "(null)");
        env->ReleaseStringUTFChars(jModelPath, path);
        return JNI_FALSE;
    }

    ensure_backend_init();

    std::lock_guard<std::mutex> lock(g_state.mutex);
    unload_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useVulkan ? nGpuLayers : 0;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (!model) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = std::max(512, nCtx);
    cparams.n_threads = std::max(1, nThreads);
    cparams.n_threads_batch = std::max(1, nThreads);

    // Performance optimizations for Vulkan
    if (useVulkan) {
        // Increase batch size for better GPU utilization
        cparams.n_batch = std::min(2048U, cparams.n_ctx / 4);
        cparams.n_ubatch = std::min(512U, cparams.n_batch / 4);

        // Optimize for GPU memory usage
        cparams.offload_kqv = true;
    } else {
        // CPU optimizations
        cparams.n_batch = std::min(512U, cparams.n_ctx / 8);
        cparams.n_ubatch = std::min(128U, cparams.n_batch / 4);
    }

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create llama context");
        llama_model_free(model);
        return JNI_FALSE;
    }

    g_state.model = model;
    g_state.ctx = ctx;
    g_state.n_ctx = cparams.n_ctx;
    g_state.n_threads = cparams.n_threads;
    g_state.n_gpu_layers = useVulkan ? nGpuLayers : 0;
    g_state.use_vulkan = useVulkan;
    reset_metrics_locked();

    llama_set_n_threads(g_state.ctx, g_state.n_threads, g_state.n_threads);
    LOGI("model loaded n_ctx=%d n_threads=%d gpu_layers=%d", g_state.n_ctx, g_state.n_threads, g_state.n_gpu_layers);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerchat_engine_EngineNative_unload(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    unload_locked();
    LOGI("engine unloaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_peerchat_engine_EngineNative_generate(JNIEnv * env, jobject thiz,
                                               jstring jPrompt,
                                               jstring jSystem,
                                               jstring jTemplate,
                                               jfloat temperature,
                                               jfloat topP,
                                               jint topK,
                                               jint maxTokens,
                                               jobjectArray jStop) {
    (void) thiz;
    (void) jTemplate;

    GenerationRequest req;
    req.prompt = jstring_to_utf8(env, jPrompt);
    req.system_prompt = jstring_to_utf8(env, jSystem);
    req.temperature = temperature;
    req.top_p = topP;
    req.top_k = topK;
    req.max_tokens = std::max(1, maxTokens);

    jsize stop_len = jStop ? env->GetArrayLength(jStop) : 0;
    for (jsize i = 0; i < stop_len; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jStop, i));
        req.stops.push_back(jstring_to_utf8(env, js));
        env->DeleteLocalRef(js);
    }

    std::string output;
    GenerationSummary summary;
    bool ok = generate_internal(req, nullptr, &output, summary);
    if (!ok) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerchat_engine_EngineNative_generateStream(JNIEnv * env, jobject thiz,
                                                     jstring jPrompt,
                                                     jstring jSystem,
                                                     jstring jTemplate,
                                                     jfloat temperature,
                                                     jfloat topP,
                                                     jint topK,
                                                     jint maxTokens,
                                                     jobjectArray jStop,
                                                     jobject jCallback) {
    (void) thiz;
    (void) jTemplate;

    StreamContext stream{};
    if (jCallback) {
        stream.env = env;
        stream.callback = jCallback;
        jclass cbCls = env->GetObjectClass(jCallback);
        stream.on_token = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;Z)V");
        env->DeleteLocalRef(cbCls);
        if (!stream.on_token) {
            LOGE("TokenCallback.onToken not found");
            return;
        }
    }

    GenerationRequest req;
    req.prompt = jstring_to_utf8(env, jPrompt);
    req.system_prompt = jstring_to_utf8(env, jSystem);
    req.temperature = temperature;
    req.top_p = topP;
    req.top_k = topK;
    req.max_tokens = std::max(1, maxTokens);

    jsize stop_len = jStop ? env->GetArrayLength(jStop) : 0;
    for (jsize i = 0; i < stop_len; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jStop, i));
        req.stops.push_back(jstring_to_utf8(env, js));
        env->DeleteLocalRef(js);
    }

    GenerationSummary summary;
    generate_internal(req, jCallback ? &stream : nullptr, nullptr, summary);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_peerchat_engine_EngineNative_embed(JNIEnv * env, jobject thiz, jobjectArray jTexts) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    jclass floatArrayClass = env->FindClass("[F");
    if (!g_state.ctx || !g_state.model || !floatArrayClass) {
        return static_cast<jobjectArray>(env->NewObjectArray(0, floatArrayClass, nullptr));
    }

    ensure_backend_init();

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = g_state.n_ctx;
    cparams.n_threads = g_state.n_threads;
    cparams.embeddings = true;

    llama_context * ectx = llama_init_from_model(g_state.model, cparams);
    if (!ectx) {
        LOGE("failed to create embeddings context");
        return static_cast<jobjectArray>(env->NewObjectArray(0, floatArrayClass, nullptr));
    }

    llama_set_n_threads(ectx, g_state.n_threads, g_state.n_threads);

    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
    jsize count = jTexts ? env->GetArrayLength(jTexts) : 0;

    std::vector<std::vector<float>> embeddings;
    embeddings.reserve(count);

    for (jsize i = 0; i < count; ++i) {
        jstring jt = static_cast<jstring>(env->GetObjectArrayElement(jTexts, i));
        std::string text = jstring_to_utf8(env, jt);
        env->DeleteLocalRef(jt);

        std::vector<llama_token> tokens;
        if (!prepare_prompt_tokens(vocab, text, tokens)) {
            embeddings.emplace_back();
            continue;
        }
        if (tokens.empty()) {
            embeddings.emplace_back();
            continue;
        }

        llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
        if (llama_encode(ectx, batch) != 0) {
            LOGE("llama_encode failed for embeddings");
            embeddings.emplace_back();
            continue;
        }

        const float * emb = llama_get_embeddings(ectx);
        const int dim = llama_model_n_embd(g_state.model);
        std::vector<float> vec;
        if (emb && dim > 0) {
            vec.assign(emb, emb + dim);
        }
        embeddings.push_back(std::move(vec));
    }

    jobjectArray outer = env->NewObjectArray(count, floatArrayClass, nullptr);
    for (jsize i = 0; i < count; ++i) {
        const auto & vec = embeddings[i];
        jfloatArray inner = env->NewFloatArray(static_cast<jsize>(vec.size()));
        if (!vec.empty()) {
            env->SetFloatArrayRegion(inner, 0, static_cast<jsize>(vec.size()), vec.data());
        }
        env->SetObjectArrayElement(outer, i, inner);
        env->DeleteLocalRef(inner);
    }

    // Clean up embedding context
    llama_free(ectx);
    return outer;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_peerchat_engine_EngineNative_countTokens(JNIEnv * env, jobject thiz, jstring jText) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.model) {
        return 0;
    }
    const char * text = env->GetStringUTFChars(jText, nullptr);
    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens;
    bool ok = prepare_prompt_tokens(vocab, text, tokens);
    env->ReleaseStringUTFChars(jText, text);
    return ok ? static_cast<jint>(tokens.size()) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_peerchat_engine_EngineNative_metrics(JNIEnv * env, jobject thiz) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    std::string json = build_metrics_json_locked();
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_peerchat_engine_EngineNative_detectModel(JNIEnv * env, jobject thiz, jstring jModelPath) {
    (void) thiz;
    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string json = detect_model_metadata(path);
    env->ReleaseStringUTFChars(jModelPath, path);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_peerchat_engine_EngineNative_stateCapture(JNIEnv * env, jobject thiz) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return env->NewByteArray(0);
    }
    const size_t size = llama_state_get_size(g_state.ctx);
    if (size == 0) {
        return env->NewByteArray(0);
    }
    std::vector<uint8_t> buffer(size);
    const size_t written = llama_state_get_data(g_state.ctx, buffer.data(), buffer.size());
    if (written == 0) {
        return env->NewByteArray(0);
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(written));
    if (!result) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written),
            reinterpret_cast<const jbyte *>(buffer.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peerchat_engine_EngineNative_stateRestore(JNIEnv * env, jobject thiz, jbyteArray jState) {
    (void) thiz;
    if (!jState) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return JNI_FALSE;
    }
    const jsize len = env->GetArrayLength(jState);
    if (len <= 0) {
        return JNI_FALSE;
    }
    std::vector<uint8_t> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(jState, 0, len, reinterpret_cast<jbyte *>(buffer.data()));
    llama_memory_clear(llama_get_memory(g_state.ctx), false);
    const size_t read = llama_state_set_data(g_state.ctx, buffer.data(), buffer.size());
    const bool ok = read > 0;
    if (ok) {
        reset_metrics_locked();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerchat_engine_EngineNative_stateClear(JNIEnv * env, jobject thiz, jboolean clearData) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return;
    }
    llama_memory_clear(llama_get_memory(g_state.ctx), clearData == JNI_TRUE);
    reset_metrics_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerchat_engine_EngineNative_abort(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    // Thread-safe abort flag set
    g_state.should_abort.store(true, std::memory_order_release);
    LOGI("abort requested");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_peerchat_engine_EngineNative_stateSize(JNIEnv * env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return 0;
    }
    const size_t size = llama_state_get_size(g_state.ctx);
    if (size == 0) {
        return 0;
    }
    return static_cast<jint>(size > 0x7fffffffULL ? 0x7fffffff : size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_peerchat_engine_EngineNative_stateCaptureInto(JNIEnv * env, jobject thiz, jobject jBuffer) {
    (void) thiz;
    if (!jBuffer) {
        return 0;
    }
    void * addr = env->GetDirectBufferAddress(jBuffer);
    const jlong capacity = env->GetDirectBufferCapacity(jBuffer);
    if (!addr || capacity <= 0) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return 0;
    }
    const size_t total = llama_state_get_size(g_state.ctx);
    if (total == 0) {
        return 0;
    }
    const size_t to_write = static_cast<size_t>(capacity < static_cast<jlong>(total) ? capacity : static_cast<jlong>(total));
    const size_t written = llama_state_get_data(g_state.ctx, static_cast<uint8_t*>(addr), to_write);
    return static_cast<jint>(written > 0x7fffffffULL ? 0x7fffffff : written);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peerchat_engine_EngineNative_stateRestoreFrom(JNIEnv * env, jobject thiz, jobject jBuffer, jint length) {
    (void) thiz;
    if (!jBuffer || length <= 0) {
        return JNI_FALSE;
    }
    void * addr = env->GetDirectBufferAddress(jBuffer);
    if (!addr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.ctx) {
        return JNI_FALSE;
    }
    llama_memory_clear(llama_get_memory(g_state.ctx), false);
    const size_t read = llama_state_set_data(g_state.ctx, static_cast<const uint8_t*>(addr), static_cast<size_t>(length));
    const bool ok = read > 0;
    if (ok) {
        reset_metrics_locked();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
