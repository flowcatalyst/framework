#!/bin/sh
# Starts and stops the processes of the push load test: an embedded PostgreSQL (./pgdb) and the
# demo server (./main.go), each recorded by PID in $PUSHLOAD_DIR. Only those PIDs are ever signalled.
#
#   loadtest.sh build          build push-demo, pgdb and loadgen into $PUSHLOAD_DIR/bin
#   loadtest.sh start          start PostgreSQL (once) and the server; wait until it is ready
#   loadtest.sh start-server   start (or start again) the server only
#   loadtest.sh kill-server    kill -9 the recorded server PID
#   loadtest.sh stop-server    stop the server only (SIGTERM)
#   loadtest.sh restart-hard   kill -9 the server and start it again (what loadgen -mode restart runs)
#   loadtest.sh stop           stop the server (SIGTERM) and PostgreSQL (SIGTERM)
#   loadtest.sh status         the recorded PIDs and whether they run
#
# Environment: PUSHLOAD_DIR (default ${TMPDIR:-/tmp}/push-load), DEMO_PORT (18100), DEMO_PORTS (8),
# DEMO_DEBUG_PORT (18199), PUSH_* settings (push.relay.interval etc.) pass through to the server.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
dir=${PUSHLOAD_DIR:-${TMPDIR:-/tmp}/push-load}
mkdir -p "$dir/bin"
port=${DEMO_PORT:-18100}

alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

secrets() {
	# fresh per test directory, kept across server restarts so stream tokens stay valid
	if [ ! -f "$dir/secrets.env" ]; then
		s=$(openssl rand -base64 48 | tr -d '\n=+/')
		k=$(openssl rand 32 | base64 | tr '+/' '-_' | tr -d '=\n')
		printf 'PUSH_STREAM_SECRET=%s\nPUSH_TICKET_KEY=load-1:%s\n' "$s" "$k" >"$dir/secrets.env"
		chmod 600 "$dir/secrets.env"
	fi
}

start_pg() {
	if alive "$dir/pg.pid"; then return; fi
	rm -f "$dir/db.url"
	"$dir/bin/pgdb" "$dir/db.url" >"$dir/pg.log" 2>&1 &
	echo $! >"$dir/pg.pid"
	i=0
	while [ ! -s "$dir/db.url" ]; do
		i=$((i + 1)); [ $i -gt 300 ] && { echo "PostgreSQL did not start; see $dir/pg.log" >&2; exit 1; }
		sleep 0.2
	done
}

start_server() {
	secrets
	if alive "$dir/server.pid"; then echo "server already running" >&2; return; fi
	set -a; . "$dir/secrets.env"; set +a
	DEMO_DATABASE_URL=$(cat "$dir/db.url") DEMO_PORT=$port "$dir/bin/push-demo" >>"$dir/server.log" 2>&1 &
	echo $! >"$dir/server.pid"
	i=0
	until curl -fs "http://127.0.0.1:$port/health/ready" >/dev/null 2>&1; do
		i=$((i + 1)); [ $i -gt 300 ] && { echo "server not ready; see $dir/server.log" >&2; exit 1; }
		sleep 0.1
	done
}

case "${1:-}" in
build)
	cd "$root"
	go build -o "$dir/bin/push-demo" ./examples/push-demo
	go build -o "$dir/bin/pgdb" ./examples/push-demo/pgdb
	go build -o "$dir/bin/loadgen" ./examples/push-demo/loadgen
	;;
start) start_pg; start_server; echo "ready: ports $port..$((port + ${DEMO_PORTS:-8} - 1)), server pid $(cat "$dir/server.pid")" ;;
start-server) start_server ;;
kill-server) if alive "$dir/server.pid"; then kill -9 "$(cat "$dir/server.pid")"; fi ;;
stop-server) if alive "$dir/server.pid"; then kill -TERM "$(cat "$dir/server.pid")"; i=0; while alive "$dir/server.pid"; do i=$((i + 1)); [ $i -gt 200 ] && break; sleep 0.1; done; fi; rm -f "$dir/server.pid" ;;
restart-hard)
	if alive "$dir/server.pid"; then kill -9 "$(cat "$dir/server.pid")"; fi
	rm -f "$dir/server.pid"
	# the dead process's listeners are gone at once; start the replacement without waiting for readiness
	secrets; set -a; . "$dir/secrets.env"; set +a
	DEMO_DATABASE_URL=$(cat "$dir/db.url") DEMO_PORT=$port "$dir/bin/push-demo" >>"$dir/server.log" 2>&1 &
	echo $! >"$dir/server.pid"
	;;
stop)
	for n in server pg; do
		if alive "$dir/$n.pid"; then
			kill -TERM "$(cat "$dir/$n.pid")"
			i=0; while alive "$dir/$n.pid"; do i=$((i + 1)); [ $i -gt 200 ] && break; sleep 0.1; done
		fi
		rm -f "$dir/$n.pid"
	done
	;;
status)
	for n in server pg; do
		if alive "$dir/$n.pid"; then echo "$n: running, pid $(cat "$dir/$n.pid")"; else echo "$n: stopped"; fi
	done
	;;
*) sed -n '2,17p' "$0"; exit 2 ;;
esac
