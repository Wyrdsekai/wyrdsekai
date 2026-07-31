/**
 * JNI bridge between Android Kotlin and llama.cpp.
 *
 * Maps LlamaCppJni.kt external functions to llama.cpp C API.
 * Compiled via NDK CMake (see CMakeLists.txt).
 *
 * Build:
 *   cd clients/daemon
 *   mkdir -p build && cd build
 *   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 *         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
 *         ../llama-jni
 *   make -j$(nproc)
 *   cp libllama-jni.so ../src/main/jniLibs/arm64-v8a/
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#define TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Model state (one model at a time on phone)
struct ModelState {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int context_size = 2048;
};

static ModelState g_state;

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_loadModel(
    JNIEnv* env, jobject /* this */,
    jstring path, jobject params
) {
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model: %s", model_path);

    // Extract params from Kotlin data class
    jclass paramsClass = env->GetObjectClass(params);
    jint ctx_size = env->GetIntField(params,
        env->GetFieldID(paramsClass, "contextSize", "I"));
    jint threads = env->GetIntField(params,
        env->GetFieldID(paramsClass, "threads", "I"));
    jint gpu_layers = env->GetIntField(params,
        env->GetFieldID(paramsClass, "gpuLayers", "I"));
    jboolean flash_attn = env->GetBooleanField(params,
        env->GetFieldID(paramsClass, "flashAttention", "Z"));

    // Initialize llama backend
    llama_backend_init();

    // Load model
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpu_layers;

    auto* model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(path, model_path);

    if (!model) {
        LOGE("Failed to load model");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
            "Failed to load GGUF model");
        return 0;
    }

    // Create context
    auto cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.flash_attn = flash_attn;

    auto* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        LOGE("Failed to create context");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
            "Failed to create llama context");
        return 0;
    }

    g_state.model = model;
    g_state.ctx = ctx;
    g_state.context_size = ctx_size;

    LOGI("Model loaded: ctx=%d, threads=%d, gpu_layers=%d", ctx_size, threads, gpu_layers);
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_unloadModel(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    if (g_state.ctx) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    if (g_state.model) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    llama_backend_free();
    LOGI("Model unloaded");
}

JNIEXPORT jstring JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_complete(
    JNIEnv* env, jobject /* this */,
    jlong handle, jstring prompt, jint maxTokens,
    jfloat temperature, jfloat topP, jobjectArray stopTokens
) {
    if (!g_state.ctx || !g_state.model) {
        return env->NewStringUTF("[Error: model not loaded]");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);

    // Tokenize prompt
    const auto* vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens(g_state.context_size);
    int n_tokens = llama_tokenize(vocab, prompt_str, -1,
        tokens.data(), tokens.size(), true, true);
    tokens.resize(n_tokens);

    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (n_tokens < 0) {
        return env->NewStringUTF("[Error: tokenization failed]");
    }

    // Decode prompt
    llama_kv_cache_clear(g_state.ctx);
    auto batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_state.ctx, batch) != 0) {
        return env->NewStringUTF("[Error: decode failed]");
    }

    // Sample tokens
    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    std::string result;
    char token_buf[256];

    for (int i = 0; i < maxTokens; i++) {
        auto token = llama_sampler_sample(sampler, g_state.ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) break;

        int n = llama_token_to_piece(vocab, token, token_buf, sizeof(token_buf), 0, true);
        if (n > 0) {
            result.append(token_buf, n);
        }

        // Prepare next batch
        auto next_batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_state.ctx, next_batch) != 0) break;
    }

    llama_sampler_free(sampler);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_healthCheck(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    return g_state.model != nullptr && g_state.ctx != nullptr;
}

JNIEXPORT jint JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_contextSize(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    return g_state.context_size;
}

JNIEXPORT jobject JNICALL
Java_org_wyrdsekai_daemon_LlamaCppJni_modelInfo(
    JNIEnv* env, jobject /* this */, jlong handle
) {
    jclass cls = env->FindClass("org/wyrdsekai/daemon/LlamaCppJni$ModelInfoJni");
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(JLjava/lang/String;II)V");

    jlong param_count = 0;
    jstring quant_type = env->NewStringUTF("unknown");
    jint ctx_len = g_state.context_size;
    jint vocab_size = 0;

    if (g_state.model) {
        // Note: llama.cpp API for model metadata varies by version
        // These are approximate
        ctx_len = g_state.context_size;
    }

    return env->NewObject(cls, ctor, param_count, quant_type, ctx_len, vocab_size);
}

} // extern "C"
