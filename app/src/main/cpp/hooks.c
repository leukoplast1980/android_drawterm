/*
 * Hooks into upstream drawterm through the linker's --wrap (CMakeLists.txt).
 * If upstream renames a wrapped function, __real_* goes undefined and the
 * link fails, rather than the hook silently going away.
 *
 *	exit		report why the session ended before the process goes
 *	readcons	show the soft keyboard while drawterm prompts
 *	so_connect	tune TCP sockets for mobile networks
 */
#include <android/log.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

#include "bridge.h"

int dt_aan;

void	__wrap_exit(int);
char*	__wrap_readcons(char*, char*, int);
char*	__real_readcons(char*, char*, int);
void	__wrap_so_connect(int, unsigned char*, unsigned short);
void	__real_so_connect(int, unsigned char*, unsigned short);

void
__wrap_exit(int code)
{
	static pthread_mutex_t once = PTHREAD_MUTEX_INITIALIZER;
	char *msg;

	/* the first caller reports; any others wait here for _exit */
	pthread_mutex_lock(&once);
	msg = dt_lastlines(8);
	__android_log_print(ANDROID_LOG_INFO, "drawterm", "exit(%d): %s", code, msg != NULL ? msg : "");
	jni_exited(code, msg != NULL ? msg : "");
	/* not exit(): atexit handlers racing the VM's threads crash on Android */
	_exit(code);
}

char*
__wrap_readcons(char *prompt, char *def, int raw)
{
	char *s;

	jni_wantkeyboard(1);
	s = __real_readcons(prompt, def, raw);
	jni_wantkeyboard(0);
	return s;
}

void
__wrap_so_connect(int fd, unsigned char *raddr, unsigned short rport)
{
	int type, on, idle, intvl, cnt;
	unsigned int timeout;
	socklen_t len;

	len = sizeof type;
	if(getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) == 0 && type == SOCK_STREAM){
		/* 9P and aan send small header and body writes; don't delay them */
		on = 1;
		setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on);
		if(dt_aan){
			/*
			 * aan redials and resends, so drop a link that stopped
			 * acknowledging; the 9front server gives up after ~24s.
			 */
			timeout = 30*1000;
			setsockopt(fd, IPPROTO_TCP, TCP_USER_TIMEOUT, &timeout, sizeof timeout);
		}else{
			/*
			 * An idle session sends nothing, and carrier NATs forget
			 * silent connections. A probe every 4 idle minutes keeps
			 * it known; ten unanswered probes in a row end it.
			 */
			idle = 240, intvl = 60, cnt = 10;
			setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof on);
			setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof idle);
			setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof intvl);
			setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof cnt);
		}
	}
	__real_so_connect(fd, raddr, rport);
}
