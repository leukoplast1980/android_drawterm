/*
 * JNI glue for com.claude.drawterm.session.Native (methods registered
 * below) and NativeCallbacks (static methods called from drawterm threads).
 * Strings cross as UTF-8 byte arrays: JNI's "modified UTF-8" mangles
 * characters outside the BMP.
 */
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "bridge.h"

#define nelem(a)	(sizeof(a)/sizeof((a)[0]))

static JavaVM *jvm;
static jclass cb;
static jmethodID mclipread, mclipwrite, mnotify, mnumcameras, mtakepicture;
static jmethodID msetcursor, mmouseset, mwantkeyboard, mexited;
static char pkgname[256];

static struct {
	jmethodID *id;
	const char *name, *sig;
} callbacks[] = {
	{ &mclipread, "clipRead", "()[B" },
	{ &mclipwrite, "clipWrite", "([B)V" },
	{ &mnotify, "notify", "([B)V" },
	{ &mnumcameras, "numCameras", "()I" },
	{ &mtakepicture, "takePicture", "(I)[B" },
	{ &msetcursor, "setCursor", "([III)V" },
	{ &mmouseset, "moveCursor", "(II)V" },
	{ &mwantkeyboard, "wantKeyboard", "(Z)V" },
	{ &mexited, "exited", "(I[B)V" },
};

/* drawterm threads are attached once and detached when they exit */
static pthread_key_t envkey;
static pthread_once_t envonce = PTHREAD_ONCE_INIT;

static void
detach(void *v)
{
	(void)v;
	(*jvm)->DetachCurrentThread(jvm);
}

static void
mkenvkey(void)
{
	pthread_key_create(&envkey, detach);
}

