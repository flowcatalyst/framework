package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * An operators-only operation (admin contract section 3a, rule 2): after the 401 and the
 * permission, the context's tier must be exactly ANCHOR; the request is read only after that.
 */
class OperatorsOnlyOperationTest {

  record Vault(String secret) {}

  record VaultBody(String name) {
    static final Validator<VaultBody> V = Validator.of(VaultBody.class).field("name", VaultBody::name, Rules.notBlank());
  }

  static final Operation<NoBody, Vault> READ =
      Operation.get("/api/vault", Vault.class).id("getVault").permission("items:item:read").operatorsOnly().build();
  /** Operators-only with no permission still needs a context. */
  static final Operation<NoBody, Vault> OPEN =
      Operation.get("/api/vault-open", Vault.class).id("getVaultOpen").operatorsOnly().build();
  static final Operation<VaultBody, Vault> WRITE =
      Operation.post("/api/vault", VaultBody.V, Vault.class).id("putVault").permission("items:item:write").operatorsOnly().build();
  static final Operation<NoBody, Vault> PLAIN =
      Operation.get("/api/plain", Vault.class).id("getPlain").permission("items:item:read").build();

  static final ContextResolver RESOLVER =
      request -> {
        String perms = request.header("X-Perms");
        if (perms == null) {
          throw new HttpException(401, "UNAUTHENTICATED", "sign in");
        }
        Context c =
            Context.of(new ClientId("acme"))
                .withPrincipal(new PrincipalId("usr_1"))
                .withPermissions(perms.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(perms.split(","))));
        String tier = request.header("X-Tier");
        return tier == null || tier.isEmpty() ? c : c.withTier(tier);
      };

  final AtomicInteger calls = new AtomicInteger();

  TestClient client() {
    Routes routes = new Routes();
    routes.declare(List.of(READ, OPEN, WRITE, PLAIN));
    routes.bind(READ, r -> { calls.incrementAndGet(); return Outcome.ok(new Vault("s")); });
    routes.bind(OPEN, r -> { calls.incrementAndGet(); return Outcome.ok(new Vault("s")); });
    routes.bind(WRITE, r -> { calls.incrementAndGet(); return Outcome.ok(new Vault(r.body().name())); });
    routes.bind(PLAIN, r -> { calls.incrementAndGet(); return Outcome.ok(new Vault("p")); });
    return TestClient.of(routes, RESOLVER);
  }

  TestResponse get(String path, String perms, String tier) {
    TestClient.Call r = client().get(path);
    if (perms != null) {
      r = r.header("X-Perms", perms);
    }
    if (tier != null) {
      r = r.header("X-Tier", tier);
    }
    return r.send();
  }

  @Test
  void theOperationSaysItIsOperatorsOnly() {
    assertThat(READ.operatorsOnly()).isTrue();
    assertThat(OPEN.operatorsOnly()).isTrue();
    assertThat(PLAIN.operatorsOnly()).isFalse();
    // implies a context, so the document lists the security schemes and 401 / 403
    assertThat(OPEN.requiresContext()).isTrue();
    assertThat(OPEN.documentedErrorStatuses()).contains(401, 403);
  }

  @Test
  void onlyAnAnchorWithThePermissionGetsThrough() {
    String read = "items:item:read";
    assertThat(get("/api/vault", read, "ANCHOR").status()).isEqualTo(200);
    assertThat(get("/api/vault", "items:item:*", "ANCHOR").status()).as("a wildcard of the permission").isEqualTo(200);
    for (String tier : new String[] {"CLIENT", "PARTNER", "", "anchor", "Anchor", "SOMETHING"}) {
      for (String perms : new String[] {read, "items:item:*", "*:*:*"}) {
        TestResponse r = get("/api/vault", perms, tier);
        assertThat(r.status()).as("tier '%s' with %s", tier, perms).isEqualTo(403);
        assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
        assertThat(r.tree().get("message").asString()).isEqualTo("this is for platform operators only");
      }
    }
    assertThat(calls.get()).as("only the two operator calls ran the handler").isEqualTo(2);
  }

  @Test
  void thePermissionIsCheckedBeforeTheTier() {
    for (String tier : new String[] {"ANCHOR", "CLIENT", "PARTNER", ""}) {
      TestResponse r = get("/api/vault", "items:other:read", tier);
      assertThat(r.status()).as(tier).isEqualTo(403);
      assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
      assertThat(r.tree().get("message").asString()).as(tier).isEqualTo("this needs the permission items:item:read");
    }
  }

  @Test
  void notSignedInIs401BeforeEverything() {
    TestResponse r = get("/api/vault", null, "ANCHOR");
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void anOperatorsOnlyOperationWithoutAPermissionStillChecksTheTier() {
    assertThat(get("/api/vault-open", "", "ANCHOR").status()).isEqualTo(200);
    TestResponse r = get("/api/vault-open", "", "CLIENT");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("message").asString()).isEqualTo("this is for platform operators only");
    assertThat(get("/api/vault-open", "", null).status()).isEqualTo(403);
  }

  @Test
  void theRequestIsReadOnlyAfterTheOperatorCheck() {
    // a malformed body from a client is refused as not-an-operator, not as a bad request
    TestClient client = client();
    TestResponse refused = client.post("/api/vault").header("X-Perms", "items:item:write").header("X-Tier", "CLIENT").jsonBody("{}").send();
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.tree().get("message").asString()).isEqualTo("this is for platform operators only");
    // the same body from an operator is read and refused as invalid
    TestResponse invalid = client.post("/api/vault").header("X-Perms", "items:item:write").header("X-Tier", "ANCHOR").jsonBody("{}").send();
    assertThat(invalid.status()).isEqualTo(400);
    assertThat(calls.get()).isEqualTo(0);
  }

  @Test
  void anOperationThatIsNotOperatorsOnlyIgnoresTheTier() {
    assertThat(get("/api/plain", "items:item:read", "CLIENT").status()).isEqualTo(200);
    assertThat(get("/api/plain", "items:item:read", null).status()).isEqualTo(200);
  }
}
