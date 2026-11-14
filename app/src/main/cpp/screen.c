/*
 * drawterm's screen, cursor and clipboard on Android.
 *
 * drawterm calls flushmemscreen with drawlock held. The UI thread can't take
 * drawlock (qlock needs a drawterm proc), so it hands resize and redraw
 * requests to androidproc.
 */
#include <android/native_window.h>
#include <android/log.h>
#include <pthread.h>

#include "u.h"
#include "lib.h"
#include "dat.h"
#include "fns.h"
#include "error.h"

#include <draw.h>
#include <memdraw.h>
#include <keyboard.h>
#include <cursor.h>
#include "screen.h"

#include "bridge.h"

int	dt_main(int, char**);

Memimage *gscreen;

/* the window; flushes hold winlk while drawing */
static pthread_mutex_t winlk = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *win;
static int winscale = 1;

/* requests for androidproc */
static pthread_mutex_t reqlk = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t reqcv = PTHREAD_COND_INITIALIZER;
static int wantw = 640, wanth = 480;
static int reqresize, reqredraw;
static int screenup;

static void
request(int resize, int redraw)
{
	pthread_mutex_lock(&reqlk);
	reqresize |= resize;
	reqredraw |= redraw;
	pthread_cond_signal(&reqcv);
	pthread_mutex_unlock(&reqlk);
}

static void
androidproc(void *v)
{
	int resize, redraw, w, h;

	USED(v);
	for(;;){
		pthread_mutex_lock(&reqlk);
		while(!reqresize && !reqredraw)
			pthread_cond_wait(&reqcv, &reqlk);
		resize = reqresize;
		redraw = reqredraw;
		w = wantw;
		h = wanth;
		reqresize = reqredraw = 0;
		pthread_mutex_unlock(&reqlk);

		if(resize)
			screenresize(Rect(0, 0, w, h));
		if(redraw){
			qlock(&drawlock);
			if(gscreen != nil)
				flushmemscreen(gscreen->r);
			qunlock(&drawlock);
		}
	}
}

/*
 * Copy r of gscreen (XRGB32) to the window (RGBX), scaled by a whole
 * factor so pixels stay sharp. Called with winlk held, win != nil.
 */
static void
blit(Rectangle r)
{
	ANativeWindow_Buffer b;
	ARect d;
	int s, x, y, sx, sy, sw, sh, top, bot, left, right;
	u32int *dst, *src, p;

	s = winscale;
	if(!rectclip(&r, gscreen->r))
		return;
	d.left = r.min.x*s;
	d.top = r.min.y*s;
	d.right = r.max.x*s;
	d.bottom = r.max.y*s;
	if(ANativeWindow_lock(win, &b, &d) != 0)
		return;

	/* the dirty region may come back larger, e.g. for a fresh buffer */
	left = d.left < 0 ? 0 : d.left;
	top = d.top < 0 ? 0 : d.top;
	right = d.right > b.width ? b.width : d.right;
	bot = d.bottom > b.height ? b.height : d.bottom;
	sw = Dx(gscreen->r);
	sh = Dy(gscreen->r);
	if(left >= right || top >= bot){
		ANativeWindow_unlockAndPost(win);
		return;
	}

	for(y = top; y < bot; y++){
		dst = (u32int*)b.bits + (uintptr)y*b.stride;
		sy = y/s;
		if(sy >= sh){
			for(x = left; x < right; x++)
				dst[x] = 0xFF000000;
			continue;
		}
		/* rows sampling the same source row repeat the previous one */
		if(y > top && (y-1)/s == sy){
			memmove(dst+left, (u32int*)b.bits + (uintptr)(y-1)*b.stride + left, (right-left)*4);
			continue;
		}
		src = (u32int*)byteaddr(gscreen, Pt(gscreen->r.min.x, gscreen->r.min.y+sy));
		for(x = left; x < right; x++){
			sx = x/s;
			if(sx >= sw){
				dst[x] = 0xFF000000;
				continue;
			}
			p = src[sx];
			dst[x] = 0xFF000000 | (p&0xFF)<<16 | (p&0xFF00) | (p>>16 & 0xFF);
		}
	}
	ANativeWindow_unlockAndPost(win);
}

void
flushmemscreen(Rectangle r)
{
	if(gscreen == nil)
		return;
	pthread_mutex_lock(&winlk);
	if(win != nil)
		blit(r);
	pthread_mutex_unlock(&winlk);
}

void
screensize(Rectangle r, ulong chan)
{
	Memimage *mi;

	mi = allocmemimage(r, chan);
	if(mi == nil)
		return;
	if(gscreen != nil)
		freememimage(gscreen);
	gscreen = mi;
	gscreen->clipr = ZR;
}

