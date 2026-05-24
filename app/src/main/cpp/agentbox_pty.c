#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void throw_io_exception(JNIEnv *env, const char *prefix) {
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s", prefix, strerror(errno));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

static char **to_c_string_array(JNIEnv *env, jobjectArray array, int *out_count) {
    if (array == NULL) {
        *out_count = 0;
        return NULL;
    }
    jsize count = (*env)->GetArrayLength(env, array);
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) {
        throw_io_exception(env, "calloc failed");
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (item == NULL) continue;
        const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (utf != NULL) {
            result[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, item, utf);
        }
        (*env)->DeleteLocalRef(env, item);
    }
    result[count] = NULL;
    *out_count = count;
    return result;
}

static void free_c_string_array(char **array) {
    if (array == NULL) return;
    for (int i = 0; array[i] != NULL; i++) free(array[i]);
    free(array);
}

JNIEXPORT jintArray JNICALL
Java_com_shaun_agentbox_sandbox_NativePty_createSubprocess(
        JNIEnv *env,
        jclass clazz,
        jstring cmd,
        jobjectArray args,
        jobjectArray env_vars,
        jstring cwd) {
    (void) clazz;

    const char *cmd_utf = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (cmd_utf == NULL) return NULL;

    int argc = 0;
    int envc = 0;
    char **argv = to_c_string_array(env, args, &argc);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
        return NULL;
    }
    char **envp = to_c_string_array(env, env_vars, &envc);
    if ((*env)->ExceptionCheck(env)) {
        free_c_string_array(argv);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
        return NULL;
    }

    const char *cwd_utf = NULL;
    if (cwd != NULL) cwd_utf = (*env)->GetStringUTFChars(env, cwd, NULL);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, NULL);
    if (pid < 0) {
        throw_io_exception(env, "forkpty failed");
        free_c_string_array(argv);
        free_c_string_array(envp);
        if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
        return NULL;
    }

    if (pid == 0) {
        if (cwd_utf != NULL) chdir(cwd_utf);
        execve(cmd_utf, argv, envp);
        _exit(127);
    }

    int read_fd = dup(master_fd);
    int write_fd = dup(master_fd);
    int saved_errno = errno;
    close(master_fd);
    if (read_fd < 0 || write_fd < 0) {
        errno = saved_errno;
        if (read_fd >= 0) close(read_fd);
        if (write_fd >= 0) close(write_fd);
        kill(pid, SIGKILL);
        throw_io_exception(env, "dup pty fd failed");
        free_c_string_array(argv);
        free_c_string_array(envp);
        if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
        return NULL;
    }

    jint values[3];
    values[0] = (jint) pid;
    values[1] = (jint) read_fd;
    values[2] = (jint) write_fd;
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 3, values);

    free_c_string_array(argv);
    free_c_string_array(envp);
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shaun_agentbox_sandbox_NativePty_setWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols, jint width, jint height) {
    (void) env; (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) width;
    ws.ws_ypixel = (unsigned short) height;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_shaun_agentbox_sandbox_NativePty_killProcess(JNIEnv *env, jclass clazz, jint pid, jint signal_num) {
    (void) env; (void) clazz;
    if (pid > 0) kill((pid_t) pid, signal_num);
}

JNIEXPORT jboolean JNICALL
Java_com_shaun_agentbox_sandbox_NativePty_isProcessAlive(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    if (pid <= 0) return JNI_FALSE;
    int status = 0;
    pid_t waited = waitpid((pid_t) pid, &status, WNOHANG);
    if (waited == (pid_t) pid) return JNI_FALSE;
    if (waited == 0) return JNI_TRUE;
    if (kill((pid_t) pid, 0) == 0) return JNI_TRUE;
    return errno == EPERM ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shaun_agentbox_sandbox_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    if (pid <= 0) return -1;
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    return status;
}
