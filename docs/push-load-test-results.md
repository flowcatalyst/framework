# Push to clients: load test results (local, 2026-11-08)

First run of the load test of [push contract](contracts/push.md) section 11, on the Go module
(`go/push`, the `postgres` log driver). **This is a single-machine local run: the load generator,
the server and PostgreSQL share one Mac and its CPU. It is not the contract's target (one 8 GB
Fargate task, with the database elsewhere), so read the memory figures as the module's, and the
delay and CPU figures as "what this code costs", not as a capacity promise.** Nothing was tuned or
optimised; every number is for the module as it stands (commit of branch `feat/push-load`).

## What was built

All under `go/examples/push-demo/`:

| File | What |
|---|---|
| `main.go` | The demo server: a framework app with the push module, base path `/api`, one **public** topic rule `pushdemo:t:{id}`, `GET /api/demo/ticket` (a ticket for the demo's client, because a subscribe without credentials needs one), `POST /api/demo/publish` (each item is one `push.PublishAlone`; the data carries `seq` and `ts`, the unix nanoseconds stamped just before the write). It listens on `DEMO_PORTS` (default 8) consecutive ports with the same routes, serves `/metrics`, and on a separate loopback port `/debug/stats` (`runtime.MemStats`, goroutines, open fds) and `/debug/pprof`. |
| `pgdb/main.go` | Starts the framework's embedded PostgreSQL 18 on a free port and creates the database `pushdemo`; stops it on SIGTERM. |
| `loadgen/main.go` | The load generator (modes below), with unit tests in `main_test.go` (chunk decoding, histogram, loss check, and a whole fan-out run against a fake server). |
| `loadtest.sh` | Starts and stops the processes by recorded PID (`build`, `start`, `start-server`, `stop-server`, `kill-server`, `restart-hard`, `stop`, `status`). It signals only the PIDs it recorded. |

The subscribe and stream URLs are the standard ones (`POST /api/push/subscriptions`,
`GET /api/push/stream?st=...`, `GET /api/push/keys`), so the field-js reader can be pointed at the
same server later. The server's secrets (`PUSH_STREAM_SECRET`, `PUSH_TICKET_KEY`) are generated
fresh by `loadtest.sh` into its work directory (mode 600) and kept across server restarts, so a
stream token stays valid across a restart.

### How to run

```sh
export PUSHLOAD_DIR=/tmp/push-load          # work dir: binaries, pid files, logs, secrets
go/examples/push-demo/loadtest.sh build     # push-demo, pgdb, loadgen into $PUSHLOAD_DIR/bin
go/examples/push-demo/loadtest.sh start     # PostgreSQL (free port) + server on 18100..18107, debug 18199
L="$PUSHLOAD_DIR/bin/loadgen -pid-file $PUSHLOAD_DIR/server.pid"

$L -mode idle      -streams 50000 -hold 15s                       # hold N idle streams
$L -mode fanout    -streams 10000 -topics 10000 -rate 1000 -duration 60s \
                   -profile-dir $PUSHLOAD_DIR/prof                 # R pushes/s, CPU+heap profile of the server
$L -mode broadcast -streams 50000 -bcast-count 10 -bcast-gap 2s    # one topic, time to the last receiver
$L -mode restart   -streams 10000 -topics 10000 -rate 1000 -restart-at 20s -tail 15s -retry 3s \
                   -restart-cmd "go/examples/push-demo/loadtest.sh restart-hard"
go/examples/push-demo/loadtest.sh stop
```

`-lsof=false` skips the (slow at 100k fds) `lsof` count; `/debug/stats` also reports the fd count.
`make -C go test` runs the generator's unit tests.

### What the generator does and measures

- **Streams:** per stream, `POST /api/push/subscriptions` (one topic, a ticket), then a raw TCP
  `GET /api/push/stream?st=...` (HTTP/1.1, chunked body decoded by hand, about 8 KiB per stream
  client-side). A drop is followed by a reconnect after `-retry` (3 s, the server's `retry:` field)
  plus up to 50 % jitter, with the same stream token and `Last-Event-ID`.
- **Delay:** receipt time minus the `ts` in the push data, in a 4 %-resolution log histogram (the
  `max` is exact). `ts` is taken in the server just before `PublishAlone`, so the delay includes the
  commit, the relay's wake-up (NOTIFY), the hub's fan-out and the write. Client and server share a clock.
- **Loss:** the publisher paces `-rate` pushes/s by posting batches to the demo's publish endpoint;
  only pushes the server **acknowledged** count as expected. Push `seq` goes to topic `seq mod topics`.
  After the run each stream's received seqs are compared with the acknowledged seqs of its topic:
  `missing`, `duplicates`, and `receivedButUnacked` (committed, but the answer was lost).
- **Server:** RSS and CPU seconds from `ps`, heap/stack/goroutines/fds from `/debug/stats`, `lsof`,
  and the server's `fw_push_*` metrics at the end.

## Machine and settings

Apple M4 Pro, 14 cores, 48 GB, macOS 15.6.1, Go 1.27.3, embedded PostgreSQL 18.3 with its default
settings (fsync on) on a local port, `ulimit -n 1048576`, `kern.maxfiles 368640`,
`kern.maxfilesperproc 184320`. The machine was **not idle**: other users' work kept the load average
at 30 to 57 throughout. Push settings: defaults, except `push.max-streams` 250 000,
`push.relay.interval` 100 ms (the relay is woken by NOTIFY; the interval is only the fallback poll)
and `push.stream.max-lifetime` 24 h. Eight server ports (18100 to 18107).

### The macOS ephemeral-port limit (finding about the method)

The brief expected about 16 k ephemeral ports **per destination address:port**. On this macOS the
limit is **about 16.3 k outbound connections in total from the machine, whatever the destination**:
16 296 connections to four ports of 127.0.0.1 succeeded and the rest failed with `can't assign
requested address`; a different destination address (the Mac's LAN address) did not help. The first
25 k run stalled at about 16 k streams for that reason. The generator therefore binds each
connection's own local port (`SO_REUSEPORT`, ports 25000 to 49151, `-bind-local`, default on): the
four-tuple stays unique because the eight server ports differ. With it, 100 000 connections from one
process worked. (The first 50 k attempt stopped at 49 952 of 50 000 because another program held
local ports 30007 and 30008; the generator now skips taken ports.) No system setting was changed.

## Idle connections

One fresh server process per step; "after hold" is 15 s after the ramp, with the streams idle
(only the 25 s heartbeat). Every step: 0 drops, 0 reconnects. Memory per stream is
(RSS at N minus RSS at 0) / N, with the Go-accounted figure beside it because RSS under-reports at
100 k (macOS compresses idle pages while memory is tight):

| Streams | Ramp | Server RSS | Go heap in use | Go stacks | Goroutines | Open fds (`lsof`) | Server CPU total | **RSS per stream** | Go heap+stack per stream | Generator RSS |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | | 17 MiB | 3.6 MiB | 0.8 MiB | 23 | 23 | | | | 9 MiB |
| 1 000 | 0.5 s | 93 MiB | 54 MiB | 23 MiB | 2 292 | 1 294 | 0.3 s | 78.6 KiB | 74.5 KiB | 35 MiB |
| 5 000 | 0.5 s | 370 MiB | 243 MiB | 95 MiB | 10 319 | 5 321 | 1.2 s | 72.4 KiB | 68.6 KiB | 92 MiB |
| 10 000 | 0.5 s | 684 MiB | 481 MiB | 186 MiB | 20 322 | 10 324 | 2.2 s | 68.4 KiB | 68.0 KiB | 170 MiB |
| 25 000 | 1.0 s | 1 706 MiB | 1 196 MiB | 460 MiB | 50 333 | 25 335 | 5.6 s | 69.2 KiB | 67.7 KiB | 359 MiB |
| 50 000 | 3.0 s | 3 489 MiB | 2 385 MiB | 912 MiB | 100 327 | 50 329 | 17.0 s | 71.1 KiB | 67.5 KiB | 701 MiB |
| 100 000 | 10.5 s | 5 494 MiB | 4 897 MiB | 1 869 MiB | 200 022 | 100 256 | 47.9 s | 56.1 KiB (compressed) | 69.3 KiB | 1 434 MiB |

- **About 68 to 71 KiB per stream, flat from 5 k to 100 k.** Two goroutines per stream (the
  connection's and net/http's background reader) and one fd per stream plus about 330 for the process.
- 100 000 streams were reached with no error, on this 48 GB machine (memory free stayed at 66 %).
  Go-accounted memory there is **6.8 GiB**; that does not fit an 8 GB task with any margin (below).
- Ramp rates were 10 k to 25 k streams/s with 300 connecting at once (subscribe POST plus stream open).
- Stopping a 50 k to 100 k stream server with SIGTERM overran the framework's 10 s drain
  (`loop did not stop within the drain period`, `shutdown action overran`): a stop took about a minute.

Where the 68 KiB go (profile of a 10 k-stream server, section "Hot spots"): the per-stream channel
buffer `push.stream.buffer` (256 entries of about 76 bytes, allocated in `hub.open`) about 18.6 KiB;
goroutine stacks about 18.5 KiB; net/http's per-connection bufio reader and writer about 7.7 KiB; the
remaining 23 KiB is request, header, context, timer and map overhead.

## Fan-out (10 000 streams, 10 000 topics, one stream per topic)

| Rate | Duration | Pushes (acked) | Delay p50 | p90 | p95 | p99 | p99.9 | max | Missing | Duplicates | Server CPU | Server RSS |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 000/s | 60 s | 60 220 (999.9/s achieved) | 1.59 ms | 2.18 ms | 2.36 ms | **4.97 ms** | 16.8 ms | 18.3 ms | **0** | 0 | 10.0 s (0.17 core) | 681 to 898 MiB |
| 5 000/s | 30 s | 151 200 (4 998/s) | 2.36 ms | 5.17 ms | 12.3 ms | **43.0 ms** | 145 ms | 220 ms | **0** | 0 | 21.5 s (0.71 core) | 688 to 1 093 MiB |

The contract's target (1 000 pushes/s over 10 000 topics, **p99 under 250 ms**) is met with a wide
margin here, including at five times the rate. 0 drops, 0 reconnects, 0 resets in both runs. CPU per
delivered push (publish transaction, relay, hub and write together): 166 µs at 1 000/s, 142 µs at
5 000/s. The generator used 1.4 s and 3.0 s of CPU. Memory grew by about 200 to 400 MiB during the
runs (garbage and the 10 000-entry `push.recent` ring), not per stream.

## Broadcast (one topic, 50 000 streams)

One push to the topic; "time to last receiver" is the largest delay over the 50 000 receivers, all
of which got every push (0 missing, 0 duplicates in both runs; 500 000 and 250 000 deliveries).

| Run | Pushes | Time to last receiver, per push |
|---|---|---|
| 10 pushes 2 s apart | 1 to 10 | 0.76, 0.96, 1.88, 9.46, 8.17, 6.16, 6.62, 4.61, 1.05, 4.20 s |
| 5 pushes 12 s apart | 1 to 5 | 3.21, 3.89, 2.84, 0.80, 0.77 s |

An isolated broadcast to 50 000 streams took **0.8 s at best and up to about 4 s**. In the 2-second
run the slow pushes overlapped the next (one 50 000-way fan-out costs about 6 s of server CPU on
this Mac, about 120 µs per stream, one write and flush each), so the 9.5 s is a backlog, not one
push's time. Server and generator compete for the same cores and the machine was loaded, so the
spread says more about this setup than the module. The server did not drop or slow any stream.

## Restart under load

10 000 streams, 1 000 pushes/s over 10 000 topics, `kill -9` of the server 20 s into publishing,
the same binary started again at once (the one server process; PostgreSQL stayed up). Reconnect
delay 3 s plus jitter (up to 4.5 s):

| | |
|---|---|
| Streams dropped / resumed | 10 000 / 10 000 |
| First stream back | 3.07 s after the kill (the 3 s retry floor) |
| **All 10 000 streams back** | **5.44 s after the kill** |
| Pushes acknowledged | 37 420 |
| **Lost** | **0** (0 duplicates, 0 resets) |
| Pushes the publisher could not write | 980 failed and 7 060 skipped while the server was down (never acknowledged, so not "lost") |
| Pushes delivered late (over 1 s) | the last one 16.4 s after the kill; worst delay 15.3 s |
| Replays | `fw_push_replay_source_total{source="log"}` = 9 999, `memory` = 0 |
| Server after the restart | RSS 3.5 GiB (steady 0.8 GiB), Go heap 1.5 GiB (0.58), CPU 41 s in 45 s |

Two findings, both visible in the numbers and in the code, neither fixed:

1. **The relay's lock lease is the restart delay.** The killed server held `svc:push-relay` (lease
   15 s); the new process could not log any push until it expired, so everything committed in that
   window arrived up to about 15 s late (hence the 15.3 s worst delay). The clients themselves
   were back in 5.4 s. With two tasks the other task takes the lock over only after the same lease.
2. **A restarted instance serves every resume from the log, not memory, and does it expensively.**
   The new process's recent-push ring starts empty (`from` is the log's last id at boot), so all
   9 999 resumes went to `replayLog`, which reads the log in pages of 1 000 rows for **all** topics
   and filters in Go (`Log.ReadAfter` has no topic filter). Each stream therefore read every push
   committed since its own `Last-Event-ID`: about 15 000 to 20 000 rows each, times 10 000 streams.
   That is the heap and RSS spike (+0.9 GiB heap, +2.7 GiB RSS over steady state at only 10 000
   streams) and the database load of the contract's "restart storm". Contract section 8's "served
   from memory, not the database" holds for an instance that stayed up, not for the one restarted.

## Hot spots (profiles of the server during the 1 000 pushes/s fan-out)

CPU, 30 s: 4.26 s of samples, 14 % of one core.

| Where | Share | Note |
|---|---:|---|
| `syscall.rawsyscalln` (socket writes, PostgreSQL I/O) | 37 % | one `write` plus flush per push per stream; nothing is batched |
| `runtime.kevent` (netpoll) | 27 % | |
| `pthread_cond_wait` / `pthread_cond_signal` (goroutine wake-ups) | 21 % / 8 % | one wake-up per delivery |
| `push.(*Pushes).PublishAlone` (cumulative) | 18 % | the demo's publisher, `pushrow.Insert` 5.4 % of it; not stream cost |
| `push.(*relay).once` (cumulative) | 7.3 % | `PostgresLog.Append` 2.1 % |
| `push.(*Pushes).listen` writes (`bufio.Flush`) | 4.7 to 6.3 % | |
| `hub.dispatch`, `hub.catchUp` | under 1 % | the fan-out bookkeeping is not a cost at this rate |

No function in the push code stands out; the time is in the system calls of per-stream writes. Heap
(in use, 10 000 streams): `hub.open` **59 %** (the 256-entry channel per stream, 18.6 KiB), net/http's
`bufio.NewWriterSize` 14 % and `NewReaderSize` 10 %. Candidates the owner may want to look at, none
built: a smaller default `push.stream.buffer`, or a buffer allocated lazily or grown; a topic filter
in `Log.ReadAfter` and a limit on concurrent log replays (finding 2); lowering the relay lock's
recovery time. A 50 000-way broadcast is bounded by the one write per stream (about 120 µs of CPU each here).

## What this suggests for one 8 GB task (not measured there)

- **Memory:** about 68 KiB per stream (RSS) with the defaults. 8 GB is 8 192 MiB: 50 000 streams
  need about 3.5 GiB (a comfortable 2x headroom, which also covers GC), 100 000 need about 6.8 GiB
  before the kernel's socket buffers (counted in a container's memory) and any spike, so **100 000
  per task is not reachable with the defaults; 40 000 to 60 000 is the realistic range**, or lower
  `push.stream.buffer` (16 to 64 would save 10 to 15 KiB, an estimate).
