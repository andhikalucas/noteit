#include <jni.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Cancellation {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    Cancellation(JNIEnv *env, jobject object) {
        env->GetJavaVM(&vm);
        callback = env->NewGlobalRef(object);
        jclass type = env->GetObjectClass(object);
        method = env->GetMethodID(type, "isCancelled", "()Z");
        env->DeleteLocalRef(type);
    }
    ~Cancellation() {
        JNIEnv *env = nullptr;
        if (callback && vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
            env->DeleteGlobalRef(callback);
    }
    bool stopped() const {
        if (!callback || !method) return true;
        JNIEnv *env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
            attached = true;
        }
        bool stop = env->CallBooleanMethod(callback, method);
        if (env->ExceptionCheck()) { env->ExceptionClear(); stop = true; }
        if (attached) vm->DetachCurrentThread();
        return stop;
    }
};
bool abort_decode(void *value) { return static_cast<Cancellation *>(value)->stopped(); }
bool continue_load(float, void *value) { return !static_cast<Cancellation *>(value)->stopped(); }
void quiet_log(ggml_log_level, const char *, void *) {}

// JNI's modified UTF-8 is not UTF-8 for supplementary characters. Models and
// JSON use standard UTF-8, so convert through Java's charset codec explicitly.
std::string utf8(JNIEnv *env, jstring value) {
    jclass type = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(type, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, encoding));
    env->DeleteLocalRef(type); env->DeleteLocalRef(encoding);
    if (!bytes) return {};
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes);
    return result;
}
jstring java_string(JNIEnv *env, const std::string &value) {
    auto bytes = env->NewByteArray(value.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    jclass type = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(type, constructor, bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(type); env->DeleteLocalRef(encoding);
    return result;
}

// The model is reused; request contexts and samplers have bounded lifetimes.
struct Runtime { llama_model *model; int threads; };
const char *grammar = R"gbnf(
root ::= "{" ws "\"text\"" ws ":" ws string ws "}"
string ::= "\"" char* "\""
char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
ws ::= [ \t\n\r]*
)gbnf";
}

extern "C" JNIEXPORT jlong JNICALL
Java_ai_voiceflow_voiceflow_1mobile_LocalCleanupNative_load(
    JNIEnv *env, jobject, jstring path, jint threads, jobject callback) {
    try {
        Cancellation cancellation(env, callback);
        if (cancellation.stopped()) return 0;
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
        const char *location = env->GetStringUTFChars(path, nullptr);
        if (!location) return 0;
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.progress_callback = continue_load;
        params.progress_callback_user_data = &cancellation;
        auto *model = llama_model_load_from_file(location, params);
        env->ReleaseStringUTFChars(path, location);
        if (!model) return 0;
        if (cancellation.stopped()) { llama_model_free(model); return 0; }
        return reinterpret_cast<jlong>(new Runtime{model, threads});
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_ai_voiceflow_voiceflow_1mobile_LocalCleanupNative_release(JNIEnv *, jobject, jlong pointer) {
    auto *runtime = reinterpret_cast<Runtime *>(pointer);
    if (runtime) { llama_model_free(runtime->model); delete runtime; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_voiceflow_voiceflow_1mobile_LocalCleanupNative_generate(
    JNIEnv *env, jobject, jlong pointer, jstring prompt, jint maximum_tokens, jobject callback) {
    auto *runtime = reinterpret_cast<Runtime *>(pointer);
    if (!runtime || maximum_tokens < 1 || maximum_tokens > 1024) return nullptr;
    try {
        Cancellation cancellation(env, callback);
        if (cancellation.stopped()) return nullptr;
        std::string text = utf8(env, prompt);
        if (env->ExceptionCheck()) return nullptr;
        if (text.size() > 24000) return nullptr;
        const auto *vocab = llama_model_get_vocab(runtime->model);
        int tokens_needed = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, true, true);
        if (tokens_needed <= 0 || tokens_needed > 3072) return nullptr;
        std::vector<llama_token> tokens(tokens_needed);
        if (llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), true, true) < 0)
            return nullptr;
        auto params = llama_context_default_params();
        params.n_ctx = 4096;
        params.n_batch = 512;
        params.n_ubatch = 128;
        params.n_threads = runtime->threads;
        params.n_threads_batch = runtime->threads;
        params.abort_callback = abort_decode;
        params.abort_callback_data = &cancellation;
        params.no_perf = true;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(runtime->model, params), llama_free);
        if (!context) return nullptr;
        auto sampler_params = llama_sampler_chain_default_params();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(sampler_params), llama_sampler_free);
        auto *constrained = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!constrained || !sampler) { if (constrained) llama_sampler_free(constrained); return nullptr; }
        llama_sampler_chain_add(sampler.get(), constrained);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        for (int offset = 0; offset < tokens_needed; offset += 512) {
            if (cancellation.stopped()) return nullptr;
            int count = std::min(512, tokens_needed - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, count);
            if (llama_decode(context.get(), batch) != 0) return nullptr;
        }
        std::string output;
        for (int generated = 0; generated < maximum_tokens; ++generated) {
            if (cancellation.stopped()) return nullptr;
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) return java_string(env, output);
            char piece[512];
            int count = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (count < 0 || output.size() + count > 16000) return nullptr;
            output.append(piece, count);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context.get(), batch) != 0) return nullptr;
        }
        // Truncation is a failure: never publish an incomplete cleanup result.
        return nullptr;
    } catch (...) { return nullptr; }
}
