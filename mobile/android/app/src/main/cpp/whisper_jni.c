#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "noteit_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct cancellation_state {
    JavaVM *vm;
    jobject callback;
    jmethodID method;
};

JNIEXPORT jboolean JNICALL
Java_ai_voiceflow_voiceflow_1mobile_WhisperNative_vulkanCompiled(JNIEnv *env, jobject thiz) {
#if NOTEIT_WHISPER_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static bool cancelled(void *opaque) {
    struct cancellation_state *state = opaque;
    JNIEnv *env = NULL;
    bool attached = false;
    if ((*state->vm)->GetEnv(state->vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*state->vm)->AttachCurrentThread(state->vm, &env, NULL) != JNI_OK) return true;
        attached = true;
    }
    bool stop = (*env)->CallBooleanMethod(env, state->callback, state->method);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        stop = true;
    }
    if (attached) (*state->vm)->DetachCurrentThread(state->vm);
    return stop;
}

static bool append_text(char **buf, size_t *cap, size_t *len, const char *text) {
    size_t size = strlen(text);
    if (*len + size + 1 > *cap) {
        size_t new_cap = (*len + size + 1) * 2;
        char *next = realloc(*buf, new_cap);
        if (next == NULL) return false;
        *buf = next;
        *cap = new_cap;
    }
    memcpy(*buf + *len, text, size);
    *len += size;
    (*buf)[*len] = '\0';
    return true;
}

// JNI glue for ai.voiceflow.voiceflow_mobile.WhisperNative.
// (Package underscore "voiceflow_mobile" is mangled to "voiceflow_1mobile".)

JNIEXPORT jlong JNICALL
Java_ai_voiceflow_voiceflow_1mobile_WhisperNative_initContext(
        JNIEnv *env, jobject thiz, jstring model_path, jboolean use_gpu) {
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) LOGE("whisper_init_from_file failed");
    return (jlong)(intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_ai_voiceflow_voiceflow_1mobile_WhisperNative_freeContext(
        JNIEnv *env, jobject thiz, jlong ptr) {
    if (ptr != 0) whisper_free((struct whisper_context *)(intptr_t) ptr);
}

JNIEXPORT jstring JNICALL
Java_ai_voiceflow_voiceflow_1mobile_WhisperNative_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong ptr, jint threads,
        jfloatArray audio, jstring lang, jstring prompt,
        jboolean scaled_context, jint drop_leading_ms, jobject cancellation, jint beam_size) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t) ptr;
    if (ctx == NULL) return NULL;
    if (beam_size < 1 || beam_size > 5) {
        LOGE("Invalid beam size: expected 1 through 5");
        return NULL;
    }

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (samples == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, audio);

    struct whisper_full_params params = whisper_full_default_params(
        beam_size == 1 ? WHISPER_SAMPLING_GREEDY : WHISPER_SAMPLING_BEAM_SEARCH);
    if (beam_size > 1) params.beam_search.beam_size = beam_size;
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;
    params.n_threads        = threads > 0 ? threads : 4;
    // Default upstream language is English. Null / "auto" MUST explicitly
    // request detection (detect_language=true would only detect and return).
    params.language         = "auto";
    params.token_timestamps = drop_leading_ms > 0;

    struct cancellation_state cancel_state = {0};
    (*env)->GetJavaVM(env, &cancel_state.vm);
    cancel_state.callback = (*env)->NewGlobalRef(env, cancellation);
    jclass cancellation_class = (*env)->GetObjectClass(env, cancellation);
    cancel_state.method = (*env)->GetMethodID(env, cancellation_class, "isCancelled", "()Z");
    (*env)->DeleteLocalRef(env, cancellation_class);
    if (cancel_state.callback == NULL || cancel_state.method == NULL) {
        if (cancel_state.callback) (*env)->DeleteGlobalRef(env, cancel_state.callback);
        (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
        return NULL;
    }
    params.abort_callback = cancelled;
    params.abort_callback_user_data = &cancel_state;

    // Scale the encoder context to the actual audio length instead of the full
    // 30 s window (1500 units = 30 s → 50 units/s). A 4 s sentence then costs
    // ~1/6 of the encoder work. Padded by 32 units so the tail isn't clipped.
    if (scaled_context) {
        int ctx = (int)((double) n / WHISPER_SAMPLE_RATE * 50.0) + 32;
        if (ctx < 192)  ctx = 192;
        if (ctx > 1500) ctx = 1500;
        params.audio_ctx = ctx;
    }

    // No temperature-fallback re-decodes: a low-confidence utterance would
    // otherwise be decoded up to 5x over. Latency matters more on-device.
    params.temperature_inc = 0.0f;

    const char *lang_c = NULL;
    if (lang != NULL) {
        lang_c = (*env)->GetStringUTFChars(env, lang, NULL);
        if (lang_c != NULL && strlen(lang_c) > 0 && strcmp(lang_c, "auto") != 0)
            params.language = lang_c;
    }
    const char *prompt_c = NULL;
    if (prompt != NULL) {
        prompt_c = (*env)->GetStringUTFChars(env, prompt, NULL);
        if (prompt_c != NULL && strlen(prompt_c) > 0)
            params.initial_prompt = prompt_c;
    }

    jstring result = NULL;
    if (!cancelled(&cancel_state) && whisper_full(ctx, params, samples, n) == 0 && !cancelled(&cancel_state)) {
        int segs = whisper_full_n_segments(ctx);
        size_t cap = 4096, len = 0;
        char *buf = (char *) malloc(cap);
        if (buf != NULL) {
            buf[0] = '\0';
            bool complete = true;
            for (int i = 0; i < segs && complete; i++) {
                if (drop_leading_ms <= 0) {
                    complete = append_text(&buf, &cap, &len, whisper_full_get_segment_text(ctx, i));
                } else {
                    // Reconcile only audio intentionally overlapped at a forced
                    // boundary. Never remove repetitions by matching text.
                    int count = whisper_full_n_tokens(ctx, i);
                    for (int j = 0; j < count && complete; j++) {
                        whisper_token_data token = whisper_full_get_token_data(ctx, i, j);
                        if (token.id >= whisper_token_eot(ctx)) continue;
                        if (token.t0 >= 0 && token.t1 >= 0 &&
                            (token.t0 + token.t1) * 5 < drop_leading_ms) continue;
                        complete = append_text(&buf, &cap, &len, whisper_full_get_token_text(ctx, i, j));
                    }
                }
            }
            if (complete) result = (*env)->NewStringUTF(env, buf);
            free(buf);
        }
    } else {
        LOGE("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang_c != NULL)   (*env)->ReleaseStringUTFChars(env, lang, lang_c);
    if (prompt_c != NULL) (*env)->ReleaseStringUTFChars(env, prompt, prompt_c);
    (*env)->DeleteGlobalRef(env, cancel_state.callback);
    return result;
}
