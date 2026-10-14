package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.PrincipalId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Reading the service principal from the client-credentials token (flowcatalyst contract 4b). */
class ServiceTokensTest {

  static final String SUB = "prn_0HZXEQ5Y8JY5Z";

  static String b64(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  static String jwt(String payloadJson) {
    return b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + b64(payloadJson) + ".c2ln";
  }

  static String refusal(String token) {
    Throwable t = catchThrowable(() -> ServiceTokens.servicePrincipal(token));
    assertThat(t).as(token).isInstanceOf(PlatformException.class);
    // the token is a credential: never in a message (a few characters may occur by chance)
    if (token.length() >= 8) {
      assertThat(t.getMessage()).doesNotContain(token);
    }
    return t.getMessage();
  }

  // ---- refused ------------------------------------------------------------------------------

  @Test
  void aTokenThatIsNotAJwtIsRefused() {
    for (String bad : new String[] {"", "tok-1", "a.b", "a.b.c.d", "..", "a..c", ".b.c"}) {
      assertThat(refusal(bad)).as(bad).contains("not a JWT");
    }
  }

  @Test
  void aPayloadThatIsNotBase64UrlJsonObjectIsRefused() {
    String head = b64("{\"alg\":\"RS256\"}");
    for (String payload :
        new String[] {
          "%%%", // not base64url
          "ab+/", // standard alphabet, not url-safe
          b64("not json"),
          b64("[1,2]"),
          b64("\"text\""),
          b64("{\"sub\":\"" + SUB + "\",\"type\":\"SERVICE\",\"sub\":\"prn_OTHER\"}"), // duplicate key
        }) {
      assertThat(refusal(head + "." + payload + ".c2ln")).as(payload).contains("payload");
    }
  }

  @Test
  void aMissingOrMalformedSubIsRefused() {
    for (String claims :
        new String[] {
          "{\"type\":\"SERVICE\"}",
          "{\"sub\":null,\"type\":\"SERVICE\"}",
          "{\"sub\":42,\"type\":\"SERVICE\"}",
          "{\"sub\":\"\",\"type\":\"SERVICE\"}",
          "{\"sub\":\"has space\",\"type\":\"SERVICE\"}",
          "{\"sub\":\"line\\nbreak\",\"type\":\"SERVICE\"}",
          "{\"sub\":\"" + "x".repeat(129) + "\",\"type\":\"SERVICE\"}",
        }) {
      assertThat(refusal(jwt(claims))).as(claims).contains("sub");
    }
  }

  @Test
  void aTokenForAnythingButAServiceAccountIsRefused() {
    for (String claims :
        new String[] {
          "{\"sub\":\"" + SUB + "\"}",
          "{\"sub\":\"" + SUB + "\",\"type\":\"USER\"}",
          "{\"sub\":\"" + SUB + "\",\"type\":\"service\"}",
          "{\"sub\":\"" + SUB + "\",\"type\":\"SERVICE \"}",
          "{\"sub\":\"" + SUB + "\",\"type\":[\"SERVICE\"]}",
        }) {
      assertThat(refusal(jwt(claims))).as(claims).contains("SERVICE");
    }
  }

  // ---- accepted -----------------------------------------------------------------------------

  @Test
  void theSubOfAServiceTokenIsTheServicePrincipal() throws Exception {
    PrincipalId id =
        ServiceTokens.servicePrincipal(
            jwt("{\"iss\":\"https://fc\",\"sub\":\"" + SUB + "\",\"type\":\"SERVICE\",\"tier\":\"ANCHOR\",\"scope\":\"a b\"}"));
    assertThat(id).isEqualTo(new PrincipalId(SUB));
  }

  @Test
  void theEdgesOfTheSubPatternAreAccepted() throws Exception {
    String longest = "x".repeat(128);
    assertThat(ServiceTokens.servicePrincipal(jwt("{\"sub\":\"" + longest + "\",\"type\":\"SERVICE\"}")))
        .isEqualTo(new PrincipalId(longest));
    assertThat(ServiceTokens.servicePrincipal(jwt("{\"sub\":\"a.b_c:d-9\",\"type\":\"SERVICE\"}")))
        .isEqualTo(new PrincipalId("a.b_c:d-9"));
  }

  @Test
  void aPaddedPayloadIsAccepted() throws Exception {
    String payload =
        Base64.getUrlEncoder()
            .encodeToString(("{\"sub\":\"" + SUB + "\",\"type\":\"SERVICE\"}").getBytes(StandardCharsets.UTF_8));
    assertThat(payload).endsWith("=");
    assertThat(ServiceTokens.servicePrincipal("aGVhZA." + payload + ".c2ln")).isEqualTo(new PrincipalId(SUB));
  }
}
