//! The s3 driver against an in-process S3: the real AWS SDK over real HTTP, path-style GET, PUT,
//! DELETE and HEAD bucket, plain S3 semantics.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::collections::BTreeMap;
use std::env;
use std::io;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, Once};

use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot, StartupError};
use fw_objectstore::testing::ObjectStoreHarness;
use fw_objectstore::{ObjectStore, ObjectStoreError, ObjectStores, S3Store};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

const BUCKET: &str = "test-bucket";

/// The SDK's default chain finds credentials in the environment.
fn test_credentials() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[expect(
            unsafe_code,
            reason = "set once, before any SDK client of this test process exists, so no thread is reading the environment"
        )]
        // SAFETY: every test calls this before building a client; nothing else touches the environment.
        unsafe {
            env::set_var("AWS_ACCESS_KEY_ID", "test");
            env::set_var("AWS_SECRET_ACCESS_KEY", "test");
        }
    });
}

#[derive(Default)]
struct State {
    objects: BTreeMap<String, Vec<u8>>,
}

struct FakeS3 {
    endpoint: String,
    state: Arc<Mutex<State>>,
    /// Answer this many more requests with 403 (not retried by the SDK).
    fail_next: Arc<AtomicUsize>,
    server: JoinHandle<()>,
}

impl Drop for FakeS3 {
    fn drop(&mut self) {
        self.server.abort();
    }
}

impl FakeS3 {
    async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let state = Arc::new(Mutex::new(State::default()));
        let fail_next = Arc::new(AtomicUsize::new(0));
        let (s, f) = (Arc::clone(&state), Arc::clone(&fail_next));
        let server = tokio::spawn(async move {
            loop {
                let Ok((socket, _)) = listener.accept().await else {
                    return;
                };
                let (s, f) = (Arc::clone(&s), Arc::clone(&f));
                tokio::spawn(async move {
                    if let Err(e) = serve(socket, &s, &f).await {
                        eprintln!("fake S3: {e}");
                    }
                });
            }
        });
        Self {
            endpoint,
            state,
            fail_next,
            server,
        }
    }

    fn object(&self, key: &str) -> Option<Vec<u8>> {
        self.state.lock().unwrap().objects.get(key).cloned()
    }

    fn put_raw(&self, key: &str, content: Vec<u8>) {
        self.state
            .lock()
            .unwrap()
            .objects
            .insert(key.to_owned(), content);
    }
}

struct Request {
    method: String,
    path: String,
    body: Vec<u8>,
}

/// Reads one request (headers, then a body by `Content-Length` or HTTP chunking; an `aws-chunked`
/// body is decoded too).
async fn read_request(socket: &mut TcpStream) -> io::Result<Request> {
    let mut buf = Vec::new();
    let head_end = loop {
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break i + 4;
        }
        let mut chunk = [0u8; 4096];
        let n = socket.read(&mut chunk).await?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        buf.extend_from_slice(&chunk[..n]);
    };
    let head = String::from_utf8_lossy(&buf[..head_end]).into_owned();
    let mut lines = head.lines();
    let mut first = lines.next().unwrap_or_default().split(' ');
    let method = first.next().unwrap_or_default().to_owned();
    let path = first.next().unwrap_or_default().to_owned();
    let header = |name: &str| {
        lines
            .clone()
            .filter_map(|l| l.split_once(':'))
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.trim().to_owned())
    };
    let length: usize = header("content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let http_chunked = header("transfer-encoding").is_some_and(|v| v.contains("chunked"));
    let aws_chunked = header("content-encoding").is_some_and(|v| v.contains("aws-chunked"));
    let mut body = buf[head_end..].to_vec();
    if http_chunked {
        while !body.ends_with(b"0\r\n\r\n") {
            let mut chunk = [0u8; 4096];
            let n = socket.read(&mut chunk).await?;
            if n == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..n]);
        }
        body = decode_chunks(&body);
    } else {
        while body.len() < length {
            let mut chunk = [0u8; 4096];
            let n = socket.read(&mut chunk).await?;
            if n == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..n]);
        }
    }
    if aws_chunked {
        body = decode_chunks(&body);
    }
    Ok(Request { method, path, body })
}

