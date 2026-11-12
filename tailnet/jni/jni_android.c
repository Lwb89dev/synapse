// JNI bridge: app.synapse.remote.Tailnet.native* -> the Go exports of this library.
#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

static jstring wrap(JNIEnv *env, char *s) {
    jstring r = (*env)->NewStringUTF(env, s ? s : "");
    free(s);
    return r;
}

JNIEXPORT jstring JNICALL Java_app_synapse_remote_Tailnet_nativeStart(
        JNIEnv *env, jclass cls, jstring dir, jstring host, jstring key, jstring url, jstring secret) {
    const char *d = (*env)->GetStringUTFChars(env, dir, 0);
    const char *h = (*env)->GetStringUTFChars(env, host, 0);
    const char *k = (*env)->GetStringUTFChars(env, key, 0);
    const char *u = (*env)->GetStringUTFChars(env, url, 0);
    const char *s = (*env)->GetStringUTFChars(env, secret, 0);
    char *r = SynapseStart((char *)d, (char *)h, (char *)k, (char *)u, (char *)s);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    (*env)->ReleaseStringUTFChars(env, host, h);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, url, u);
    (*env)->ReleaseStringUTFChars(env, secret, s);
    return wrap(env, r);
}

JNIEXPORT jstring JNICALL Java_app_synapse_remote_Tailnet_nativeStatus(JNIEnv *env, jclass cls) {
    return wrap(env, SynapseStatus());
}

JNIEXPORT jstring JNICALL Java_app_synapse_remote_Tailnet_nativeForward(JNIEnv *env, jclass cls, jstring target) {
    const char *t = (*env)->GetStringUTFChars(env, target, 0);
    char *r = SynapseForward((char *)t);
    (*env)->ReleaseStringUTFChars(env, target, t);
    return wrap(env, r);
}

JNIEXPORT void JNICALL Java_app_synapse_remote_Tailnet_nativeStop(JNIEnv *env, jclass cls) {
    SynapseStop();
}