/* Attach if needed and open a local reference frame; NULL on failure. */
static JNIEnv*
enter(void)
{
	JNIEnv *env;
	JavaVMAttachArgs args = { JNI_VERSION_1_6, "drawterm", NULL };

	if((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK){
		if((*jvm)->AttachCurrentThread(jvm, &env, &args) != JNI_OK)
			return NULL;
		pthread_once(&envonce, mkenvkey);
		pthread_setspecific(envkey, env);
	}
	if((*env)->PushLocalFrame(env, 16) != 0)
		return NULL;
	return env;
}

static void
leave(JNIEnv *env)
{
	if((*env)->ExceptionCheck(env)){
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	(*env)->PopLocalFrame(env, NULL);
}

static jbyteArray
tobytes(JNIEnv *env, const char *s)
{
	jsize n;
	jbyteArray a;

	n = strlen(s);
	if((a = (*env)->NewByteArray(env, n)) != NULL)
		(*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte*)s);
	return a;
}

/* Malloc'd copy of a byte array with n bytes, NUL-terminated. */
static char*
frombytes(JNIEnv *env, jbyteArray a, jsize *np)
{
	jsize n;
	char *s;

	n = (*env)->GetArrayLength(env, a);
	if((s = malloc(n+1)) == NULL)
		return NULL;
	(*env)->GetByteArrayRegion(env, a, 0, n, (jbyte*)s);
	s[n] = 0;
	if(np != NULL)
		*np = n;
	return s;
}

static void
callstring(jmethodID m, const char *s)
{
	JNIEnv *env;
	jbyteArray a;

	if((env = enter()) == NULL)
		return;
	if((a = tobytes(env, s)) != NULL)
		(*env)->CallStaticVoidMethod(env, cb, m, a);
	leave(env);
}

/* Call a ()[B or (I)[B callback; returns a malloc'd copy, or NULL. */
static char*
callbytes(jmethodID m, int hasarg, int arg, jsize *np)
{
	JNIEnv *env;
	jbyteArray a;
	char *s;

	if((env = enter()) == NULL)
		return NULL;
	s = NULL;
	if(hasarg)
		a = (*env)->CallStaticObjectMethod(env, cb, m, (jint)arg);
	else
		a = (*env)->CallStaticObjectMethod(env, cb, m);
	if(!(*env)->ExceptionCheck(env) && a != NULL)
		s = frombytes(env, a, np);
	leave(env);
	return s;
}

/* --- bridge.h, from drawterm threads --- */

char*
jni_clipread(void)
{
	return callbytes(mclipread, 0, 0, NULL);
}

void
jni_clipwrite(const char *s)
{
	callstring(mclipwrite, s);
}

void
jni_notify(const char *s)
{
	callstring(mnotify, s);
}

int
jni_takepicture(int camera, unsigned char **jpeg)
{
	jsize n;

	*jpeg = (unsigned char*)callbytes(mtakepicture, 1, camera, &n);
	return *jpeg != NULL ? n : -1;
}

int
jni_numcameras(void)
{
	JNIEnv *env;
	int n;

	if((env = enter()) == NULL)
		return 0;
	n = (*env)->CallStaticIntMethod(env, cb, mnumcameras);
	if((*env)->ExceptionCheck(env))
		n = 0;
	leave(env);
	return n;
}

void
jni_setcursor(const unsigned int *argb, int hotx, int hoty)
{
	JNIEnv *env;
	jintArray a;

	if((env = enter()) == NULL)
		return;
	if((a = (*env)->NewIntArray(env, 16*16)) != NULL){
		(*env)->SetIntArrayRegion(env, a, 0, 16*16, (const jint*)argb);
		(*env)->CallStaticVoidMethod(env, cb, msetcursor, a, (jint)hotx, (jint)hoty);
	}
	leave(env);
}

void
jni_mouseset(int x, int y)
{
	JNIEnv *env;

	if((env = enter()) == NULL)
		return;
	(*env)->CallStaticVoidMethod(env, cb, mmouseset, (jint)x, (jint)y);
	leave(env);
}

void
jni_wantkeyboard(int show)
{
	JNIEnv *env;

	if((env = enter()) == NULL)
		return;
	(*env)->CallStaticVoidMethod(env, cb, mwantkeyboard, (jboolean)(show != 0));
	leave(env);
}

void
jni_exited(int code, const char *s)
{
	JNIEnv *env;
	jbyteArray a;

	if((env = enter()) == NULL)
		return;
	if((a = tobytes(env, s)) != NULL)
		(*env)->CallStaticVoidMethod(env, cb, mexited, (jint)code, a);
	leave(env);
}

const char*
jni_packagename(void)
{
	return pkgname;
}

/* --- Native's methods --- */

static jint
nrun(JNIEnv *env, jclass c, jstring pkg, jobjectArray jargs, jbyteArray jpass)
{
	const char *p;
	char **argv, *pass;
	jbyteArray a;
	jsize i, n;

	(void)c;
	if((p = (*env)->GetStringUTFChars(env, pkg, NULL)) != NULL){
		strncpy(pkgname, p, sizeof pkgname - 1);
		(*env)->ReleaseStringUTFChars(env, pkg, p);
	}
	n = (*env)->GetArrayLength(env, jargs);
	if((argv = calloc(n+1, sizeof(char*))) == NULL)
		return -1;
	for(i = 0; i < n; i++){
		a = (*env)->GetObjectArrayElement(env, jargs, i);
		argv[i] = frombytes(env, a, NULL);
		(*env)->DeleteLocalRef(env, a);
		if(argv[i] == NULL)
			return -1;
		if(strcmp(argv[i], "-p") == 0)
			dt_aan = 1;
	}
	if(jpass != NULL && (pass = frombytes(env, jpass, NULL)) != NULL){
		/* cpu.c reads $PASS once, then removes it */
		setenv("PASS", pass, 1);
		memset(pass, 0, strlen(pass));
		free(pass);
	}
	/* argv lives as long as drawterm does */
	return dt_run(n, argv);
}

static void
nsetsurface(JNIEnv *env, jclass c, jobject surface)
{
	ANativeWindow *w;

	(void)c;
	w = surface != NULL ? ANativeWindow_fromSurface(env, surface) : NULL;
	dt_setwindow(w);
	if(w != NULL)
		ANativeWindow_release(w);
}

static void
nsetsize(JNIEnv *env, jclass c, jint w, jint h, jint scale)
{
	(void)env; (void)c;
	dt_setsize(w, h, scale);
}

static void
nmouse(JNIEnv *env, jclass c, jint x, jint y, jint buttons)
{
	(void)env; (void)c;
	dt_mouse(x, y, buttons);
}

static void
nkey(JNIEnv *env, jclass c, jint rune, jboolean down)
{
	(void)env; (void)c;
	dt_key(rune, down);
}

static const JNINativeMethod natives[] = {
	{ "run", "(Ljava/lang/String;[[B[B)I", (void*)nrun },
	{ "setSurface", "(Landroid/view/Surface;)V", (void*)nsetsurface },
	{ "setSize", "(III)V", (void*)nsetsize },
	{ "mouse", "(III)V", (void*)nmouse },
	{ "key", "(IZ)V", (void*)nkey },
};

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
	JNIEnv *env;
	jclass c;
	size_t i;

	(void)reserved;
	jvm = vm;
	if((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	c = (*env)->FindClass(env, "com/claude/drawterm/session/Native");
	if(c == NULL || (*env)->RegisterNatives(env, c, natives, nelem(natives)) != 0)
		return JNI_ERR;

	c = (*env)->FindClass(env, "com/claude/drawterm/session/NativeCallbacks");
	if(c == NULL)
		return JNI_ERR;
	cb = (*env)->NewGlobalRef(env, c);
	for(i = 0; i < nelem(callbacks); i++){
		*callbacks[i].id = (*env)->GetStaticMethodID(env, cb, callbacks[i].name, callbacks[i].sig);
		if(*callbacks[i].id == NULL){
			__android_log_print(ANDROID_LOG_ERROR, "drawterm", "missing callback %s%s",
				callbacks[i].name, callbacks[i].sig);
			return JNI_ERR;
		}
	}
	return JNI_VERSION_1_6;
}