/// `<hex size>[;extension]\r\n<data>\r\n` frames up to a zero-size frame (HTTP and aws-chunked).
fn decode_chunks(mut raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    while let Some(eol) = raw.windows(2).position(|w| w == b"\r\n") {
        let size_text = String::from_utf8_lossy(&raw[..eol]);
        let size_text = size_text.split(';').next().unwrap_or_default();
        let Ok(size) = usize::from_str_radix(size_text.trim(), 16) else {
            break;
        };
        if size == 0 || raw.len() < eol + 2 + size {
            break;
        }
        out.extend_from_slice(&raw[eol + 2..eol + 2 + size]);
        raw = raw.get(eol + 2 + size + 2..).unwrap_or_default();
    }
    out
}

fn percent_decode(text: &str) -> String {
    let b = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        match (b[i], b.get(i + 1..i + 3)) {
            (b'%', Some(hex)) if u8::from_str_radix(&String::from_utf8_lossy(hex), 16).is_ok() => {
                out.push(u8::from_str_radix(&String::from_utf8_lossy(hex), 16).unwrap());
                i += 3;
            }
            (c, _) => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn xml_error(code: &str) -> Vec<u8> {
    format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>{code}</Code><Message>{code}</Message><RequestId>1</RequestId></Error>").into_bytes()
}

async fn serve(
    mut socket: TcpStream,
    state: &Mutex<State>,
    fail_next: &AtomicUsize,
) -> io::Result<()> {
    let request = read_request(&mut socket).await?;
    let path = request.path.split('?').next().unwrap_or_default();
    let path = percent_decode(path.trim_start_matches('/'));
    let (bucket, key) = path.split_once('/').unwrap_or((path.as_str(), ""));
    let failing = fail_next
        .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| n.checked_sub(1))
        .is_ok();
    let (status, headers, body): (&str, String, Vec<u8>) = if failing {
        ("403 Forbidden", String::new(), xml_error("AccessDenied"))
    } else if bucket != BUCKET {
        ("404 Not Found", String::new(), xml_error("NoSuchBucket"))
    } else if key.is_empty() {
        ("200 OK", String::new(), Vec::new()) // HEAD bucket
    } else {
        let mut state = state.lock().unwrap();
        match request.method.as_str() {
            "PUT" => {
                state.objects.insert(key.to_owned(), request.body);
                ("200 OK", "ETag: \"x\"\r\n".to_owned(), Vec::new())
            }
            "GET" => match state.objects.get(key) {
                Some(v) => ("200 OK", String::new(), v.clone()),
                None => ("404 Not Found", String::new(), xml_error("NoSuchKey")),
            },
            "DELETE" => {
                state.objects.remove(key);
                ("204 No Content", String::new(), Vec::new())
            }
            _ => ("405 Method Not Allowed", String::new(), Vec::new()),
        }
    };
    let head = format!(
        "HTTP/1.1 {status}\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    socket.write_all(head.as_bytes()).await?;
    if request.method != "HEAD" {
        socket.write_all(&body).await?;
    }
    socket.shutdown().await
}

fn store_config(fake: &FakeS3, bucket: &str) -> S3Store {
    S3Store::new(bucket)
        .with_endpoint(&fake.endpoint)
        .with_region("us-east-1")
}

async fn build(builder: fw_objectstore::ObjectStoresBuilder) -> Result<ObjectStores, StartupError> {
    test_credentials();
    let dir = tempfile::tempdir().unwrap();
    let out = Arc::new(Mutex::new(None));
    let slot = Arc::clone(&out);
    AppBuilder::new(AppName::of("s3-test"))
        .config_dir(dir.path().join("none"))
        .environment(Environment::from_pairs::<&str, &str>([]))
        .configure()
        .await?
        .build(async move |boot: &mut Boot| {
            *slot.lock().unwrap() = Some(builder.build(boot).await?);
            Ok(())
        })
        .await?;
    let stores = out.lock().unwrap().take().unwrap();
    Ok(stores)
}

struct S3 {
    fake: FakeS3,
    store: Arc<dyn ObjectStore>,
}

impl ObjectStoreHarness for S3 {
    fn store(&self) -> Arc<dyn ObjectStore> {
        Arc::clone(&self.store)
    }
    fn limit(&self) -> usize {
        4096
    }
}

async fn harness() -> S3 {
    let fake = FakeS3::start().await;
    let config = store_config(&fake, BUCKET)
        .with_prefix("p/q")
        .with_max_bytes(4096);
    let stores = build(ObjectStores::builder().store("s3", config))
        .await
        .unwrap();
    let store = Arc::clone(stores.default_store().unwrap());
    S3 { fake, store }
}

fw_objectstore::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread")]);

#[tokio::test]
async fn values_live_under_the_prefix_in_the_bucket() {
    let h = harness().await;
    h.store().put("hello/world.json", &[1, 2]).await.unwrap();
    assert_eq!(h.fake.object("p/q/hello/world.json"), Some(vec![1, 2]));
}

#[tokio::test]
async fn a_server_error_on_read_write_and_delete_is_a_failure_naming_store_and_key() {
    let h = harness().await;
    let s = h.store();
    h.fake.fail_next.store(100, Ordering::SeqCst);
    for (what, result) in [
        ("cannot read 'x'", s.get("x").await.map(|_| ())),
        ("cannot write 'x'", s.put("x", &[1]).await),
        ("cannot delete 'x'", s.delete("x").await),
    ] {
        let e = result.unwrap_err();
        assert!(matches!(e, ObjectStoreError::Failed { .. }));
        assert!(e.to_string().contains(what), "{e}");
        assert!(e.to_string().contains("object store 's3'"), "{e}");
    }
    h.fake.fail_next.store(0, Ordering::SeqCst);
}

#[tokio::test]
async fn a_value_over_the_limit_in_the_bucket_is_refused_on_read() {
    let h = harness().await;
    h.fake.put_raw("p/q/big", vec![0; 5000]);
    let e = h.store().get("big").await.unwrap_err();
    assert!(matches!(e, ObjectStoreError::Failed { .. }));
    assert!(e.to_string().contains("over the limit"), "{e}");
}

#[tokio::test]
async fn a_missing_bucket_fails_start_up_naming_the_store() {
    let fake = FakeS3::start().await;
    let e = build(ObjectStores::builder().store("lost", store_config(&fake, "no-such-bucket")))
        .await
        .unwrap_err();
    let m = e
        .problems()
        .iter()
        .map(ToString::to_string)
        .collect::<String>();
    assert!(
        m.contains("object store 'lost'") && m.contains("no-such-bucket"),
        "{m}"
    );
}

#[tokio::test]
async fn verify_at_startup_can_be_turned_off() {
    let fake = FakeS3::start().await;
    let config = store_config(&fake, "no-such-bucket").with_verify_at_startup(false);
    let stores = build(ObjectStores::builder().store("quiet", config))
        .await
        .unwrap();
    assert!(stores.get("quiet").is_some());
}

#[tokio::test]
async fn bad_bucket_names_prefixes_and_limits_fail_start_up() {
    let fake = FakeS3::start().await;
    for bad in ["", "UPPER", "a", "has space", "-lead", "trail-"] {
        let e = build(ObjectStores::builder().store("s", store_config(&fake, bad)))
            .await
            .unwrap_err();
        assert!(!e.problems().is_empty(), "{bad}");
    }
    let bad_prefix = store_config(&fake, BUCKET).with_prefix("../x");
    assert!(
        build(ObjectStores::builder().store("s", bad_prefix))
            .await
            .is_err()
    );
    let bad_limit = store_config(&fake, BUCKET).with_max_bytes(0);
    assert!(
        build(ObjectStores::builder().store("s", bad_limit))
            .await
            .is_err()
    );
}
