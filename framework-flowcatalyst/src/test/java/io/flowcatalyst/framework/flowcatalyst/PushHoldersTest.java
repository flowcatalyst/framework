package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.push.Holder;
import org.junit.jupiter.api.Test;

/** A field revocation names the push streams it ends (push contract section 7). */
class PushHoldersTest {

  @Test
  void aSuspendedWorkerEndsThePrincipalsStreams() {
    assertThat(PushHolders.of(new FieldRevocation.WorkerSuspended("fwu_1", "clt_1"))).isEqualTo(new Holder("fwu_1", "", ""));
  }

  @Test
  void aRevokedDeviceEndsTheDevicesStreams() {
    assertThat(PushHolders.of(new FieldRevocation.DeviceRevoked("fwd_1", null))).isEqualTo(new Holder("", "fwd_1", ""));
  }

  @Test
  void aRevokedSessionEndsTheFamilyTheWorkerOrTheDevice() {
    assertThat(PushHolders.of(new FieldRevocation.SessionRevoked("fws_1", null, null, null, "signed-out"))).isEqualTo(new Holder("", "", "fws_1"));
    assertThat(PushHolders.of(new FieldRevocation.SessionRevoked(null, "fwu_1", "fwd_1", "clt_1", null))).isEqualTo(new Holder("fwu_1", "fwd_1", ""));
  }

  @Test
  void theHolderIsWhatThePlatformEventsNameAndTheSessionEventParsesToOne() {
    FieldRevocation parsed =
        FieldRevocation.parse(
                FieldRevocation.SESSION_REVOKED,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"familyId\":\"fws_9\",\"workerId\":\"fwu_9\"}"))
            .orElseThrow();
    assertThat(PushHolders.of(parsed)).isEqualTo(new Holder("fwu_9", "", "fws_9"));
    assertThat(PushHolders.of(parsed).isEmpty()).isFalse();
  }
}
