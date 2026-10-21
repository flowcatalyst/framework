package s3store_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/objectstore"
	"github.com/flowcatalyst/framework/go/objectstore/objectstoretest"
	"github.com/flowcatalyst/framework/go/objectstore/s3store"
)

const limit = 4096

func TestMain(m *testing.M) {
	// The SDK's default chain finds these; the tests never reach a real AWS account.
	_ = os.Setenv("AWS_ACCESS_KEY_ID", "test")
	_ = os.Setenv("AWS_SECRET_ACCESS_KEY", "test")
	_ = os.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	os.Exit(m.Run())
}

// fakeS3 is a small in-process S3 (path-style GET, PUT, DELETE and HEAD bucket) so the driver is
// tested over a real HTTP wire with the real SDK.
type fakeS3 struct {
	bucket  string
	srv     *httptest.Server
	failing atomic.Int32 // requests still to answer with a 403 (the SDK does not retry it)

	mu      sync.Mutex
	objects map[string][]byte
}

func newFakeS3(t *testing.T, bucket string) *fakeS3 {
	t.Helper()
	f := &fakeS3{bucket: bucket, objects: map[string][]byte{}}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeS3) object(key string) ([]byte, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	v, ok := f.objects[key]
	return v, ok
}

func (f *fakeS3) handle(w http.ResponseWriter, r *http.Request) {
	if f.failing.Load() > 0 && f.failing.Add(-1) >= 0 {
		xmlError(w, 403, "AccessDenied", "boom")
		return
	}
	path := r.URL.Path
	if path != "/"+f.bucket && !strings.HasPrefix(path, "/"+f.bucket+"/") {
		xmlError(w, 404, "NoSuchBucket", "no bucket")
		return
	}
	key := strings.TrimPrefix(strings.TrimPrefix(path, "/"+f.bucket), "/")
	switch r.Method {
	case http.MethodHead:
		w.WriteHeader(200)
	case http.MethodPut:
		body, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.objects[key] = body
		f.mu.Unlock()
		w.Header().Set("ETag", `"fake"`)
		w.WriteHeader(200)
	case http.MethodGet:
		v, ok := f.object(key)
		if !ok {
			xmlError(w, 404, "NoSuchKey", "nope")
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(v)
	case http.MethodDelete:
		f.mu.Lock()
		delete(f.objects, key)
		f.mu.Unlock()
		w.WriteHeader(204)
	default:
		w.WriteHeader(405)
	}
}

func xmlError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/xml")
	w.WriteHeader(status)
	_, _ = w.Write([]byte("<Error><Code>" + code + "</Code><Message>" + msg + "</Message></Error>"))
}

// build runs objectstore.Build inside a started application.
func build(t *testing.T, opts ...objectstore.BuildOption) (*objectstore.Stores, error) {
	t.Helper()
	app := fw.New("s3store-test", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := objectstore.Declare(app)
	var out *objectstore.Stores
	r, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		out, err = objectstore.Build(ctx, b, d, opts...)
		return nil, err
	})
	if err == nil {
		t.Cleanup(func() { _ = r.Shutdown() })
	}
	return out, err
}

func one(t *testing.T, f objectstore.StoreFactory) objectstore.Store {
	t.Helper()
	all, err := build(t, objectstore.WithStore("s3", f))
	if err != nil {
		t.Fatal(err)
	}
	s, _ := all.Default()
	return s
}

func TestContractAgainstFakeS3(t *testing.T) {
	t.Parallel()
	fake := newFakeS3(t, "test-bucket")
	s := one(t, s3store.Bucket("test-bucket", s3store.Endpoint(fake.srv.URL), s3store.Region("us-east-1"),
		s3store.Prefix("p/q"), s3store.MaxBytes(limit)))
	objectstoretest.Run(t, objectstoretest.Harness{Limit: limit, New: func(*testing.T) objectstore.Store { return s }})
}

