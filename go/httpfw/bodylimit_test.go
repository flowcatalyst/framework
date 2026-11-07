package httpfw_test

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

type upload struct {
	Data string `json:"data"`
}

var uploadOp = httpfw.Post[upload, itemView]("/api/uploads", validate.Of(validate.Field("data", func(u upload) string { return u.Data }, validate.MaxLength(1000)))).ID("upload").Build()

// body is a JSON body of exactly n bytes.
func body(n int) string { return `{"data":"` + strings.Repeat("x", n-11) + `"}` }

// chunked hides the length, so the limit is enforced while reading.
func chunked(r *http.Request) *http.Request {
	r.ContentLength = -1
	r.Body = io.NopCloser(r.Body)
	return r
}

func TestAPerRouteBodyLimitRefusesALargerBodyWhileOtherRoutesKeepTheGlobalOne(t *testing.T) {
	t.Parallel()
	echo := func(w http.ResponseWriter, r *http.Request) error {
		v, err := httpfw.DecodeJSON[upload](r)
		if err != nil {
			return err
		}
		return jsonBody(w, `{"n":`+itoa(len(v.Data))+`}`)
	}
	rt := httpfw.NewRoutes("test")
	rt.Handle("POST /small", echo, httpfw.MaxBody(20))
	rt.Handle("POST /large", echo, httpfw.MaxBody(100)) // above the global limit: photos
	rt.Handle("POST /global", echo)
	rt.Bind(httpfw.Handle(uploadOp, func(_ context.Context, _ fw.Context, in httpfw.In[upload]) (itemView, error) {
		return itemView{}, nil
	}, httpfw.MaxBody(20)))
	d := compile(t, []httpfw.Option{httpfw.WithMaxBodyBytes(32)}, rt)
	for _, c := range []struct {
		path   string
		size   int
		status int
	}{
		{"/small", 20, 200},
		{"/small", 21, 413},
		{"/global", 32, 200},
		{"/global", 33, 413},
		{"/large", 100, 200},
		{"/large", 101, 413},
		{"/api/uploads", 20, 200},
		{"/api/uploads", 21, 413},
	} {
		got := do(d, "POST", c.path, body(c.size), "Content-Type", "application/json")
		if got.status != c.status {
			t.Errorf("%s with %d bytes: %d %s, want %d", c.path, c.size, got.status, got.body, c.status)
		}
		if c.status == 413 && !strings.Contains(got.body, `"PAYLOAD_TOO_LARGE"`) {
			t.Errorf("%s: %s", c.path, got.body)
		}
		rec := newRecorder()
		d.ServeHTTP(rec, chunked(request("POST", c.path, body(c.size), "Content-Type", "application/json")))
		if rec.Code != c.status {
			t.Errorf("%s with %d bytes, no declared length: %d %s, want %d", c.path, c.size, rec.Code, rec.Body.String(), c.status)
		}
	}
}

func TestAPerRouteBodyLimitMustBePositive(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("test")
	rt.Handle("POST /zero", func(http.ResponseWriter, *http.Request) error { return nil }, httpfw.MaxBody(0))
	_, err := httpfw.Compile([]*httpfw.Routes{rt})
	if err == nil || !strings.Contains(err.Error(), "MaxBody") {
		t.Fatalf("got %v", err)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for ; n > 0; n /= 10 {
		b = append([]byte{byte('0' + n%10)}, b...)
	}
	return string(b)
}
