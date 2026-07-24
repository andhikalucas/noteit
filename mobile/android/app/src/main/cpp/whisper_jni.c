#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "noteit_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JNI glue for ai.voiceflow.voiceflow_mobile.WhisperNative.
// (Package underscore "voiceflow_mobile" is mangled to "voiceflow_1mobile".)

JNIEXPORT jlong JNICALL
Java_ai_voiceflow_voiceflow_1mobile_WhisperNative_initContext(
        JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
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
        jfloatArray audio, jstring lang, jstring prompt) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t) ptr;
    if (ctx == NULL) return NULL;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    jsize n = (*env)->GetArrayLength(env, audio);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;
    params.n_threads        = threads > 0 ? threads : 4;

    // Scale the encoder context to the actual audio length instead of the full
    // 30 s window (1500 units = 30 s → 50 units/s). A 4 s sentence then costs
    // ~1/6 of the encoder work. Padded by 32 units so the tail isn't clipped.
    {
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
            params.language = lang_c;   // NULL => whisper auto-detects
    }
    const char *prompt_c = NULL;
    if (prompt != NULL) {
        prompt_c = (*env)->GetStringUTFChars(env, prompt, NULL);
        if (prompt_c != NULL && strlen(prompt_c) > 0)
            params.initial_prompt = prompt_c;
    }

    jstring result = NULL;
    if (whisper_full(ctx, params, samples, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        size_t cap = 4096, len = 0;
        char *buf = (char *) malloc(cap);
        if (buf != NULL) {
            buf[0] = '\0';
            for (int i = 0; i < segs; i++) {
                const char *t = whisper_full_get_segment_text(ctx, i);
                size_t tl = strlen(t);
                if (len + tl + 1 > cap) {
                    cap = (len + tl + 1) * 2;
                    char *nb = (char *) realloc(buf, cap);
                    if (nb == NULL) break;
                    buf = nb;
                }
                memcpy(buf + len, t, tl);
                len += tl;
                buf[len] = '\0';
            }
            result = (*env)->NewStringUTF(env, buf);
            free(buf);
        }
    } else {
        LOGE("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang_c != NULL)   (*env)->ReleaseStringUTFChars(env, lang, lang_c);
    if (prompt_c != NULL) (*env)->ReleaseStringUTFChars(env, prompt, prompt_c);
    return result;
}