- **Restart storm:** the spike of finding 2 (about 360 KiB per resuming stream at 10 000) would not
  fit at 50 000 streams. Until the replay is bounded, size for the storm, not the steady state, or
  keep two tasks so resumes spread and the survivor serves them from memory.
- **File descriptors:** one per stream plus about 330; a task with 100 000 streams needs `nofile`
  of about 105 000 or more (Fargate's default hard limit is 1 048 576).
- **CPU:** about 140 to 170 µs per delivered push including the publisher and the relay; 1 000
  deliveries/s is under 0.2 core, 5 000/s is 0.7 core. Broadcast is the CPU-bound case: about 120 µs
  per receiving stream, so a 50 000-way push is about 6 s of CPU, 3 s on two vCPUs, if Linux costs
  the same (it probably costs less). Holding idle streams costs the 25 s heartbeat: 100 000 streams
  are 4 000 writes/s, roughly 0.4 core at the per-write cost seen here (an estimate; the ramp of
  100 000 streams used 29 s of CPU).
- **Delay:** p99 5 ms at 1 000/s and 43 ms at 5 000/s over 10 000 topics, far inside 250 ms; the
  figure that matters on the real target is the database round trips of the relay and the publisher.

## Caveats

- Local, one machine, loaded by other users (load average 30 to 57); the generator, the server and
  PostgreSQL share CPU, and PostgreSQL is an embedded default-configured one. Absolute delay and
  broadcast times are pessimistic for CPU and optimistic for network (loopback, no TLS, no ALB).
- macOS: kernel socket memory is not in RSS; the compressor makes RSS fall below the Go-accounted
  figure at 100 000 streams. Linux numbers will differ (goroutine stacks, `epoll`, cgroup memory).
- The restart test used one server, so the relay was down until the lease expired and nothing
  survived to serve resumes from memory; the contract's two-task test is not run. The database load
  was not measured with PostgreSQL's own counters, only inferred from the replay-source metric,
  the code and the process CPU.
- Pushes carry only `seq` and `ts` (about 50 bytes); `-pad` adds bytes. Heartbeats (25 s) are the
  only traffic on idle streams. Each stream has one topic; a stream with many topics costs more
  (a map entry per topic in `hub.byTopic`).
- The publisher posts to the demo endpoint over HTTP, so publish concurrency is bounded (48 requests
  of up to 8 parallel `PublishAlone` each); at 5 000/s nothing was skipped.
- Not run: A2 (`redis`), two tasks, the 75 000 step (100 000 was run directly), TLS.
