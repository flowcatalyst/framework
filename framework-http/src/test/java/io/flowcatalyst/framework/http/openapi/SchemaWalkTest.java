package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.openapi.Docs.compact;
import static io.flowcatalyst.framework.http.openapi.Docs.doc;
import static io.flowcatalyst.framework.http.openapi.Docs.getting;
import static io.flowcatalyst.framework.http.openapi.Docs.problems;
import static io.flowcatalyst.framework.http.openapi.Docs.schema;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Schemas from types (contract section 5): the type table, records, sealed interfaces, refusals. */
class SchemaWalkTest {

  // ---- the type table ----------------------------------------------------------------------------

  @Test
  void everyRowOfTheTypeTable() {
    JsonNode doc = Json.mapper().readTree(OpenApiFixture.fixture());
    JsonNode scalars = schema(doc, "Scalars").get("properties");
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("text", "{\"type\":\"string\"}"),
            Map.entry("flag", "{\"type\":\"boolean\"}"),
            Map.entry("maybeFlag", "{\"type\":\"boolean\"}"),
            Map.entry("count", "{\"type\":\"integer\",\"format\":\"int32\"}"),
            Map.entry("maybeCount", "{\"type\":\"integer\",\"format\":\"int32\"}"),
            Map.entry("big", "{\"type\":\"integer\",\"format\":\"int64\"}"),
            Map.entry("maybeBig", "{\"type\":\"integer\",\"format\":\"int64\"}"),
            Map.entry("ratio", "{\"type\":\"number\",\"format\":\"double\"}"),
            Map.entry("maybeRatio", "{\"type\":\"number\",\"format\":\"double\"}"),
            Map.entry("amount", "{\"type\":\"string\",\"format\":\"decimal\"}"),
            Map.entry("at", "{\"type\":\"string\",\"format\":\"date-time\"}"),
            Map.entry("day", "{\"type\":\"string\",\"format\":\"date\"}"),
            Map.entry("timeout", "{\"type\":\"string\",\"format\":\"duration\"}"),
            Map.entry("uuid", "{\"type\":\"string\",\"format\":\"uuid\"}"),
            Map.entry("blob", "{\"type\":\"string\",\"contentEncoding\":\"base64\"}"),
            Map.entry("anything", "{}"),
            Map.entry("colour", "{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]}"),
            Map.entry(
                "price",
                "{\"type\":\"string\",\"pattern\":\"^-?[0-9]+\\\\.[0-9]{2}$\",\"description\":\"an amount with two decimals\"}"));
    for (Map.Entry<String, String> e : expected.entrySet()) {
      assertThat(compact(scalars.get(e.getKey()))).as(e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(scalars.propertyNames()).containsExactlyElementsOf(List.of(
        "text", "flag", "maybeFlag", "count", "maybeCount", "big", "maybeBig", "ratio", "maybeRatio", "amount", "at",
        "day", "timeout", "uuid", "blob", "anything", "colour", "price"));

    JsonNode ids = schema(doc, "Ids").get("properties");
    assertThat(compact(ids.get("client"))).isEqualTo("{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9._-]{1,100}$\"}");
    assertThat(compact(ids.get("application"))).isEqualTo("{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*$\"}");
    assertThat(compact(ids.get("principal"))).isEqualTo("{\"type\":\"string\",\"pattern\":\"\\\\S\",\"minLength\":1}");

    JsonNode collections = schema(doc, "Collections").get("properties");
    assertThat(compact(collections.get("tags"))).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
    assertThat(compact(collections.get("counts"))).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"format\":\"int32\"}}");
    assertThat(compact(collections.get("colours")))
        .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]},\"uniqueItems\":true}");
    assertThat(compact(collections.get("totals")))
        .isEqualTo("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\",\"format\":\"int64\"}}");
    assertThat(compact(collections.get("lines"))).isEqualTo("{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/Line\"}}");
    assertThat(compact(collections.get("groups")))
        .isEqualTo("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}");
  }

  // ---- records -----------------------------------------------------------------------------------

  record Person(@Nullable String nickname, String name, int age, @Nullable Integer height) {}

  @Test
  void aRecordIsAClosedObjectAndNullableMeansMayBeAbsentNeverNull() {
    JsonNode person = schema(doc(getting(Person.class)), "Person");

    assertThat(compact(person))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"nickname\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},"
                + "\"age\":{\"type\":\"integer\",\"format\":\"int32\"},\"height\":{\"type\":\"integer\",\"format\":\"int32\"}},"
                + "\"required\":[\"name\",\"age\"],\"additionalProperties\":false}");
  }

  record Empty() {}

  @Test
  void aRecordWithoutRequiredComponentsHasNoRequiredList() {
    assertThat(compact(schema(doc(getting(Empty.class)), "Empty")))
        .isEqualTo("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
  }

  record Outer(Inner inner) {
    record Inner(String a) {}
  }

  record Item(String id) {}

  record Page<T>(List<T> items, @Nullable String next) {}

  record Pair<A, B>(A left, B right) {}

  @Test
  void namesFollowNestingAndTypeArgumentsButNotHolderClasses() {
    JsonNode doc = doc(getting(Outer.class), Operation.get("/p", new TypeRef<Page<Item>>() {}).id("pageItems").build(),
        Operation.get("/q", new TypeRef<Pair<String, List<Item>>>() {}).id("pair").build(),
        Operation.get("/r", new TypeRef<Page<Map<String, Item>>>() {}).id("pageMaps").build());

    assertThat(doc.path("components").path("schemas").propertyNames())
        .containsExactly("Error", "Item", "Outer", "OuterInner", "PageItem", "PageMapItem", "PairStringListItem");
    assertThat(compact(schema(doc, "PageItem").get("properties").get("items")))
        .isEqualTo("{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/Item\"}}");
  }

  static final class HolderA {
    record Twin(String a) {}
  }

  static final class HolderB {
    record Twin(String b) {}
  }

  record BothTwins(HolderA.Twin a, HolderB.Twin b) {}

  @Test
  void twoTypesWithOneNameFailUntilOneIsRenamedInCode() {
    assertThat(problems(getting(BothTwins.class)))
        .singleElement()
        .asString()
        .startsWith("getX.response.b: two types produce the component name Twin:")
        .contains("OpenApi.name");

    JsonNode doc = doc(Docs.app(a -> OpenApi.name(a, HolderB.Twin.class, "OtherTwin"), getting(BothTwins.class)));
    assertThat(doc.path("components").path("schemas").propertyNames()).contains("Twin", "OtherTwin");
  }

  record Error(String code) {}

  @Test
  void theErrorComponentNameIsTheFrameworks() {
    assertThat(problems(getting(Error.class)))
        .singleElement()
        .asString()
        .contains("two types produce the component name Error: the framework's error envelope");
  }

  record Node(String name, List<Node> children) {}

  @Test
  void aRecursiveRecordIsOneComponent() {
    assertThat(compact(schema(doc(getting(Node.class)), "Node").get("properties").get("children")))
        .isEqualTo("{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/Node\"}}");
  }

  // ---- refusals ----------------------------------------------------------------------------------

  record WithOptional(Optional<String> x) {}

  record WithObject(Object x) {}

  @SuppressWarnings("rawtypes")
  record WithRaw(List x) {}

  record WithWildcard(List<? extends CharSequence> x) {}

  record WithIntKeys(Map<Integer, String> x) {}

  record WithIntArray(int[] x) {}

  record WithClass(StringBuilder x) {}

  record WithNullableElement(List<@Nullable String> x) {}

  record WithFloat(float x) {}

  @Test
  void everyRefusalNamesThePathFromTheOperation() {
    assertThat(problems(getting(WithOptional.class)))
        .containsExactly("getX.response.x: Optional is refused (house rule: never a field); make the component @Nullable");
    assertThat(problems(getting(WithObject.class))).containsExactly("getX.response.x: Object is refused; name the type");
    assertThat(problems(getting(WithRaw.class))).containsExactly("getX.response.x: the raw type List has no type arguments");
    assertThat(problems(getting(WithWildcard.class))).singleElement().asString().startsWith("getX.response.x: the wildcard type");
    assertThat(problems(getting(WithIntKeys.class))).containsExactly("getX.response.x: a Map's key must be a String, not Integer");
    assertThat(problems(getting(WithIntArray.class)))
        .containsExactly("getX.response.x: the array int[] is refused; use a List (byte[] is the one array allowed)");
    assertThat(problems(getting(WithClass.class)))
        .containsExactly("getX.response.x: java.lang.StringBuilder is not a record, an enum, a sealed interface or a registered value type (OpenApi.schema)");
    assertThat(problems(getting(WithNullableElement.class)))
        .containsExactly("getX.response.x: @Nullable on a type argument is refused (arrays and maps hold no absent values)");
    assertThat(problems(getting(WithFloat.class))).singleElement().asString().contains("float is not a record");
  }

  @Test
  void aTopLevelResponseIsARecordOrAUnion() {
    assertThat(problems(getting(new TypeRef<List<Item>>() {})))
        .containsExactly("getX.response: a response is a record, a sealed interface or NoBody; never a Map, a list or a primitive at the top level");
    assertThat(problems(getting(String.class))).hasSize(1);
    assertThat(problems(getting(new TypeRef<Map<String, Item>>() {}))).hasSize(1);
    assertThat(problems(getting(JsonNode.class))).hasSize(1);
  }

  @Test
  void aBodyIsARecordOrAUnion() {
    Operation<String, Item> op = Operation.post("/x", Validator.of(String.class), Item.class).id("postX").build();

    assertThat(problems(op)).containsExactly("postX.body: a request body is a record or a sealed interface, not String");
  }

  @Test
  void aLocalRecordHasNoName() {
    record Local(String a) {}

    assertThat(problems(getting(Local.class)))
        .singleElement()
        .asString()
        .contains("a local or anonymous")
        .contains("make it a member type");
  }

  // ---- sealed interfaces -------------------------------------------------------------------------

  @Test
  void aSealedInterfaceIsOneOfWithADiscriminatorAndVariantsCarryItFirst() {
    JsonNode doc = Json.mapper().readTree(OpenApiFixture.fixture());

    assertThat(compact(schema(doc, "Shape")))
        .isEqualTo(
            "{\"oneOf\":[{\"$ref\":\"#/components/schemas/ShapeCircle\"},{\"$ref\":\"#/components/schemas/ShapeSquare\"}],"
                + "\"discriminator\":{\"propertyName\":\"kind\",\"mapping\":{\"circle\":\"#/components/schemas/ShapeCircle\","
                + "\"square\":\"#/components/schemas/ShapeSquare\"}}}");
    assertThat(compact(schema(doc, "ShapeSquare")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\",\"const\":\"square\"},"
                + "\"side\":{\"type\":\"number\",\"format\":\"double\"},\"label\":{\"type\":\"string\"}},"
                + "\"required\":[\"kind\",\"side\"],\"additionalProperties\":false}");
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes({@JsonSubTypes.Type(value = Pet.Cat.class, name = "cat"), @JsonSubTypes.Type(value = Pet.Dog.class, name = "dog")})
  sealed interface Pet {
    record Cat(String name) implements Pet {}

    record Dog(String name) implements Pet {}
  }

  @Test
  void aVariantUsedOnItsOwnIsStillTheVariantComponent() {
    JsonNode doc = doc(getting(Pet.Cat.class));

    assertThat(doc.path("components").path("schemas").propertyNames()).containsExactly("Error", "PetCat");
    assertThat(schema(doc, "PetCat").get("properties").propertyNames()).containsExactly("type", "name");
  }

  sealed interface Untyped {
    record A(String a) implements Untyped {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = ByClass.A.class, name = "a")})
  sealed interface ByClass {
    record A(String a) implements ByClass {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Wrapped.A.class, name = "a")})
  sealed interface Wrapped {
    record A(String a) implements Wrapped {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Nameless.A.class)})
  sealed interface Nameless {
    record A(String a) implements Nameless {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Partial.A.class, name = "a")})
  sealed interface Partial {
    record A(String a) implements Partial {}

    record B(String b) implements Partial {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Aliased.A.class, name = "a", names = {"alpha"})})
  sealed interface Aliased {
    record A(String a) implements Aliased {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Clash.A.class, name = "a")})
  sealed interface Clash {
    record A(String kind) implements Clash {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = NotRecord.A.class, name = "a")})
  sealed interface NotRecord {
    final class A implements NotRecord {}
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = Gettered.A.class, name = "a")})
  sealed interface Gettered {
    record A(String a) implements Gettered {}

    default String getLabel() {
      return "label";
    }
  }

  record Holds<T>(T value) {}

  @Test
  void sealedInterfaceRefusals() {
    assertThat(problems(getting(Untyped.class)))
        .singleElement().asString().contains("needs @JsonTypeInfo(use = NAME, include = PROPERTY");
    assertThat(problems(getting(ByClass.class)))
        .singleElement().asString().contains("must be (use = NAME, include = PROPERTY, property = \"...\")");
    assertThat(problems(getting(Wrapped.class)))
        .singleElement().asString().contains("must be (use = NAME, include = PROPERTY, property = \"...\")");
    assertThat(problems(getting(Nameless.class))).singleElement().asString().contains("@JsonSubTypes.Type A of Nameless needs a name");
    assertThat(problems(getting(Partial.class)))
        .singleElement().asString().contains("must name exactly its permitted subtypes [A, B], not [A]");
    assertThat(problems(getting(Aliased.class))).singleElement().asString().contains("(aliases) on Aliased cannot be documented");
    assertThat(problems(getting(Clash.class)))
        .containsExactly("getX.response.A.kind: the component has the name of the discriminator property 'kind'");
    assertThat(problems(getting(NotRecord.class))).singleElement().asString().contains("the permitted subtype A of NotRecord must be a record");
    assertThat(problems(getting(Gettered.class))).singleElement().asString().contains("A.getLabel() looks like a getter");
  }

  // ---- Jackson annotations -----------------------------------------------------------------------

  record Renamed(@JsonProperty("full_name") String name) {}

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  record Included(String a) {}

  record Formatted(@JsonFormat(shape = JsonFormat.Shape.NUMBER) java.time.Instant at) {}

  record Aliases(@JsonAlias("nm") String name) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Snake(String fullName) {}

  @Retention(RetentionPolicy.RUNTIME)
  @JacksonAnnotationsInside
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @interface Combo {}

  @Combo
  record Combined(String a) {}

  enum Valued {
    A;

    @JsonValue
    String json() {
      return "a";
    }
  }

  enum Renaming {
    @JsonProperty("alpha")
    A
  }

  record UsesValued(Valued v) {}

  record UsesRenaming(Renaming r) {}

  record IgnoredComponent(@JsonIgnore String hidden, String shown) {}

  record Getters(String a) {
    public String getTotal() {
      return a;
    }

    public boolean isEmpty() {
      return a.isEmpty();
    }
  }

  record IgnoredGetters(String a) {
    @JsonIgnore
    public String getTotal() {
      return a;
    }

    public static String getStatic() {
      return "s";
    }

    public String get() {
      return a;
    }

    public String isNotBoolean() {
      return a;
    }
  }

  @Test
  void anyJacksonAnnotationTheDocumentDoesNotUnderstandFailsTheDump() {
    assertThat(problems(getting(Renamed.class)))
        .containsExactly("getX.response: Jackson annotation @JsonProperty on component Renamed.name is not understood by the document");
    assertThat(problems(getting(Included.class)))
        .containsExactly("getX.response: Jackson annotation @JsonInclude on Included is not understood by the document");
    assertThat(problems(getting(Formatted.class))).singleElement().asString().contains("@JsonFormat on component Formatted.at");
    assertThat(problems(getting(Aliases.class))).singleElement().asString().contains("@JsonAlias on component Aliases.name");
    assertThat(problems(getting(Snake.class))).singleElement().asString().contains("@JsonNaming on Snake");
    assertThat(problems(getting(Combined.class))).singleElement().asString().contains("@Combo on Combined");
    assertThat(problems(getting(UsesValued.class))).singleElement().asString().contains("@JsonValue on Valued.json()");
    assertThat(problems(getting(UsesRenaming.class))).singleElement().asString().contains("@JsonProperty on Renaming.A");
    assertThat(problems(getting(IgnoredComponent.class)))
        .singleElement().asString().contains("@JsonIgnore on component IgnoredComponent.hidden would drop it from the JSON");
  }

  @Test
  void getterLikeMethodsFailUnlessIgnored() {
    assertThat(problems(getting(Getters.class)))
        .containsExactlyInAnyOrder(
            "getX.response: Getters.getTotal() looks like a getter, so Jackson would write it as a property the document does not have: rename it or mark it @JsonIgnore",
            "getX.response: Getters.isEmpty() looks like a getter, so Jackson would write it as a property the document does not have: rename it or mark it @JsonIgnore");
    assertThat(doc(getting(IgnoredGetters.class)).path("components").path("schemas").has("IgnoredGetters")).isTrue();
  }

  @Test
  void aRegisteredTypeIsNotCheckedForJacksonAnnotations() {
    JsonNode doc = doc(Docs.app(a -> OpenApi.schema(a, Renamed.class, Schema.string()), getting(new TypeRef<Holds<Renamed>>() {})));

    assertThat(compact(schema(doc, "HoldsRenamed").get("properties").get("value"))).isEqualTo("{\"type\":\"string\"}");
  }

  // ---- registered schemas and property overrides ---------------------------------------------------

  record Flag(boolean on, String mode) {}

  @Test
  void aPropertyOverrideReplacesOneComponentsSchemaAndKeepsItsRequiredness() {
    JsonNode doc =
        doc(Docs.app(
            a -> {
              OpenApi.property(a, Flag.class, "on", Schema.bool().constant(true));
              OpenApi.property(a, Flag.class, "mode", Schema.string().constant("fetch-metadata"));
            },
            getting(Flag.class)));

    assertThat(compact(schema(doc, "Flag")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"on\":{\"type\":\"boolean\",\"const\":true},"
                + "\"mode\":{\"type\":\"string\",\"const\":\"fetch-metadata\"}},\"required\":[\"on\",\"mode\"],\"additionalProperties\":false}");
  }

  @Test
  void aPropertyOverrideOfAMissingComponentFails() {
    assertThat(problems(a -> OpenApi.property(a, Flag.class, "off", Schema.bool()), getting(Flag.class)))
        .containsExactly("getX.response: OpenApi.property names 'off', which is not a component of Flag");
  }

  sealed interface Answer {
    record Yes(boolean ok) implements Answer {}

    record No(String why) implements Answer {}
  }

  @Test
  void aRegisteredUnionIsAComponentWithoutADiscriminator() {
    JsonNode doc =
        doc(Docs.app(a -> OpenApi.schema(a, Answer.class, Schema.oneOf(Answer.Yes.class, Answer.No.class)), getting(Answer.class)));

    assertThat(compact(schema(doc, "Answer")))
        .isEqualTo("{\"oneOf\":[{\"$ref\":\"#/components/schemas/AnswerYes\"},{\"$ref\":\"#/components/schemas/AnswerNo\"}]}");
    assertThat(schema(doc, "AnswerYes").get("properties").propertyNames()).containsExactly("ok");
  }

  @Test
  void theSameTypeRegisteredTwiceFails() {
    assertThat(problems(a -> {
          OpenApi.schema(a, Item.class, Schema.string());
          OpenApi.schema(a, Item.class, Schema.int32());
        }, getting(Empty.class)))
        .contains("OpenApi.schema: " + Item.class.getName() + " has two schemas");
  }

  @Test
  void noBodyIsNotAComponent() {
    JsonNode doc = doc(Operation.delete("/x", NoBody.class).id("deleteX").build());

    assertThat(doc.path("components").path("schemas").propertyNames()).containsExactly("Error");
  }
}
