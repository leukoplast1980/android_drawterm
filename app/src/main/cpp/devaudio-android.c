/*
 * drawterm's audio backend on Android: playback through AAudio, so that
 * /mnt/term/dev/audio on the server plays on the phone. No recording.
 *
 * /dev/audio is 16-bit little-endian stereo at 44.1 kHz, which AAudio
 * takes as is (resampling for the hardware if needed). Writes block until
 * AAudio has room, so players run at the right speed.
 *
 * "audio" in /dev/volume is a software gain, linear in decibels like
 * 9front's drivers: 100 is full volume, each step down is 0.4 dB, 0 is
 * silence. The phone's own volume applies on top.
 */
#include <aaudio/AAudio.h>

#include "u.h"
#include "lib.h"
#include "dat.h"
#include "fns.h"
#include "error.h"
#include "devaudio.h"

enum
{
	Rate = 44100,
	Channels = 2,
	Frame = 2*Channels,	/* bytes */
	Nbuf = 1024,		/* frames converted at a time */
};

/* one blocking write; far more than AAudio's buffer takes to drain */
static const int64_t Timeout = 2000000000LL;

static AAudioStream *stream;
static uchar part[Frame];	/* a frame split between two writes */
static int npart;
static int vol[2] = { 100, 100 };
static int gain[2] = { 1<<16, 1<<16 };	/* from vol, 16.16 fixed point */
static short buf[Nbuf*Channels];

static void
closestream(void)
{
	aaudio_stream_state_t st;
	int i;

	if(stream == nil)
		return;
	/* stopping plays out what is buffered; wait for that, within limits */
	if(AAudioStream_requestStop(stream) == AAUDIO_OK){
		st = AAudioStream_getState(stream);
		for(i = 0; i < 2 && (st == AAUDIO_STREAM_STATE_STARTED || st == AAUDIO_STREAM_STATE_STOPPING); i++)
			if(AAudioStream_waitForStateChange(stream, st, &st, Timeout) != AAUDIO_OK)
				break;
	}
	AAudioStream_close(stream);
	stream = nil;
}

static void
openstream(void)
{
	AAudioStreamBuilder *b;
	aaudio_result_t r;

	r = AAudio_createStreamBuilder(&b);
	if(r != AAUDIO_OK)
		error((char*)AAudio_convertResultToText(r));
	AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
	AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setChannelCount(b, Channels);
	AAudioStreamBuilder_setSampleRate(b, Rate);
	r = AAudioStreamBuilder_openStream(b, &stream);
	AAudioStreamBuilder_delete(b);
	if(r != AAUDIO_OK){
		stream = nil;
		error((char*)AAudio_convertResultToText(r));
	}
	if(AAudioStream_getSampleRate(stream) != Rate
	|| AAudioStream_getChannelCount(stream) != Channels
	|| AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16){
		closestream();
		error("audio: no 44.1 kHz 16-bit stereo output");
	}
	r = AAudioStream_requestStart(stream);
	if(r != AAUDIO_OK){
		closestream();
		error((char*)AAudio_convertResultToText(r));
	}
}

static void
play(short *p, int n)
{
	aaudio_result_t r;
	int reopened;

	reopened = 0;
	while(n > 0){
		r = AAudioStream_write(stream, p, n, Timeout);
		if(r == AAUDIO_ERROR_DISCONNECTED && !reopened){
			/* the output changed (headphones, Bluetooth): carry on with the new one */
			closestream();
			openstream();
			reopened = 1;
			continue;
		}
		if(r < 0)
			error((char*)AAudio_convertResultToText(r));
		if(r == 0)
			error("audio: output stalled");
		p += r*Channels;
		n -= r;
	}
}

void
audiodevopen(void)
{
	npart = 0;
	openstream();
}

void
audiodevclose(void)
{
	closestream();
}

int
audiodevwrite(void *a, int n)
{
	uchar *p, *e;
	int i, m, s;

	if(stream == nil)
		error("audio: not open");
	p = a;
	e = p+n;
	while(p < e){
		m = 0;
		while(m < Nbuf && p < e){
			part[npart++] = *p++;
			if(npart < Frame)
				continue;
			for(i = 0; i < Channels; i++){
				s = (short)(part[2*i] | part[2*i+1]<<8);
				buf[m*Channels+i] = (s*gain[i]) >> 16;
			}
			npart = 0;
			m++;
		}
		if(m > 0)
			play(buf, m);
	}
	return n;
}

int
audiodevread(void *a, int n)
{
	USED(a);
	USED(n);
	error("audio: no recording");
	return -1;
}

static int
volgain(int v)
{
	double g;

	if(v <= 0)
		return 0;
	g = 65536.0;
	for(; v < 100; v++)
		g *= 0.954993;	/* -0.4 dB */
	return (int)g;
}

void
audiodevsetvol(int what, int left, int right)
{
	/* only "audio"; "reset" sets the others too, which is harmless */
	if(what != Vaudio)
		return;
	if(left >= 0){
		vol[0] = left > 100 ? 100 : left;
		gain[0] = volgain(vol[0]);
	}
	if(right >= 0){
		vol[1] = right > 100 ? 100 : right;
		gain[1] = volgain(vol[1]);
	}
}

void
audiodevgetvol(int what, int *left, int *right)
{
	/* the others stay at -1, so /dev/volume doesn't list them */
	if(what != Vaudio)
		return;
	*left = vol[0];
	*right = vol[1];
}
