package push

import (
	"strconv"
	"strings"
	"testing"
)

func TestCursorIsTheNewestDispatchedPush(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		if got := n.p.Cursor(); got != "0" {
			t.Fatalf("cursor of an empty log %q, want 0", got)
		}
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": 1})
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": 2})
		ids := n.settle(2)
		if got, want := n.p.Cursor(), strconv.FormatInt(ids[1], 10); got != want {
			t.Fatalf("cursor %q, want the newest push %s", got, want)
		}
	})
}

func TestSnapshotCursorReplaysOnlyLaterPushes(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "A"})
		n.settle(1)
		cursor := n.p.Cursor() // before the state read
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "B"})
		ids := n.settle(2)

		c := orderStream(t, n, cursor)
		f := c.next()
		if !strings.Contains(f, `"v":"B"`) || pushID(t, f) != ids[1] {
			t.Fatalf("first push %q, want B (%d)", f, ids[1])
		}
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "C"})
		if f := c.next(); !strings.Contains(f, `"v":"C"`) {
			t.Fatalf("next push %q, want C and no repeat of A", f)
		}
	})
}

func TestSnapshotCursorOfAnEmptyLogReplaysEverything(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		cursor := n.p.Cursor()
		if cursor != "0" {
			t.Fatalf("cursor %q, want 0", cursor)
		}
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "A"})
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "B"})
		ids := n.settle(2)
		c := orderStream(t, n, cursor)
		for i, v := range []string{"A", "B"} {
			f := c.next()
			if !strings.Contains(f, `"v":"`+v+`"`) || pushID(t, f) != ids[i] {
				t.Fatalf("push %d %q, want %s (%d)", i, f, v, ids[i])
			}
		}
	})
}

func TestInvalidLastEventIDGetsAResetThenLive(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "old"})
		ids := n.settle(1)
		for i, bad := range []string{"12-34", "abc", "-1"} {
			c := orderStream(t, n, bad)
			f := c.next()
			if want := resetEvent(resetUnknownID, ids[len(ids)-1]); f != want {
				t.Fatalf("Last-Event-ID %q: got %q, want %q", bad, f, want)
			}
			n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": bad})
			if f := c.next(); !strings.Contains(f, `"v":"`+bad+`"`) {
				t.Fatalf("Last-Event-ID %q: after the reset got %q, want the live push", bad, f)
			}
			c.close()
			ids = n.settle(i + 2) // the log now holds the first push and one per round
		}
	})
}

func TestBlankLastEventIDIsNoCursor(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "old"})
		n.settle(1)
		c := orderStream(t, n, "  ")
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "live"})
		if f := c.next(); !strings.Contains(f, `"v":"live"`) {
			t.Fatalf("got %q, want the live push first and no reset", f)
		}
	})
}
