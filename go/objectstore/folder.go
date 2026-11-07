package objectstore

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"syscall"
)

// folderStore keeps a value as a file under one folder, a key's '/' being folders. A put writes a
// hidden temporary file beside the target and renames it into place, so a reader sees the old
// value or the new one. A content type other than the default is kept beside the value, in the
// hidden file ".<name>.content-type", written the same way after the value. Keys cannot start a
// segment with a dot (see [CheckKey]), so temporary and type files never collide with a key. The folder is opened as an [os.Root], which refuses any path
// (a "..", an absolute path, a symbolic link) that leads outside it.
type folderStore struct {
	name     string
	root     *os.Root
	maxBytes int
}

func (f *folderStore) Name() string { return f.name }

func (f *folderStore) fail(op, key string, err error) error {
	return fmt.Errorf("%w: object store '%s': cannot %s '%s': %w", ErrStore, f.name, op, key, err)
}

// typeFile is the hidden file beside a value that holds its content type.
func typeFile(key string) string {
	dir, name := path.Split(key)
	return dir + "." + name + ".content-type"
}

func (f *folderStore) Put(ctx context.Context, key string, content []byte) error {
	return f.PutObject(ctx, key, Object{Content: content})
}

// PutObject writes the value, then its type file (or removes it for the default type). A reader
// racing a put that changes the type may see the new bytes with the previous type for that moment:
// the folder driver is for local development and tests.
func (f *folderStore) PutObject(_ context.Context, key string, o Object) error {
	if err := CheckKey(key); err != nil {
		return err
	}
	if err := CheckContentType(o.ContentType); err != nil {
		return err
	}
	if err := CheckSize(len(o.Content), f.maxBytes); err != nil {
		return err
	}
	dir := path.Dir(key)
	if dir != "." {
		if err := f.root.MkdirAll(dir, 0o750); err != nil {
			return f.fail("write", key, err)
		}
	}
	if err := f.replace(key, o.Content); err != nil {
		return f.fail("write", key, err)
	}
	ct := ContentTypeOrDefault(o.ContentType)
	if ct == DefaultContentType {
		if err := f.root.Remove(typeFile(key)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return f.fail("write the content type of", key, err)
		}
		return nil
	}
	if err := f.replace(typeFile(key), []byte(ct)); err != nil {
		return f.fail("write the content type of", key, err)
	}
	return nil
}

// replace writes content to a hidden temporary file beside name and renames it into place.
func (f *folderStore) replace(name string, content []byte) (err error) {
	dir := path.Dir(name)
	var rnd [16]byte
	if _, err := crand.Read(rnd[:]); err != nil {
		return err
	}
	tmp := path.Join(dir, ".tmp-"+hex.EncodeToString(rnd[:]))
	file, err := f.root.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if err != nil {
		return err
	}
	defer func() {
		if err != nil {
			_ = f.root.Remove(tmp) // hidden and harmless if this fails too
		}
	}()
	_, err = file.Write(content)
	if cerr := file.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = f.root.Rename(tmp, name)
	}
	return err
}

func (f *folderStore) Get(ctx context.Context, key string) ([]byte, bool, error) {
	o, ok, err := f.GetObject(ctx, key)
	return o.Content, ok, err
}

func (f *folderStore) GetObject(_ context.Context, key string) (Object, bool, error) {
	if err := CheckKey(key); err != nil {
		return Object{}, false, err
	}
	data, ok, err := f.read(key)
	if err != nil || !ok {
		return Object{}, ok, err
	}
	ct, err := f.contentType(key)
	if err != nil {
		return Object{}, false, err
	}
	return Object{Content: data, ContentType: ct}, true, nil
}

// contentType reads a value's type file: the default when there is none.
func (f *folderStore) contentType(key string) (string, error) {
	file, err := f.root.Open(typeFile(key))
	if errors.Is(err, fs.ErrNotExist) {
		return DefaultContentType, nil
	}
	if err != nil {
		return "", f.fail("read the content type of", key, err)
	}
	defer func() { _ = file.Close() }()
	b, err := io.ReadAll(io.LimitReader(file, MaxContentTypeLength+1))
	if err != nil {
		return "", f.fail("read the content type of", key, err)
	}
	ct := string(b)
	if CheckContentType(ct) != nil || ct == "" {
		return "", fmt.Errorf("%w: object store '%s': the content type file of '%s' does not hold a content type", ErrStore, f.name, key)
	}
	return ct, nil
}

// read reads a value's bytes; false when there is none.
func (f *folderStore) read(key string) ([]byte, bool, error) {
	present, size, err := f.stat(key)
	if err != nil {
		return nil, false, f.fail("read", key, err)
	}
	if !present {
		return nil, false, nil
	}
	if size > int64(f.maxBytes) {
		return nil, false, fmt.Errorf("%w: object store '%s': '%s' is %d bytes, over the limit of %d", ErrStore, f.name, key, size, f.maxBytes)
	}
	file, err := f.root.Open(key)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, f.fail("read", key, err)
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.LimitReader(file, int64(f.maxBytes)+1))
	if err != nil {
		return nil, false, f.fail("read", key, err)
	}
	if len(data) > f.maxBytes {
		return nil, false, fmt.Errorf("%w: object store '%s': '%s' is over the limit of %d", ErrStore, f.name, key, f.maxBytes)
	}
	return data, true, nil
}

func (f *folderStore) Delete(_ context.Context, key string) error {
	if err := CheckKey(key); err != nil {
		return err
	}
	present, _, err := f.stat(key)
	if err != nil {
		return f.fail("delete", key, err)
	}
	if !present {
		return nil
	}
	if err := f.root.Remove(key); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return f.fail("delete", key, err)
	}
	if err := f.root.Remove(typeFile(key)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return f.fail("delete the content type of", key, err)
	}
	return nil
}

// stat says whether key is a regular file (a link, a folder or nothing is "absent") and its size.
// A path that leaves the folder through a link is an error, not absence.
func (f *folderStore) stat(key string) (present bool, size int64, err error) {
	info, err := f.root.Lstat(key)
	if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ENOTDIR) {
		return false, 0, nil // ENOTDIR: a part of the key is a value, not a folder
	}
	if err != nil {
		return false, 0, err
	}
	if !info.Mode().IsRegular() {
		return false, 0, nil
	}
	return true, info.Size(), nil
}

// Close releases the open folder.
func (f *folderStore) Close() error { return f.root.Close() }

// openFolder creates dir if missing and opens it, resolved to its real path.
func openFolder(name, dir string, maxBytes int) (Store, error) {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return nil, fmt.Errorf("directory %s cannot be created: %w", dir, err)
	}
	resolved, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return nil, fmt.Errorf("directory %s cannot be resolved: %w", dir, err)
	}
	root, err := os.OpenRoot(resolved)
	if err != nil {
		return nil, fmt.Errorf("directory %s cannot be opened: %w", dir, err)
	}
	return &folderStore{name: name, root: root, maxBytes: maxBytes}, nil
}
