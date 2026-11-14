/*
 * #N, bound after /dev by upstream main.c:
 *
 *	camN.jpg	read a fresh photo
 *	accel		read "x y z" acceleration, m/s²
 *	compass		read "x y z" magnetic field, µT
 *	notification	write to post an Android notification
 */
#include <android/sensor.h>
#include <android/looper.h>

#include "u.h"
#include "lib.h"
#include "dat.h"
#include "fns.h"
#include "error.h"

#include "bridge.h"

enum
{
	Qdir,
	Qcam,
	Qaccel,
	Qcompass,
	Qnotification,

	Maxnote	= 4096,
	Sensortimeout = 2000,	/* ms */
};

#define QTYPE(path)	((int)((path) & 0xFF))
#define QCAM(path)	((int)((path) >> 8))
#define QPATH(cam, type)	(((vlong)(cam) << 8) | (type))

typedef struct Picture Picture;
struct Picture
{
	long	len;
	uchar	data[];
};

static int ncameras;
static ASensorManager *sensormgr;

static void
androidinit(void)
{
	ncameras = jni_numcameras();
	sensormgr = ASensorManager_getInstanceForPackage(jni_packagename());
}

static Chan*
androidattach(char *spec)
{
	Chan *c;

	c = devattach('N', spec);
	mkqid(&c->qid, QPATH(0, Qdir), 0, QTDIR);
	return c;
}

static int
androidgen(Chan *c, char *name, Dirtab *tab, int ntab, int s, Dir *dp)
{
	Qid q;

	USED(name, tab, ntab);
	if(s == DEVDOTDOT){
		mkqid(&q, QPATH(0, Qdir), 0, QTDIR);
		devdir(c, q, "#N", 0, eve, 0555, dp);
		return 1;
	}
	if(s < ncameras){
		snprint(up->genbuf, sizeof up->genbuf, "cam%d.jpg", s);
		mkqid(&q, QPATH(s, Qcam), 0, QTFILE);
		devdir(c, q, up->genbuf, 0, eve, 0444, dp);
		return 1;
	}
	switch(s - ncameras){
	case 0:
		mkqid(&q, QPATH(0, Qaccel), 0, QTFILE);
		devdir(c, q, "accel", 0, eve, 0444, dp);
		return 1;
	case 1:
		mkqid(&q, QPATH(0, Qcompass), 0, QTFILE);
		devdir(c, q, "compass", 0, eve, 0444, dp);
		return 1;
	case 2:
		mkqid(&q, QPATH(0, Qnotification), 0, QTFILE);
		devdir(c, q, "notification", 0, eve, 0222, dp);
		return 1;
	}
	return -1;
}

static Walkqid*
androidwalk(Chan *c, Chan *nc, char **name, int nname)
{
	return devwalk(c, nc, name, nname, nil, 0, androidgen);
}

static int
androidstat(Chan *c, uchar *db, int n)
{
	return devstat(c, db, n, nil, 0, androidgen);
}

static Chan*
androidopen(Chan *c, int omode)
{
	Picture *pic;
	uchar *jpeg;
	int n;

	pic = nil;
	if(QTYPE(c->qid.path) == Qcam){
		if(openmode(omode) != OREAD)
			error(Eperm);
		n = jni_takepicture(QCAM(c->qid.path), &jpeg);
		if(n < 0)
			error("camera unavailable or permission denied");
		pic = malloc(sizeof(Picture) + n);
		if(pic == nil){
			free(jpeg);
			error(Enomem);
		}
		pic->len = n;
		memmove(pic->data, jpeg, n);
		free(jpeg);
	}
	if(waserror()){
		free(pic);
		nexterror();
	}
	c = devopen(c, omode, nil, 0, androidgen);
	poperror();
	c->aux = pic;
	return c;
}

static void
androidclose(Chan *c)
{
	if(QTYPE(c->qid.path) == Qcam && (c->flag & COPEN)){
		free(c->aux);
		c->aux = nil;
	}
}

/* one fresh event from a sensor */
static long
sensorread(int type, char *a, long n)
{
	const ASensor *sensor;
	ASensorEventQueue *q;
	ASensorEvent ev;
	ALooper *looper;
	int got, waited;
	char buf[128];

	if(sensormgr == nil)
		error("no sensor manager");
	sensor = ASensorManager_getDefaultSensor(sensormgr, type);
	if(sensor == nil)
		error("no such sensor on this device");

	looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
	q = ASensorManager_createEventQueue(sensormgr, looper, 1, nil, nil);
	if(q == nil)
		error("cannot create sensor event queue");
	if(ASensorEventQueue_enableSensor(q, sensor) < 0){
		ASensorManager_destroyEventQueue(sensormgr, q);
		error("cannot enable sensor");
	}

	got = 0;
	for(waited = 0; !got && waited < Sensortimeout; waited += 50){
		ALooper_pollOnce(50, nil, nil, nil);
		while(ASensorEventQueue_getEvents(q, &ev, 1) > 0)
			got = 1;
	}
	ASensorEventQueue_disableSensor(q, sensor);
	ASensorManager_destroyEventQueue(sensormgr, q);
	if(!got)
		error("sensor timed out");

	snprint(buf, sizeof buf, "%11f %11f %11f\n", ev.vector.x, ev.vector.y, ev.vector.z);
	return readstr(0, a, n, buf);
}

static long
androidread(Chan *c, void *a, long n, vlong off)
{
	Picture *pic;

	switch(QTYPE(c->qid.path)){
	case Qdir:
		return devdirread(c, a, n, nil, 0, androidgen);
	case Qcam:
		pic = c->aux;
		if(pic == nil || off >= pic->len)
			return 0;
		if(off + n > pic->len)
			n = pic->len - off;
		memmove(a, pic->data + off, n);
		return n;
	case Qaccel:
		return sensorread(ASENSOR_TYPE_ACCELEROMETER, a, n);
	case Qcompass:
		return sensorread(ASENSOR_TYPE_MAGNETIC_FIELD, a, n);
	}
	error(Egreg);
	return -1;
}

static long
androidwrite(Chan *c, void *a, long n, vlong off)
{
	char *s;
	long m;

	USED(off);
	switch(QTYPE(c->qid.path)){
	case Qnotification:
		m = n > Maxnote ? Maxnote : n;	/* the rest is dropped, not refused */
		s = malloc(m+1);
		if(s == nil)
			error(Enomem);
		memmove(s, a, m);
		s[m] = 0;
		jni_notify(s);
		free(s);
		return n;
	}
	error(Eperm);
	return -1;
}

Dev androiddevtab = {
	'N',
	"android",

	devreset,
	androidinit,
	devshutdown,
	androidattach,
	androidwalk,
	androidstat,
	androidopen,
	devcreate,
	androidclose,
	androidread,
	devbread,
	androidwrite,
	devbwrite,
	devremove,
	devwstat,
};
