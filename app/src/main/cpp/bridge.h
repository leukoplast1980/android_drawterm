/*
 * The drawterm side (screen.c, devandroid.c: drawterm headers) and the JNI
 * side (jni.c, hooks.c: system headers only) talk through these plain-C
 * functions, so neither sees the other's headers.
 */
struct ANativeWindow;

/* screen.c */
int	dt_run(int argc, char **argv);
void	dt_setwindow(struct ANativeWindow *w);	/* NULL when the surface goes */
void	dt_setsize(int width, int height, int scale);	/* in drawterm pixels */
void	dt_mouse(int x, int y, int buttons);
void	dt_key(int rune, int down);
char*	dt_lastlines(int maxlines);	/* malloc'd tail of the console log */

/* hooks.c */
extern int dt_aan;	/* -p: aan reconnects dropped links */

/* jni.c; strings are UTF-8, returned buffers malloc'd */
char*	jni_clipread(void);
void	jni_clipwrite(const char *s);
void	jni_notify(const char *s);
int	jni_numcameras(void);
int	jni_takepicture(int camera, unsigned char **jpeg);	/* length, or -1 */
void	jni_setcursor(const unsigned int *argb16x16, int hotx, int hoty);
void	jni_mouseset(int x, int y);
void	jni_wantkeyboard(int show);
void	jni_exited(int code, const char *s);	/* returns once the UI knows */
const char*	jni_packagename(void);