void
screeninit(void)
{
	Rectangle r;

	memimageinit();
	pthread_mutex_lock(&reqlk);
	r = Rect(0, 0, wantw, wanth);
	pthread_mutex_unlock(&reqlk);
	screensize(r, XRGB32);
	if(gscreen == nil)
		panic("screensize failed");
	gscreen->clipr = r;
	kproc("android", androidproc, nil);
	qlock(&drawlock);
	terminit();
	flushmemscreen(gscreen->r);
	qunlock(&drawlock);

	/* the window may have changed size meanwhile */
	pthread_mutex_lock(&reqlk);
	screenup = 1;
	if(wantw != Dx(r) || wanth != Dy(r))
		reqresize = 1;
	pthread_cond_signal(&reqcv);
	pthread_mutex_unlock(&reqlk);
}

Memdata*
attachscreen(Rectangle *r, ulong *chan, int *depth, int *width, int *softscreen)
{
	*r = gscreen->clipr;
	*chan = gscreen->chan;
	*depth = gscreen->depth;
	*width = gscreen->width;
	*softscreen = 1;

	gscreen->data->ref++;
	return gscreen->data;
}

void
getcolor(ulong v, ulong *r, ulong *g, ulong *b)
{
	*r = (v>>16)&0xFF;
	*g = (v>>8)&0xFF;
	*b = v&0xFF;
}

void
setcolor(ulong i, ulong r, ulong g, ulong b)
{
	USED(i, r, g, b);
}

void
setcursor(void)
{
	uint px[16*16];
	uchar clr[2*16], set[2*16];
	Point off;
	int i, j, c, s, m;

	lock(&cursor.lk);
	off = cursor.offset;
	memmove(clr, cursor.clr, sizeof clr);
	memmove(set, cursor.set, sizeof set);
	unlock(&cursor.lk);

	for(i = 0; i < 16; i++){
		c = clr[2*i]<<8 | clr[2*i+1];
		s = set[2*i]<<8 | set[2*i+1];
		for(j = 0; j < 16; j++){
			m = 0x8000 >> j;
			if(s & m)
				px[i*16+j] = 0xFF000000;
			else if(c & m)
				px[i*16+j] = 0xFFFFFFFF;
			else
				px[i*16+j] = 0;
		}
	}
	jni_setcursor(px, -off.x, -off.y);
}

void
mouseset(Point p)
{
	jni_mouseset(p.x, p.y);
}

char*
clipread(void)
{
	return jni_clipread();
}

int
clipwrite(char *buf)
{
	jni_clipwrite(buf);
	return strlen(buf);
}

void
guimain(void)
{
	cpubody();
}

/* --- bridge.h --- */

int
dt_run(int argc, char **argv)
{
	return dt_main(argc, argv);
}

void
dt_setwindow(ANativeWindow *w)
{
	pthread_mutex_lock(&winlk);
	if(win != nil)
		ANativeWindow_release(win);
	win = w;
	if(win != nil){
		ANativeWindow_acquire(win);
		ANativeWindow_setBuffersGeometry(win, 0, 0, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
	}
	pthread_mutex_unlock(&winlk);
	if(w != nil)
		request(0, 1);
}

void
dt_setsize(int w, int h, int scale)
{
	if(w < 64)
		w = 64;
	if(h < 64)
		h = 64;
	if(scale < 1)
		scale = 1;

	pthread_mutex_lock(&winlk);
	winscale = scale;
	pthread_mutex_unlock(&winlk);

	pthread_mutex_lock(&reqlk);
	if(screenup){
		reqresize |= wantw != w || wanth != h;
		reqredraw = 1;
		pthread_cond_signal(&reqcv);
	}
	wantw = w;
	wanth = h;
	pthread_mutex_unlock(&reqlk);
}

/* input before the screen (and devices) are up is dropped */
void
dt_mouse(int x, int y, int buttons)
{
	if(screenup)
		absmousetrack(x, y, buttons, ticks());
}

void
dt_key(int rune, int down)
{
	if(screenup)
		kbdkey((Rune)rune, down);
}

char*
dt_lastlines(int maxlines)
{
	char *s, *b, *p, *e;
	int n, lines;

	/* exiting: a racy read is fine */
	n = kmesg.n;
	if(n > (int)sizeof kmesg.buf)
		n = sizeof kmesg.buf;
	b = kmesg.buf + (n > 4096 ? n - 4096 : 0);
	e = kmesg.buf + n;
	while(e > b && (e[-1] == '\n' || e[-1] == '\r' || e[-1] == ' ' || e[-1] == '\t'))
		e--;
	lines = 0;
	for(p = e; p > b; p--)
		if(p[-1] == '\n' && ++lines >= maxlines)
			break;
	s = malloc(e - p + 1);
	if(s == nil)
		return nil;
	memmove(s, p, e - p);
	s[e - p] = 0;
	return s;
}
