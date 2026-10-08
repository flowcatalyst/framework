package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidatorTest {

  record Address(String street, String city) {
    static final Validator<Address> V =
        Validator.of(Address.class)
            .field("street", Address::street, Rules.notBlank(), Rules.maxLength(10))
            .field("city", Address::city, Rules.notNull());
  }

  record Item(String sku, int qty) {
    static final Validator<Item> V =
        Validator.of(Item.class)
            .field("sku", Item::sku, Rules.notBlank())
            .field("qty", Item::qty, Rules.range(1, 5));
  }

  record Order(
      String customer,
      int quantity,
      List<String> tags,
      Address address,
      List<Item> items,
      String status,
      LocalDate due) {
    static final Validator<Order> V =
        Validator.of(Order.class)
            .field("customer", Order::customer, Rules.notBlank(), Rules.maxLength(5))
            .field("quantity", Order::quantity, Rules.range(1, 1000))
            .field("tags", Order::tags, Rules.size(0, 3), Rules.each(Rules.minLength(2)))
            .nested("address", Order::address, Address.V)
            .field("items", Order::items, Rules.each(Rules.valid(Item.V)))
            .field("status", Order::status, Rules.oneOf("NEW", "PAID"))
            .field("due", Order::due, Rules.custom("IN_FUTURE", "must be in the future", d -> d.isAfter(LocalDate.of(2000, 1, 1))));
  }

  private static TestClient client() {
    Routes routes = new Routes();
    routes.post("/orders", r -> Response.ok(Map.of("customer", r.json(Order.V).customer())));
    return TestClient.of(routes);
  }

  private static final String VALID =
      """
      {"customer":"acme","quantity":3,"tags":["ab","cd"],"address":{"street":"Main","city":"X"},
       "items":[{"sku":"a","qty":1}],"status":"NEW","due":"2030-05-01"}""";

  @Test
  void aValidBodyPasses() {
    TestResponse r = client().post("/orders").jsonBody(VALID).send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("customer").asString()).isEqualTo("acme");
  }

  @Test
  void allErrorsAreCollectedNotTheFirst() {
    String body =
        """
        {"customer":"","quantity":0,"tags":["a","bb","cc","dd"],"address":{"street":"","city":null},
         "items":[{"sku":"ok","qty":9},{"sku":" ","qty":1}],"status":"GONE","due":"1999-01-01"}""";
    TestResponse r = client().post("/orders").jsonBody(body).send();
    assertThat(r.status()).isEqualTo(400);
    tools.jackson.databind.JsonNode d = r.tree().get("details");
    assertThat(r.tree().get("error").asString()).isEqualTo("VALIDATION");
    assertThat(r.tree().get("message").asString()).isEqualTo("request is invalid");
    List<String> keys = d.propertyNames().stream().toList();
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "customer", "quantity", "tags", "tags[0]", "address.street", "address.city", "items[0].qty", "items[1].sku", "status", "due");
    assertThat(d.get("customer").asString()).startsWith("NOT_BLANK: ");
    assertThat(d.get("quantity").asString()).startsWith("OUT_OF_RANGE: ");
    assertThat(d.get("tags").asString()).startsWith("SIZE: ");
    assertThat(d.get("tags[0]").asString()).startsWith("TOO_SHORT: ");
    assertThat(d.get("address.city").asString()).startsWith("NOT_NULL: ");
    assertThat(d.get("status").asString()).startsWith("NOT_ONE_OF: ");
    assertThat(d.get("due").asString()).startsWith("IN_FUTURE: ");
  }

  @Test
  void theFirstErrorPerFieldIsInTheDetailsButAllAreInTheException() {
    Order bad = new Order("", 1, List.of(), new Address("ok", "c"), List.of(), "NEW", LocalDate.of(2030, 1, 1));
    Validator<String> v = Validator.of(String.class).field("x", s -> s, Rules.minLength(3), Rules.pattern("[a-z]+"));
    assertThatThrownBy(() -> v.check("1"))
        .isInstanceOfSatisfying(
            ValidationException.class,
            e -> {
              assertThat(e.errors()).extracting(FieldError::code).containsExactly("TOO_SHORT", "PATTERN");
              assertThat(e.details()).hasSize(1);
              assertThat(e.details().get("x")).startsWith("TOO_SHORT: ");
            });
    // a blank customer fails notBlank only (maxLength passes): one error, on one field
    assertThat(Order.V.validate(bad)).extracting(FieldError::field).containsExactly("customer");
  }

  @Test
  void rulesOtherThanNotNullPassOnNull() {
    Validator<Address> v =
        Validator.of(Address.class)
            .field("street", Address::street, Rules.minLength(3), Rules.maxLength(3), Rules.pattern("a"), Rules.oneOf("a"))
            .field("city", Address::city, Rules.custom("X", "x", s -> false));
    assertThat(v.validate(new Address(null, null))).isEmpty();
    assertThat(Validator.of(Address.class).field("city", Address::city, Rules.notNull()).validate(new Address("s", null)))
        .hasSize(1);
    assertThat(Validator.of(Address.class).field("city", Address::city, Rules.notBlank()).validate(new Address("s", null)))
        .hasSize(1);
  }

  @Test
  void nestedNullPasses() {
    Validator<Order> v = Validator.of(Order.class).nested("address", Order::address, Address.V);
    assertThat(v.validate(new Order("a", 1, null, null, null, null, null))).isEmpty();
  }

  @Test
  void rangeHandlesNumbersOfAnyType() {
    Rule<Number> r = Rules.range(1, 10);
    Violations v = new Violations();
    r.check(5L, v);
    r.check(10.0, v);
    r.check(new java.math.BigDecimal("1.0"), v);
    assertThat(v.isEmpty()).isTrue();
    r.check(10.5, v);
    r.check(Double.NaN, v);
    r.check(0, v);
    assertThat(v.entries()).hasSize(3);
  }

  @Test
  void messagesNeverContainTheValue() {
    Violations v = new Violations();
    Rules.maxLength(2).check("secret-value", v);
    Rules.pattern("[0-9]+").check("secret-value", v);
    Rules.oneOf("a").check("secret-value", v);
    Rules.range(1, 2).check(999, v);
    assertThat(v.entries()).extracting(Violations.Entry::message).noneMatch(m -> m.contains("secret") || m.contains("999"));
  }

  // ---- JSON decoding ------------------------------------------------------------------------

  private static TestResponse post(String json) {
    return client().post("/orders").jsonBody(json).send();
  }

  @Test
  void anUnknownPropertyIsRejected() {
    TestResponse r = post(VALID.replace("{\"customer\"", "{\"extra\":1,\"customer\""));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("extra").asString()).isEqualTo("INVALID_JSON: unknown property");
  }

  @Test
  void anUnknownNestedPropertyIsRejectedWithItsPath() {
    TestResponse r = post(VALID.replace("\"street\":\"Main\"", "\"street\":\"Main\",\"zip\":1"));
    assertThat(r.tree().get("details").get("address.zip").asString()).startsWith("INVALID_JSON: ");
  }

  @Test
  void aWrongTypeNamesTheFieldButNotTheValue() {
    TestResponse r = post(VALID.replace("\"quantity\":3", "\"quantity\":\"SECRET-TEXT\""));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("quantity").asString()).isEqualTo("INVALID_JSON: wrong type or format");
    assertThat(r.body()).doesNotContain("SECRET-TEXT");
  }

  @Test
  void aWrongTypeInAnArrayElementHasAnIndexedPath() {
    TestResponse r = post(VALID.replace("\"qty\":1", "\"qty\":\"SECRET-TEXT\""));
    assertThat(r.tree().get("details").has("items[0].qty")).isTrue();
    assertThat(r.body()).doesNotContain("SECRET-TEXT");
  }

  @Test
  void aBadDateDoesNotEchoTheValue() {
    TestResponse r = post(VALID.replace("2030-05-01", "SECRET-DATE"));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("due").asString()).startsWith("INVALID_JSON: ");
    assertThat(r.body()).doesNotContain("SECRET-DATE");
  }

  @Test
  void nullForAPrimitiveFails() {
    TestResponse r = post(VALID.replace("\"quantity\":3", "\"quantity\":null"));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("details").get("quantity").asString()).startsWith("INVALID_JSON: ");
  }

  @Test
  void syntaxErrorsUseTheBodyField() {
    for (String bad : List.of("{", "not json SECRET-TEXT", "", "[1,2]", "{\"customer\":\"a\"} trailing", "null")) {
      TestResponse r = post(bad);
      assertThat(r.status()).as(bad).isEqualTo(400);
      assertThat(r.tree().get("error").asString()).isEqualTo("VALIDATION");
      assertThat(r.body()).doesNotContain("SECRET-TEXT");
    }
    assertThat(post("{").tree().get("details").get("body").asString()).startsWith("INVALID_JSON: ");
  }

  @Test
  void duplicateKeysAreRejected() {
    assertThat(post("{\"customer\":\"a\",\"customer\":\"b\"}").status()).isEqualTo(400);
  }

  @Test
  void nonJsonContentTypeIs415BeforeAnyDecoding() {
    TestResponse r = client().post("/orders").header("Content-Type", "application/xml").body("<x/>").send();
    assertThat(r.status()).isEqualTo(415);
  }

  // ---- parameters ---------------------------------------------------------------------------

  enum Color { RED, GREEN }

  @Test
  void queryAndPathHelpers() {
    Routes routes = new Routes();
    routes.get(
        "/p/{id}/{big}",
        r ->
            Response.ok(
                Map.of(
                    "id", r.pathInt("id"),
                    "big", r.pathLong("big"),
                    "page", r.queryInt("page", 1),
                    "n", r.queryLong("n", 9L),
                    "flag", r.queryBool("flag", false),
                    "color", r.queryEnum("color", Color.class, Color.RED).name(),
                    "req", r.queryRequired("req"),
                    "multi", r.queries("m"),
                    "first", String.valueOf(r.query("m")),
                    "absent", String.valueOf(r.query("zzz")))));
    TestClient c = TestClient.of(routes);
    tools.jackson.databind.JsonNode t =
        c.get("/p/5/9000000000?page=3&n=7&flag=true&color=GREEN&req=a%20b+c&m=1&m=2").send().tree();
    assertThat(t.get("id").asInt()).isEqualTo(5);
    assertThat(t.get("big").asLong()).isEqualTo(9_000_000_000L);
    assertThat(t.get("page").asInt()).isEqualTo(3);
    assertThat(t.get("n").asLong()).isEqualTo(7);
    assertThat(t.get("flag").asBoolean()).isTrue();
    assertThat(t.get("color").asString()).isEqualTo("GREEN");
    assertThat(t.get("req").asString()).isEqualTo("a b c");
    assertThat(t.get("multi").size()).isEqualTo(2);
    assertThat(t.get("first").asString()).isEqualTo("1");
    assertThat(t.get("absent").asString()).isEqualTo("null");

    tools.jackson.databind.JsonNode defaults = c.get("/p/5/6?req=x").send().tree();
    assertThat(defaults.get("page").asInt()).isEqualTo(1);
    assertThat(defaults.get("n").asLong()).isEqualTo(9);
    assertThat(defaults.get("flag").asBoolean()).isFalse();
    assertThat(defaults.get("color").asString()).isEqualTo("RED");
  }

  @Test
  void badParametersNameTheParameterAndNotTheValue() {
    Routes routes = new Routes();
    routes.get(
        "/p/{id}",
        r -> Response.ok(Map.of("v", r.pathInt("id") + r.queryInt("page", 1) + r.queryLong("n", 1) + (r.queryBool("f", false) ? 1 : 0) + r.queryEnum("c", Color.class, Color.RED).ordinal() + r.queryRequired("req").length())));
    TestClient c = TestClient.of(routes);
    for (String[] bad :
        new String[][] {
          {"/p/SECRET?req=a", "id"},
          {"/p/1?req=a&page=SECRET", "page"},
          {"/p/1?req=a&n=SECRET", "n"},
          {"/p/1?req=a&f=yes", "f"},
          {"/p/1?req=a&c=SECRET", "c"},
          {"/p/1", "req"},
          {"/p/99999999999", "id"},
        }) {
      TestResponse r = c.get(bad[0]).send();
      assertThat(r.status()).as(bad[0]).isEqualTo(400);
      assertThat(r.tree().get("details").has(bad[1])).as(bad[0]).isTrue();
      assertThat(r.body()).doesNotContain("SECRET");
    }
  }

  @Test
  void aMalformedQueryEscapeIs400() {
    Routes routes = new Routes();
    routes.get("/q", r -> Response.text(200, String.valueOf(r.query("a"))));
    assertThat(TestClient.of(routes).get("/q?a=%zz").send().status()).isEqualTo(400);
  }
}