func TestFakeS3Specifics(t *testing.T) {
	t.Parallel()
	fake := newFakeS3(t, "test-bucket")
	s := one(t, s3store.Bucket("test-bucket", s3store.Endpoint(fake.srv.URL), s3store.Region("us-east-1"), s3store.Prefix("p/q")))
	ctx := t.Context()

	t.Run("values live under the prefix in the bucket", func(t *testing.T) {
		if err := s.Put(ctx, "hello/world.json", []byte{1, 2}); err != nil {
			t.Fatal(err)
		}
		if v, ok := fake.object("p/q/hello/world.json"); !ok || !bytes.Equal(v, []byte{1, 2}) {
			t.Errorf("object: %v %v", v, ok)
		}
	})
	t.Run("a server error is an ErrStore naming the key", func(t *testing.T) {
		fake.failing.Store(1000)
		defer fake.failing.Store(0)
		_, _, err := s.Get(ctx, "x")
		check(t, err, "cannot read 'x'")
		check(t, s.Put(ctx, "x", []byte{1}), "cannot write 'x'")
		check(t, s.Delete(ctx, "x"), "cannot delete 'x'")
	})
	t.Run("a missing bucket fails start-up naming the store and bucket", func(t *testing.T) {
		_, err := build(t, objectstore.WithStore("lost", s3store.Bucket("no-such-bucket", s3store.Endpoint(fake.srv.URL), s3store.Region("us-east-1"))))
		if err == nil || !strings.Contains(err.Error(), "object store 'lost'") || !strings.Contains(err.Error(), "no-such-bucket") {
			t.Errorf("%v", err)
		}
	})
	t.Run("verification at start-up can be turned off", func(t *testing.T) {
		all, err := build(t, objectstore.WithStore("quiet", s3store.Bucket("no-such-bucket", s3store.Endpoint(fake.srv.URL),
			s3store.Region("us-east-1"), s3store.VerifyAtStartup(false))))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := all.Get("quiet"); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("bad bucket names, prefixes and limits are refused when built", func(t *testing.T) {
		for _, bad := range []string{"", "UPPER", "a", "has space", "-lead", "trail-"} {
			if _, err := build(t, objectstore.WithStore("b", s3store.Bucket(bad, s3store.VerifyAtStartup(false)))); err == nil ||
				!strings.Contains(err.Error(), "object store 'b'") {
				t.Errorf("bucket %q: %v", bad, err)
			}
		}
		if _, err := build(t, objectstore.WithStore("b", s3store.Bucket("good-name", s3store.Prefix("../x")))); err == nil {
			t.Error("prefix ../x accepted")
		}
		if _, err := build(t, objectstore.WithStore("b", s3store.Bucket("good-name", s3store.MaxBytes(0)))); err == nil {
			t.Error("limit 0 accepted")
		}
	})
}

func check(t *testing.T, err error, want string) {
	t.Helper()
	if !errors.Is(err, objectstore.ErrStore) || !strings.Contains(err.Error(), want) {
		t.Errorf("want ErrStore containing %q, got %v", want, err)
	}
}

// TestContractAgainstS3Service runs the same suite against a real S3-compatible service, only
// when FW_S3_ENDPOINT is set (for example http://localhost:4566 for LocalStack). It makes a bucket
// of its own and removes everything it created.
func TestContractAgainstS3Service(t *testing.T) {
	endpoint, ok := os.LookupEnv("FW_S3_ENDPOINT") //nolint:forbidigo // opt-in switch of this test only
	if !ok || endpoint == "" {
		t.Skip("FW_S3_ENDPOINT is not set")
	}
	ctx := t.Context()
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"))
	if err != nil {
		t.Fatal(err)
	}
	admin := s3.NewFromConfig(cfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(endpoint)
		o.UsePathStyle = true
	})
	var rnd [6]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		t.Fatal(err)
	}
	bucket := "fw-objectstore-test-" + hex.EncodeToString(rnd[:])
	if _, err := admin.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		// The test context is cancelled by now; clean up with a context that is not.
		cctx := context.WithoutCancel(ctx)
		var token *string
		for {
			list, err := admin.ListObjectsV2(cctx, &s3.ListObjectsV2Input{Bucket: aws.String(bucket), ContinuationToken: token})
			if err != nil {
				t.Errorf("list: %v", err)
				break
			}
			for _, o := range list.Contents {
				if _, err := admin.DeleteObject(cctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: o.Key}); err != nil {
					t.Errorf("delete %s: %v", aws.ToString(o.Key), err)
				}
			}
			if !aws.ToBool(list.IsTruncated) {
				break
			}
			token = list.NextContinuationToken
		}
		if _, err := admin.DeleteBucket(cctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)}); err != nil {
			t.Errorf("delete bucket: %v", err)
		}
	})
	s := one(t, s3store.Bucket(bucket, s3store.Endpoint(endpoint), s3store.Region("us-east-1"), s3store.Prefix("contract"), s3store.MaxBytes(limit)))
	objectstoretest.Run(t, objectstoretest.Harness{Limit: limit, New: func(*testing.T) objectstore.Store { return s }})
}
