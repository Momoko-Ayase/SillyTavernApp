#include <jni.h>
#include <string>
#include <cstdlib>
#include "node.h"
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

static int pipe_stdout[2];
static int pipe_stderr[2];
static pthread_t thread_stdout;
static pthread_t thread_stderr;
static const char *ADBTAG = "SILLYTAVERN-NODE";

static void *thread_stderr_func(void *) {
    ssize_t n; char buf[2048];
    while ((n = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return 0;
}

static void *thread_stdout_func(void *) {
    ssize_t n; char buf[2048];
    while ((n = read(pipe_stdout[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return 0;
}

static int start_redirecting_stdout_stderr() {
    setvbuf(stdout, 0, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);
    setvbuf(stderr, 0, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);
    // pthread_create returns 0 on success or a positive errno on failure (never -1).
    if (pthread_create(&thread_stdout, 0, thread_stdout_func, 0) != 0) return -1;
    pthread_detach(thread_stdout);
    if (pthread_create(&thread_stderr, 0, thread_stderr_func, 0) != 0) return -1;
    pthread_detach(thread_stderr);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_moe_momokko_sillytavernapp_NodeBridge_setEnv(
        JNIEnv *env, jobject, jstring key, jstring value) {
    const char *k = env->GetStringUTFChars(key, 0);
    const char *v = env->GetStringUTFChars(value, 0);
    setenv(k, v, 1);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT jint JNICALL
Java_moe_momokko_sillytavernapp_NodeBridge_chDir(
        JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, 0);
    int r = chdir(p);
    env->ReleaseStringUTFChars(path, p);
    return (jint) r;
}

extern "C" JNIEXPORT jint JNICALL
Java_moe_momokko_sillytavernapp_NodeBridge_startNodeWithArguments(
        JNIEnv *env, jobject, jobjectArray arguments) {
    jsize argc = env->GetArrayLength(arguments);

    int buffer_size = 0;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, 0);
        buffer_size += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }

    char *args_buffer = (char *) calloc(buffer_size, sizeof(char));
    char **argv = (char **) calloc(argc, sizeof(char *));
    char *pos = args_buffer;

    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *c = env->GetStringUTFChars(s, 0);
        strcpy(pos, c);
        argv[i] = pos;
        pos += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }

    if (start_redirecting_stdout_stderr() == -1) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "stdout/stderr redirect failed");
    }

    int result = node::Start(argc, argv);
    free(args_buffer);
    free(argv);
    return (jint) result;
}
