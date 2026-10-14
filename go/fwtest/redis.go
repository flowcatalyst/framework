package fwtest

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"
)

// EnvRedisURL names the variable that points the tests at an existing Redis or Valkey server
// (redis://host:port/db) instead of one started from PATH.
const EnvRedisURL = "FW_TEST_REDIS_URL"

// Redis is the test binary's Redis server, with a key prefix of this test's own.
type Redis struct {
	// URL is a redis:// URL of the server.
	URL string
	// Prefix is unique to the test ("fwtest:<pid>:<n>:"); keys under it belong to the test.
	Prefix string
}

// NewRedis returns the test binary's Redis server and a key prefix unique to this test. The
// first call starts redis-server (or valkey-server) from PATH on a free port, unless
// FW_TEST_REDIS_URL is set. When there is neither, the test fails with the instruction to
// install one: Redis tests need real Lua semantics and are never skipped.
func NewRedis(t testing.TB) Redis {
	t.Helper()
	state.mu.Lock()
	called := state.mainCalled
	state.mu.Unlock()
	if !called {
		t.Fatal("fwtest: Main was not called; add `func TestMain(m *testing.M) { fwtest.Main(m) }` to the package's tests")
	}
	state.redisOnce.Do(func() { state.redis, state.redisErr = newRedis() })
	if state.redisErr != nil {
		t.Fatalf("fwtest: Redis: %v", state.redisErr)
	}
	return Redis{
		URL:    state.redis.url,
		Prefix: fmt.Sprintf("fwtest:%d:%d:", os.Getpid(), state.counter.Add(1)),
	}
}

type redisServer struct {
	url string
	cmd *exec.Cmd
}

func newRedis() (*redisServer, error) {
	if raw := os.Getenv(EnvRedisURL); raw != "" {
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "redis" && u.Scheme != "rediss") {
			return nil, fmt.Errorf("%s must be a redis:// URL, got %q", EnvRedisURL, raw)
		}
		return &redisServer{url: raw}, nil
	}
	bin, err := exec.LookPath("redis-server")
	if err != nil {
		bin, err = exec.LookPath("valkey-server")
	}
	if err != nil {
		return nil, errors.New("neither redis-server nor valkey-server is on PATH and " + EnvRedisURL +
			" is not set. Install one (macOS: brew install redis; Debian/Ubuntu: apt install redis-server)," +
			" or run one in Docker (docker run -d -p 6390:6379 redis:8-alpine) and set " + EnvRedisURL +
			"=redis://127.0.0.1:6390/0. Redis tests are never skipped")
	}
	port, err := FreePort()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(bin, "--port", strconv.Itoa(port), "--bind", "127.0.0.1",
		"--save", "", "--appendonly", "no", "--protected-mode", "no", "--loglevel", "warning")
	cmd.Stdout, cmd.Stderr = nil, nil
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start %s: %w", bin, err)
	}
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(15 * time.Second)
	for {
		if err := ping(addr); err == nil {
			break
		} else if time.Now().After(deadline) {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
			return nil, fmt.Errorf("%s on port %d did not answer PING: %w", bin, port, err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	return &redisServer{url: "redis://" + addr + "/0", cmd: cmd}, nil
}

func ping(addr string) error {
	conn, err := net.DialTimeout("tcp", addr, time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write([]byte("PING\r\n")); err != nil {
		return err
	}
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return err
	}
	if !strings.HasPrefix(line, "+PONG") {
		return fmt.Errorf("unexpected reply %q", line)
	}
	return nil
}

func stopRedis() {
	if state.redis == nil || state.redis.cmd == nil {
		return
	}
	_ = state.redis.cmd.Process.Kill()
	_ = state.redis.cmd.Wait()
}
