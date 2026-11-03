package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract section 15.7: the three revocation events, built from the data as the platform writes it. */
class FieldRevocationTest {

  static final Instant NOW = Receiving.NOW;

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;
  final List<FieldRevocation> seen = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    platform = FakePlatform.start();
    app = Receiving.start(dir, Receiving.env(platform), () -> NOW, Receiving.handlers(r -> r.onFieldRevocation((ctx, event) -> seen.add(event))));
    client = Receiving.client(app);
  }

  @AfterEach
  void tearDown() {
    app.close();
    platform.close();
  }

  private TestResponse deliver(String type, String data) {
    String body = "{\"id\":\"dj_1\",\"type\":\"" + type + "\",\"attemptNumber\":1,\"clientId\":\"clt_1\",\"clientCode\":\"acme\",\"data\":" + data + "}";
    return ReceiverTesting.dispatch(client, Receiving.SECRET, NOW, "dj_1", type, "clt_1:acme", body);
  }

  @Test
  void aSuspendedWorkerIsDelivered() {
    TestResponse r = deliver("platform:iam:field-worker:suspended", "{\"workerId\":\"fwu_1\",\"clientId\":\"clt_1\",\"workerCode\":\"W001\"}");
    assertThat(r.status()).isEqualTo(200);
    assertThat(seen).containsExactly(new FieldRevocation.WorkerSuspended("fwu_1", "clt_1"));
  }

  @Test
  void aRevokedDeviceIsDelivered() {
    TestResponse r = deliver("platform:iam:field-device:revoked", "{\"deviceId\":\"fwd_1\",\"clientId\":\"clt_1\",\"mode\":\"SHARED\"}");
    assertThat(r.status()).isEqualTo(200);
    assertThat(seen).containsExactly(new FieldRevocation.DeviceRevoked("fwd_1", "clt_1"));
  }

  @Test
  void revokedSessionsAreDeliveredWhicheverIdsTheEventNames() {
    // an administrator suspended a worker: the worker, no family
    assertThat(deliver("platform:iam:field-session:revoked", "{\"clientId\":\"clt_1\",\"reason\":\"WORKER_SUSPENDED\",\"count\":2,\"workerId\":\"fwu_1\"}").status()).isEqualTo(200);
    // a device ended its sessions
    assertThat(deliver("platform:iam:field-session:revoked", "{\"clientId\":\"clt_1\",\"reason\":\"DEVICE_REVOKED\",\"count\":1,\"deviceId\":\"fwd_1\"}").status()).isEqualTo(200);
    // refresh-token reuse: the family
    assertThat(
            deliver(
                    "platform:iam:field-session:revoked",
                    "{\"clientId\":\"clt_1\",\"reason\":\"REUSE\",\"count\":3,\"familyId\":\"fws_9\",\"workerId\":\"fwu_1\",\"deviceId\":\"fwd_1\"}")
                .status())
        .isEqualTo(200);
    assertThat(seen)
        .containsExactly(
            new FieldRevocation.SessionRevoked(null, "fwu_1", null, "clt_1", "WORKER_SUSPENDED"),
            new FieldRevocation.SessionRevoked(null, null, "fwd_1", "clt_1", "DEVICE_REVOKED"),
            new FieldRevocation.SessionRevoked("fws_9", "fwu_1", "fwd_1", "clt_1", "REUSE"));
  }

  @Test
  void dataThatLacksWhatTheTypeNeedsIsRejectedNotRetried() {
    TestResponse r = deliver("platform:iam:field-worker:suspended", "{\"clientId\":\"clt_1\"}");
    assertThat(r.status()).isEqualTo(422);
    assertThat(seen).isEmpty();
    assertThat(deliver("platform:iam:field-device:revoked", "\"nope\"").status()).isEqualTo(422);
    // a session event that names neither a family, a worker nor a device is undeliverable
    assertThat(deliver("platform:iam:field-session:revoked", "{\"clientId\":\"clt_1\",\"reason\":\"ADMIN\",\"count\":0}").status()).isEqualTo(422);
    // sessionId is not a field the platform writes: the family is familyId
    assertThat(deliver("platform:iam:field-session:revoked", "{\"clientId\":\"clt_1\",\"sessionId\":\"fws_1\"}").status()).isEqualTo(422);
  }

  @Test
  void aHandlerThatThrowsMakesThePlatformRetry() {
    app.close();
    app = Receiving.start(dir, Receiving.env(platform), () -> NOW, Receiving.handlers(r -> r.onFieldRevocation((ctx, event) -> {
      throw new IllegalStateException("db down");
    })));
    client = Receiving.client(app);
    assertThat(deliver("platform:iam:field-worker:suspended", "{\"workerId\":\"fwu_1\",\"clientId\":\"clt_1\"}").status()).isEqualTo(503);
  }

  @Test
  void theSubscriptionNamesTheThreeEventTypes() {
    Definition.Subscription s = FieldRevocation.subscription("field-revocations", "/fc/dispatch");
    assertThat(s.eventTypes())
        .containsExactly("platform:iam:field-worker:suspended", "platform:iam:field-device:revoked", "platform:iam:field-session:revoked");
    assertThat(s.target()).isEqualTo("/fc/dispatch");
  }

  @Test
  void aSecondRegistrationForTheseTypesIsRefused() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.get(Receivers.class).onFieldRevocation((ctx, event) -> {}))
        .isInstanceOf(IllegalStateException.class);
  }
}
