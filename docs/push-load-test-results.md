# Push to clients: load test results (local, 2026-11-08)

First run of the load test of [push contract](contracts/push.md) section 11, on the Go module
(`go/push`, the `postgres` log driver). **This is a single-machine local run: the load generator,
the server and PostgreSQL share one Mac and its CPU. It is not the contract's target (one 8 GB
Fargate task, with the database elsewhere), so read the memory figures as the module's, and the
delay and CPU figures as "what this code costs", not as a capacity promise.** Nothing was tuned or
optimised; every number is for the module as it stands (commit of branch `feat/push-load`). The four
findings were then fixed; the same tests run again are in [After the fixes](#after-the-fixes-2026-11-08),
at the end, beside the numbers here, which are kept as they were.

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

## After the fixes (2026-11-08)

Branch `feat/push-hardening` fixed the four findings above (contract section 17, amendment 11). The
same commands were run again on the same Mac in the night of 8 to 9 November (load average 5 to 27,
lower than the first run's 30 to 57), with the generator unchanged. Where a figure depends on the
machine's load, the old code (`before`, the commit of the first run) was run again beside the new one
(`after`) in the same hour, alternating; the original numbers stay in the sections above. Results,
logs and profiles are in the session's work directory, not the repository.

### What changed

| Finding | Fix |
|---|---|
| Relay lease 15 s | `push.relay.lease`, default 5 s (1 s to 60 s), refreshed every third. A cleanly stopping relay already released its lock (`locks.With` releases when the run returns); a run is now let finish instead of being cancelled, so it returns within the drain and the release always happens. |
| Restart served from the log | Before it serves, the hub reads the newest `push.recent + 1` entries in one read (`Log.Newest`) into its memory. A log replay holds one page (1 000 entries) and at most 10 000 outbox ids, never the whole replay, and streams resuming from the same id at once share each page read. |
| Slow shutdown | The hub ends every stream at once when the server's drain begins, opens none after it, and stops reading the log. A log read or relay run under way at shutdown finishes rather than being cancelled (bounded by 10 s). |
| 68 KiB per stream | The stream buffer holds pointers to pushes encoded once and shared, and grows only as far as the stream falls behind (an idle stream holds none). Two goroutines per stream are kept (below). |

### Idle connections

One fresh server per step, as before; "after hold" figures. 0 drops, 0 reconnects at every step.

| Streams | Ramp | Server RSS | Go heap in use | Go stacks | Goroutines | Open fds | Server CPU total | **RSS per stream** (before) | Go heap+stack per stream (before) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 000 | 0.5 s | 448 MiB | 222 MiB | 186 MiB | 20 327 | 10 328 | 2.2 s | **44.1 KiB** (68.4) | 41.3 KiB (68.0) |
| 50 000 | 2.5 s | 2 092 MiB | 1 095 MiB | 917 MiB | 100 327 | 50 328 | 11.7 s | **42.5 KiB** (71.1) | 41.1 KiB (67.5) |
| 100 000 | 7.0 s | 4 133 MiB | 2 277 MiB | 1 857 MiB | 200 024 | 100 224 | 34.3 s | **42.1 KiB** (56.1, compressed) | 42.3 KiB (69.3) |

- **About 42 to 44 KiB per stream, down from 68 to 71 KiB** (Go heap per stream 22 KiB, was 49 KiB;
  stacks unchanged at about 19 KiB). 100 000 streams are 4.1 GiB, 50 000 are 2.1 GiB. In the heap
  profile of the 1 000/s fan-out, `hub.open` is now 4.5 % of the heap in use (was 59 %); net/http's
  bufio reader and writer (about 9.3 KiB per stream) are now the largest item.
- **Still two goroutines per stream.** The second is net/http's background read of the connection,
  which is how a client that leaves is noticed at once (its stream ends, its slot under
  `push.max-streams-per-principal` is freed, nothing more is written to it). Only hijacking the
  connection removes it, and then a client that leaves is noticed at the next write, up to the 25 s
  heartbeat later; the module's tests rely on the prompt release. Not done.

### Fan-out (10 000 streams, 10 000 topics)

`before` and `after` run in the same hour; 0 missing, 0 duplicates, 0 drops in all four.

| Rate | Code | Pushes | p50 | p90 | p95 | p99 | p99.9 | max | Server CPU while publishing | Server RSS |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 000/s | before | 61 680 | 2.65 ms | 4.42 ms | 5.37 ms | 7.35 ms | 19.6 ms | 46.3 ms | 16.1 s | 677 to 920 MiB |
| 1 000/s | after | 61 580 | 2.76 ms | 4.59 ms | 5.37 ms | **7.07 ms** | 14.9 ms | 31.0 ms | 16.2 s | 437 to 605 MiB |
| 5 000/s | before | 158 700 | 2.27 ms | 3.23 ms | 3.78 ms | 6.05 ms | 14.9 ms | 18.8 ms | 19.5 s | 684 to 1 035 MiB |
| 5 000/s | after | 158 500 | 2.36 ms | 3.23 ms | 3.78 ms | **6.05 ms** | 16.8 ms | 34.3 ms | 19.8 s | 431 to 620 MiB |

The delay did not regress (a second `after` run at 5 000/s: p99 6.29 ms, max 112 ms). CPU is the
same; memory is about 250 to 400 MiB lower. The first run's 43 ms p99 at 5 000/s was the loaded
machine: the old code gives 6 ms on the quieter one.

### Restart under load (`kill -9`, 10 000 streams, 1 000 pushes/s)

Two rounds, `before` and `after` alternating (round 2 of `before` ran while the load average rose to
27). 0 lost, 0 duplicates in every run.

| | before 1 | before 2 | after 1 | after 2 |
|---|---:|---:|---:|---:|
| All streams back | 5.04 s | 6.46 s | 5.14 s | 4.88 s |
| **Last push over 1 s late** (after the kill) | 11.9 s | 31.2 s | **5.1 s** | **5.8 s** |
| **Worst delay** | 8.3 s | 24.4 s | **4.0 s** | **3.8 s** |
| p99 delay | 7.9 s | 8.9 s | 3.8 s | 1.7 s |
| Replays from memory / from the log | 0 / 9 999 | 0 / 9 999 | **9 983 / 16** | **8 770 / 1 230** |
| Server RSS (Go heap) 3 s after all were back | 3 244 MiB (1 928) | 3 015 MiB (1 259) | **453 MiB (240)** | **977 MiB (589)** |

- **The relay is back after the lease:** pushes committed while no relay ran now arrive at most
  about 5 s after the kill (the 5 s lease), not 12 to 31 s.
- **A restarted instance resumes from memory.** The streams still read from the log are those whose
  last push is older than the newest `push.recent` (10 000) pushes when they reconnect: here each
  topic gets one push every 10 s, so the memory holds about 10 s, and the relay's catch-up after the
  lease pushes the oldest out while the last clients reconnect (4 to 5 s). With `PUSH_RECENT=30000`
  every resume came from memory (10 000 / 0), the worst delay was 3.8 s and the last late push 5.1 s
  after the kill.
- **The storm costs a few hundred MiB, not 3 GiB**, and a log replay holds one page at a time.
- Earlier `after` runs while the machine was busy (ramps of 6 s and 21 s instead of 0.5 s) were
  slower: all back in 7.3 s and 4.6 s, worst delays 9.4 s and 29.8 s, from memory 7 492 and 6 664.
  The slow part there is the CPU-starved server serving 10 000 resumes, not the lease.

### SIGTERM drain (time from SIGTERM to the process's exit)

| Streams | before | after |
|---:|---|---|
| 50 000 | 11.5, 11.3, 34.9, 21.2, 9.0, 4.2, 4.7, 21.6 s | 9.5, 4.2, 3.9, 7.5, 2.5, 0.9 s |
| 100 000 | 24.3 s (first run: about a minute) | 10.7 s, 14.4 s; 135 s on the degraded machine (below) |

**Why it was slow.** Not the streams: they all see the drain at once (a closed channel) and the HTTP
server's drain finished within a second even before. Two things took the time:

1. **A database query cancelled half-way.** Shutdown cancelled the hub's read of the log (and could
   cancel a relay run). pgx then abandons the connection: it sends PostgreSQL a cancel request on a
   new connection and waits up to 15 s for it to be acknowledged, and closing the pool waits for
   that. A goroutine dump taken 5 s after SIGTERM showed nothing left but `pgxpool.Close` waiting
   for a connection whose `CancelRequest` was still reading. The old code logged `shutdown action
   overran app.shutdown.drain` on `db` in 5 of its 9 runs; the new code in none of the 8 whose log
   was kept (`db` closes in under 2 ms). The first `after` build, which only bounded the reads instead of stopping
   them at the drain, still overran in 2 of 3 runs: a read that started during the drain timed out.
2. **Closing tens of thousands of loopback connections at once on macOS.** With both ends of every
   connection on one Mac, closing them makes the kernel stall the loopback stack for seconds: new
   connections to PostgreSQL time out (`connect: operation timed out`), 10 000 goroutines sit in
   `write` or `close` system calls (Go's 10 000-thread limit was hit twice by each build in the
   10-push broadcast, below, and once by the generator), and 80 000 to 110 000 orphaned sockets in
   `FIN_WAIT_1`/`CLOSING` took several minutes to clear (the runner waited for them between steps).
   In the demo most streams are on its seven extra ports, plain `http.Server`s closed by the
   `demo:ports` shutdown action: that action took 0.6 to 7 s at 50 000 and overran its 10 s at
   100 000. In the `after` runs, SIGTERM to "loops stopped, server drained" took 0.3 to 0.7 s at
   50 000 and 0.5 s and 4.0 s at 100 000; the rest is the demo's ports. The 135 s run came after
   several 100 000-stream steps had left about 110 000 orphaned sockets: its ramp took 52 s instead
   of 7, the relay lost its lock 7 times while the streams connected, a log read timed out at
   +7.9 s and the relay loop did not stop within 10 s (it was in a database call that could not
   connect).

### Broadcast (one topic, 50 000 streams)

| Run | Code | Time to the last receiver, per push |
|---|---|---|
| 5 pushes 12 s apart | after | 0.85, 1.34, 2.00, 2.58, 2.68 s (before, first run: 3.21, 3.89, 2.84, 0.80, 0.77 s) |
| 10 pushes 2 s apart | after, clean run | 1.21, 1.05, 1.27, 1.54, 5.68, 12.96, 10.53, 8.16, 6.06, 3.82 s; 0 missing |

The 10-push run, which overlaps one push's fan-out with the next, overloads this Mac with either
code: both `before` runs and two of three `after` runs ended with the server killed by `fatal
error: thread exhaustion` (about 10 000 goroutines blocked in socket `write` system calls on the
loopback, above), so their pushes from the crash on count as missing (before: 33 985 and 9 344;
after: 19 137 and 25 703); nothing was missing while a server ran. An isolated 50 000-way push
still takes about 1 to 3 s to its last receiver here.

### What else the runs showed

- **A 5 s lease is lost more easily.** While the loopback stalled (100 000 streams connecting, the
  close storms) the relay's lock refresh failed for longer than the lease 12 times in the `after`
  runs whose logs were kept (`loop lost its lock while running`, 7 of them in the degraded 100 000
  run); never in the `before` runs with the old 15 s lease. The relay then stops and
  takes the lock again; a run under way finishes, and the log is still right (contract section 3).
  On a database that can stall for seconds, set `push.relay.lease` higher; failover after a crash
  takes up to the lease.
- **macOS limits, not the module's:** the thread limit and the loopback stall above, and `lsof` once
  hung for 15 minutes on the server's fds (the fan-out runs since use `-lsof=false`). Linux behaves
  differently; the Fargate run is still the one that counts.

### For one 8 GB task (estimate, not measured there)

About 42 KiB per stream: 100 000 streams are about 4.1 GiB, so **100 000 per task now fit 8 GB
with roughly 2x headroom** before the kernel's socket buffers. A restart storm of 10 000 resumes
costs hundreds of MiB, not 3 GiB. Keep `push.recent` at least the pushes of the longest gap a client
may have between its last push and its reconnect (the default 10 000 is 10 s at 1 000 pushes/s).
