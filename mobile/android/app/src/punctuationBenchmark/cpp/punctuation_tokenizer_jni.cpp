#include <jni.h>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
constexpr size_t kMaxModelBytes = 8 * 1024 * 1024;
constexpr size_t kMaxInputBytes = 256000;
constexpr size_t kMaxPacketBytes = 8 * 1024 * 1024;
using Processor = sentencepiece::SentencePieceProcessor;

void fail(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass(type);
    if (error) { env->ThrowNew(error, message); env->DeleteLocalRef(error); }
}

std::string bytes(JNIEnv *env, jbyteArray input, size_t limit) {
    if (!input) throw std::invalid_argument("Missing tokenizer input");
    const auto size = env->GetArrayLength(input);
    if (size < 0 || static_cast<size_t>(size) > limit) throw std::invalid_argument("Tokenizer input exceeds limit");
    std::string result(static_cast<size_t>(size), '\0');
    if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read tokenizer bytes");
    return result;
}

Processor *processor(jlong handle) {
    if (!handle) throw std::invalid_argument("Tokenizer is closed");
    return reinterpret_cast<Processor *>(static_cast<intptr_t>(handle));
}

void number(std::vector<jbyte> &output, int32_t value) {
    if (output.size() > kMaxPacketBytes - 4) throw std::runtime_error("Tokenizer packet exceeds limit");
    const auto bits = static_cast<uint32_t>(value);
    for (int shift = 0; shift < 32; shift += 8) output.push_back(static_cast<jbyte>((bits >> shift) & 255));
}

void string(std::vector<jbyte> &output, const std::string &value) {
    if (value.size() > kMaxPacketBytes) throw std::runtime_error("Tokenizer string exceeds limit");
    number(output, static_cast<int32_t>(value.size()));
    if (value.size() > kMaxPacketBytes - output.size()) throw std::runtime_error("Tokenizer packet exceeds limit");
    output.insert(output.end(), value.begin(), value.end());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ai_voiceflow_voiceflow_1mobile_PunctuationTokenizer_nativeLoad(JNIEnv *env, jobject, jbyteArray model) {
    try {
        const auto serialized = bytes(env, model, kMaxModelBytes);
        auto value = std::make_unique<Processor>();
        if (!value->LoadFromSerializedProto(serialized).ok()) throw std::runtime_error("SentencePiece model load failed");
        return static_cast<jlong>(reinterpret_cast<intptr_t>(value.release()));
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "Could not allocate tokenizer model");
    } catch (const std::exception &error) {
        fail(env, "java/lang/IllegalArgumentException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_ai_voiceflow_voiceflow_1mobile_PunctuationTokenizer_nativeSpecialIds(JNIEnv *env, jobject, jlong handle) {
    try {
        const auto *value = processor(handle);
        const jint ids[] = {value->unk_id(), value->bos_id(), value->eos_id(), value->pad_id(), value->GetPieceSize()};
        jintArray result = env->NewIntArray(5);
        if (result) env->SetIntArrayRegion(result, 0, 5, ids);
        return result;
    } catch (const std::exception &error) {
        fail(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_voiceflow_voiceflow_1mobile_PunctuationTokenizer_nativeEncode(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    try {
        const auto *value = processor(handle);
        const auto source = bytes(env, input, kMaxInputBytes);
        sentencepiece::ImmutableSentencePieceText encoded;
        // The convenience EncodeAsImmutableProto ignores errors in C++. Use the
        // checked API. Spans remain UTF-8 bytes; Kotlin maps them to UTF-16.
        if (!value->Encode(source, encoded.mutable_proto()).ok()) throw std::runtime_error("SentencePiece encode failed");
        if (encoded.pieces_size() > 128000) throw std::runtime_error("Tokenizer piece count exceeds limit");
        std::vector<jbyte> packet;
        number(packet, 1); // Little-endian protocol version.
        number(packet, value->unk_id()); number(packet, value->bos_id());
        number(packet, value->eos_id()); number(packet, value->pad_id());
        number(packet, value->GetPieceSize()); number(packet, static_cast<int32_t>(encoded.pieces_size()));
        for (size_t i = 0; i < encoded.pieces_size(); ++i) {
            const auto token = encoded.pieces(static_cast<int>(i));
            if (token.begin() > token.end() || token.end() > source.size()) throw std::runtime_error("Invalid tokenizer byte span");
            number(packet, static_cast<int32_t>(token.id()));
            number(packet, static_cast<int32_t>(token.begin())); number(packet, static_cast<int32_t>(token.end()));
            string(packet, token.piece()); string(packet, token.surface());
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(packet.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(packet.size()), packet.data());
        return result;
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "Could not allocate tokenizer output");
    } catch (const std::exception &error) {
        fail(env, "java/lang/IllegalArgumentException", error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_voiceflow_voiceflow_1mobile_PunctuationTokenizer_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Processor *>(static_cast<intptr_t>(handle));
}
