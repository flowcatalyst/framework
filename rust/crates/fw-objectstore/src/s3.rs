//! The `s3` driver (feature `s3`): values as objects in one bucket, on the AWS SDK for Rust.
//! Credentials and region come from the SDK's default provider chains (environment, shared
//! profile, container or instance role), so nothing secret is configured here.

use std::error::Error;

use aws_config::BehaviorVersion;
use aws_sdk_s3::Client;
use aws_sdk_s3::config::{Builder, RequestChecksumCalculation, ResponseChecksumValidation};
use aws_sdk_s3::error::{DisplayErrorContext, SdkError};
use aws_sdk_s3::primitives::ByteStream;
use fw_core::BoxFuture;

use crate::key::check_key;
use crate::store::{ObjectStore, ObjectStoreError, check_put};

/// Values as S3 objects.
pub struct S3ObjectStore {
    name: String,
    client: Client,
    bucket: String,
    /// Empty, or the prefix and a `/`.
    prefix: String,
    max_bytes: usize,
}

impl S3ObjectStore {
    /// Connects a client and, when `verify_at_startup`, checks the bucket with `HeadBucket`.
    ///
    /// With an `endpoint` (an S3-compatible service such as LocalStack or MinIO) addressing is
    /// path-style, and request and response checksums are used only where required: not every
    /// compatible service accepts the trailer.
    ///
    /// # Errors
    ///
    /// The message of a bucket that cannot be reached.
    pub(crate) async fn open(name: &str, config: &crate::S3Store) -> Result<Self, String> {
        let mut loader = aws_config::defaults(BehaviorVersion::latest());
        if let Some(region) = &config.region {
            loader = loader.region(aws_config::Region::new(region.clone()));
        }
        let mut builder = Builder::from(&loader.load().await);
        if let Some(endpoint) = &config.endpoint {
            builder = builder
                .endpoint_url(endpoint)
                .force_path_style(true)
                .request_checksum_calculation(RequestChecksumCalculation::WhenRequired)
                .response_checksum_validation(ResponseChecksumValidation::WhenRequired);
        }
        let client = Client::from_conf(builder.build());
        if config.verify_at_startup {
            client
                .head_bucket()
                .bucket(&config.bucket)
                .send()
                .await
                .map_err(|e| {
                    format!(
                        "S3 bucket '{}' cannot be reached (it must exist, and the role needs s3:ListBucket; or turn off verify_at_startup): {}",
                        config.bucket,
                        DisplayErrorContext(&e)
                    )
                })?;
        }
        Ok(Self {
            name: name.to_owned(),
            client,
            bucket: config.bucket.clone(),
            prefix: config
                .prefix
                .as_deref()
                .map_or_else(String::new, |p| format!("{p}/")),
            max_bytes: config.max_bytes,
        })
    }

    fn failed(&self, what: &str, key: &str, e: &impl Error) -> ObjectStoreError {
        ObjectStoreError::failed(format!(
            "object store '{}': cannot {what} '{key}': {}",
            self.name,
            DisplayErrorContext(e)
        ))
    }

    async fn read(&self, key: &str) -> Result<Option<Vec<u8>>, ObjectStoreError> {
        check_key(key)?;
        let response = match self
            .client
            .get_object()
            .bucket(&self.bucket)
            .key(format!("{}{key}", self.prefix))
            .send()
            .await
        {
            Ok(r) => r,
            Err(SdkError::ServiceError(e)) if e.err().is_no_such_key() => return Ok(None),
            Err(e) => return Err(self.failed("read", key, &e)),
        };
        if let Some(length) = response.content_length()
            && u64::try_from(length).is_ok_and(|l| l > self.max_bytes as u64)
        {
            return Err(ObjectStoreError::failed(format!(
                "object store '{}': '{key}' is {length} bytes, over the limit of {}",
                self.name, self.max_bytes
            )));
        }
        let body = response
            .body
            .collect()
            .await
            .map_err(|e| self.failed("read", key, &e))?;
        Ok(Some(body.to_vec()))
    }
}

impl ObjectStore for S3ObjectStore {
    fn name(&self) -> &str {
        &self.name
    }

    fn put<'a>(
        &'a self,
        key: &'a str,
        content: &'a [u8],
    ) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(async move {
            check_put(key, content, self.max_bytes)?;
            self.client
                .put_object()
                .bucket(&self.bucket)
                .key(format!("{}{key}", self.prefix))
                .body(ByteStream::from(content.to_vec()))
                .send()
                .await
                .map_err(|e| self.failed("write", key, &e))?;
            Ok(())
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<Option<Vec<u8>>, ObjectStoreError>> {
        Box::pin(self.read(key))
    }

    fn delete<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(async move {
            check_key(key)?;
            self.client
                .delete_object()
                .bucket(&self.bucket)
                .key(format!("{}{key}", self.prefix))
                .send()
                .await
                .map_err(|e| self.failed("delete", key, &e))?;
            Ok(())
        })
    }
}
