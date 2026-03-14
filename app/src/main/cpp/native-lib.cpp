#include <jni.h>
#include <string>
#include <vector>
#include <mutex>

static std::vector<std::string> g_log_buffer;
static std::mutex g_log_mtx;

static std::vector<std::string> g_terminal_buffer;
static std::mutex g_terminal_mtx;

extern "C" JNIEXPORT void JNICALL
Java_com_pi_androidmonitor_NativeBridge_pushLog(JNIEnv* env, jclass clazz, jstring log) {
    const char* nativeLog = env->GetStringUTFChars(log, nullptr);
    std::lock_guard<std::mutex> lock(g_log_mtx);
    g_log_buffer.push_back(nativeLog);
    if (g_log_buffer.size() > 1000) g_log_buffer.erase(g_log_buffer.begin());
    env->ReleaseStringUTFChars(log, nativeLog);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_pi_androidmonitor_NativeBridge_getLogs(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_log_mtx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray res = env->NewObjectArray(g_log_buffer.size(), stringClass, env->NewStringUTF(""));
    for (size_t i = 0; i < g_log_buffer.size(); i++) {
        env->SetObjectArrayElement(res, i, env->NewStringUTF(g_log_buffer[i].c_str()));
    }
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pi_androidmonitor_NativeBridge_pushTerminal(JNIEnv* env, jclass clazz, jstring data) {
    const char* nativeData = env->GetStringUTFChars(data, nullptr);
    std::lock_guard<std::mutex> lock(g_terminal_mtx);
    g_terminal_buffer.push_back(nativeData);
    if (g_terminal_buffer.size() > 1000) g_terminal_buffer.erase(g_terminal_buffer.begin());
    env->ReleaseStringUTFChars(data, nativeData);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_pi_androidmonitor_NativeBridge_getTerminal(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_terminal_mtx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray res = env->NewObjectArray(g_terminal_buffer.size(), stringClass, env->NewStringUTF(""));
    for (size_t i = 0; i < g_terminal_buffer.size(); i++) {
        env->SetObjectArrayElement(res, i, env->NewStringUTF(g_terminal_buffer[i].c_str()));
    }
    return res;
}
