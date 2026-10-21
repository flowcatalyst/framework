// Package s3store is the S3 object store driver (object-stores contract section 4), on the AWS SDK
// for Go v2. Credentials and region come from the SDK's default chains (environment, shared
// profile, container or instance role), so nothing secret is configured here.
//
//	objectstore.WithStore("archive", s3store.Bucket("acme-archive", s3store.Prefix("ingest/sets")))
package s3store

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"regexp"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/objectstore"
)

var bucketName = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`)

// Option configures [Bucket].
type Option func(*settings)

type settings struct {
	prefix   string
	region   string
	endpoint string
	maxBytes int
	verify   bool
}

// Prefix puts all keys under a prefix inside the bucket, so several stores can share a bucket. It
// is itself a valid key (see [objectstore.CheckKey]), for example "ingest/sets".
func Prefix(p string) Option { return func(s *settings) { s.prefix = p } }

// Region sets the AWS region; without it the SDK's default chain decides (AWS_REGION and so on).
func Region(r string) Option { return func(s *settings) { s.region = r } }

// Endpoint selects an S3-compatible service (LocalStack, MinIO), for example
// "http://localhost:4566". Path-style addressing is used with it, and request and response
// checksums only where required (not every compatible service accepts the trailer).
func Endpoint(url string) Option { return func(s *settings) { s.endpoint = url } }

// MaxBytes sets the largest value accepted (at least 1; default [objectstore.DefaultMaxBytes]).
func MaxBytes(n int) Option { return func(s *settings) { s.maxBytes = n } }

// VerifyAtStartup sets whether start-up checks the bucket with a HeadBucket call (default true;
// it needs the s3:ListBucket permission, so turn it off if the role lacks it).
func VerifyAtStartup(v bool) Option { return func(s *settings) { s.verify = v } }

// Bucket is the factory of the S3 store for one bucket, which must already exist. A bucket name
// S3 does not allow, a bad prefix or limit fail when the store is built, naming it.
func Bucket(bucket string, opts ...Option) objectstore.StoreFactory {
	return func(ctx context.Context, _ *fw.Boot, name string) (objectstore.Store, error) {
		s := settings{maxBytes: objectstore.DefaultMaxBytes, verify: true}
		for _, o := range opts {
			o(&s)
		}
		if !bucketName.MatchString(bucket) {
			return nil, fmt.Errorf("not a valid S3 bucket name: '%s'", bucket)
		}
		prefix := ""
		if s.prefix != "" {
			if err := objectstore.CheckKey(s.prefix); err != nil {
				return nil, fmt.Errorf("prefix: %w", err)
			}
			prefix = s.prefix + "/"
		}
		if s.maxBytes < 1 {
			return nil, fmt.Errorf("MaxBytes must be at least 1, was %d", s.maxBytes)
		}
		var loadOpts []func(*config.LoadOptions) error
		if s.region != "" {
			loadOpts = append(loadOpts, config.WithRegion(s.region))
		}
		cfg, err := config.LoadDefaultConfig(ctx, loadOpts...)
		if err != nil {
			return nil, fmt.Errorf("AWS configuration: %w", err)
		}
		client := s3.NewFromConfig(cfg, func(o *s3.Options) {
			if s.endpoint != "" {
				// S3-compatible services: path-style addressing, and checksums only where required.
				o.BaseEndpoint = aws.String(s.endpoint)
				o.UsePathStyle = true
				o.RequestChecksumCalculation = aws.RequestChecksumCalculationWhenRequired
				o.ResponseChecksumValidation = aws.ResponseChecksumValidationWhenRequired
			}
		})
		if s.verify {
			if _, err := client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(bucket)}); err != nil {
				return nil, fmt.Errorf("S3 bucket '%s' cannot be reached (it must exist, and the role needs s3:ListBucket; or turn off VerifyAtStartup): %w", bucket, err)
			}
		}
		return &store{name: name, client: client, bucket: bucket, prefix: prefix, maxBytes: s.maxBytes}, nil
	}
}

// store keeps values as S3 objects. The SDK client holds nothing that needs closing.
type store struct {
	name     string
	client   *s3.Client
	bucket   string
	prefix   string
	maxBytes int
}

func (s *store) Name() string { return s.name }

func (s *store) fail(op, key string, err error) error {
	return fmt.Errorf("%w: object store '%s': cannot %s '%s': %w", objectstore.ErrStore, s.name, op, key, err)
}

func (s *store) Put(ctx context.Context, key string, content []byte) error {
	if err := objectstore.CheckKey(key); err != nil {
		return err
	}
	if err := objectstore.CheckSize(len(content), s.maxBytes); err != nil {
		return err
	}
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket: aws.String(s.bucket), Key: aws.String(s.prefix + key), Body: bytes.NewReader(content),
	})
	if err != nil {
		return s.fail("write", key, err)
	}
	return nil
}

func (s *store) Get(ctx context.Context, key string) ([]byte, bool, error) {
	if err := objectstore.CheckKey(key); err != nil {
		return nil, false, err
	}
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(s.prefix + key)})
	if err != nil {
		if isNoSuchKey(err) {
			return nil, false, nil
		}
		return nil, false, s.fail("read", key, err)
	}
	defer out.Body.Close()
	if n := aws.ToInt64(out.ContentLength); n > int64(s.maxBytes) {
		return nil, false, fmt.Errorf("%w: object store '%s': '%s' is %d bytes, over the limit of %d", objectstore.ErrStore, s.name, key, n, s.maxBytes)
	}
	data, err := io.ReadAll(io.LimitReader(out.Body, int64(s.maxBytes)+1))
	if err != nil {
		return nil, false, s.fail("read", key, err)
	}
	if len(data) > s.maxBytes {
		return nil, false, fmt.Errorf("%w: object store '%s': '%s' is over the limit of %d", objectstore.ErrStore, s.name, key, s.maxBytes)
	}
	return data, true, nil
}

func (s *store) Delete(ctx context.Context, key string) error {
	if err := objectstore.CheckKey(key); err != nil {
		return err
	}
	if _, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(s.prefix + key)}); err != nil {
		return s.fail("delete", key, err)
	}
	return nil
}

// isNoSuchKey recognises S3's "no such object" answer (by type, or by code for services that
// answer with another error shape).
func isNoSuchKey(err error) bool {
	if _, ok := errors.AsType[*types.NoSuchKey](err); ok {
		return true
	}
	var api smithy.APIError
	return errors.As(err, &api) && api.ErrorCode() == "NoSuchKey"
}
