/**
 * JNI bridge between KMP Android and llama.cpp.
 *
 * Maps LlamaCppBridge.kt external functions to llama.cpp C API.
 * Library name: libwyrd-llama.so (distinct from daemon's libllama-jni.so).
 *
 * Build (NDK CMake):
 *   cd clients/kmp
 *   ./gradlew :shared:assembleDebug   (CMake runs automatically via externalNativeBuild)
 *
 * Or manual:
 *   mkdir -p build-ndk && cd build-ndk
 *   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
 *         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
 *         ../shared/src/androidMain/jni
 *   make -j$(nproc)
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#define TAG "wyrd-llama"
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
Java_org_wyrdsekai_app_inference_LlamaCppBridge_loadModel(
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
    cparams.flash_attn_type = flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;

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
Java_org_wyrdsekai_app_inference_LlamaCppBridge_unloadModel(
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
Java_org_wyrdsekai_app_inference_LlamaCppBridge_complete(
    JNIEnv* env, jobject /* this */,
    jlong handle, jstring prompt, jint maxTokens,
    jfloat temperature, jfloat topP, jobjectArray stopTokens
) {
    if (!g_state.ctx || !g_state.model) {
        return env->NewStringUTF("[Error: model not loaded]");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    int prompt_len = (int)strlen(prompt_str);
    LOGI("Tokenizing prompt: %d chars", prompt_len);

    // Tokenize prompt
    const auto* vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens(g_state.context_size);
    int n_tokens = llama_tokenize(vocab, prompt_str, prompt_len,
        tokens.data(), tokens.size(), true, true);

    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (n_tokens < 0) {
        LOGE("Tokenization failed: need %d tokens, have %d", -n_tokens, g_state.context_size);
        return env->NewStringUTF("[Error: tokenization failed]");
    }
    tokens.resize(n_tokens);
    LOGI("Tokenized: %d tokens", n_tokens);

    // Decode prompt
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
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

JNIEXPORT jstring JNICALL
Java_org_wyrdsekai_app_inference_LlamaCppBridge_completeWithGrammar(
    JNIEnv* env, jobject /* this */,
    jlong handle, jstring prompt, jint maxTokens,
    jfloat temperature, jfloat topP, jobjectArray stopTokens,
    jstring grammar
) {
    if (!g_state.ctx || !g_state.model) {
        return env->NewStringUTF("[Error: model not loaded]");
    }

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const char* grammar_str = env->GetStringUTFChars(grammar, nullptr);
    int prompt_len = (int)strlen(prompt_str);
    LOGI("Grammar completion: %d chars prompt, grammar=%d chars", prompt_len, (int)strlen(grammar_str));

    // Tokenize prompt
    const auto* vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens(g_state.context_size);
    int n_tokens = llama_tokenize(vocab, prompt_str, prompt_len,
        tokens.data(), tokens.size(), true, true);

    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (n_tokens < 0) {
        env->ReleaseStringUTFChars(grammar, grammar_str);
        LOGE("Tokenization failed: need %d tokens", -n_tokens);
        return env->NewStringUTF("[Error: tokenization failed]");
    }
    tokens.resize(n_tokens);
    LOGI("Tokenized: %d tokens (grammar-constrained)", n_tokens);

    // Decode prompt
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    auto batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_state.ctx, batch) != 0) {
        env->ReleaseStringUTFChars(grammar, grammar_str);
        return env->NewStringUTF("[Error: decode failed]");
    }

    // Sampler with grammar constraint
    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_grammar(vocab, grammar_str, "root"));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    env->ReleaseStringUTFChars(grammar, grammar_str);

    std::string result;
    char token_buf[256];

    for (int i = 0; i < maxTokens; i++) {
        auto token = llama_sampler_sample(sampler, g_state.ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) break;

        int n = llama_token_to_piece(vocab, token, token_buf, sizeof(token_buf), 0, true);
        if (n > 0) {
            result.append(token_buf, n);
        }

        auto next_batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_state.ctx, next_batch) != 0) break;
    }

    llama_sampler_free(sampler);

    LOGI("Grammar completion result: %d chars", (int)result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_org_wyrdsekai_app_inference_LlamaCppBridge_healthCheck(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    return g_state.model != nullptr && g_state.ctx != nullptr;
}

JNIEXPORT jint JNICALL
Java_org_wyrdsekai_app_inference_LlamaCppBridge_contextSize(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    return g_state.context_size;
}

} // extern "C"
