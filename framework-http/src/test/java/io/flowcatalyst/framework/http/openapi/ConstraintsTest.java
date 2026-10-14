package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.Rules.each;
import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.minLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.oneOf;
import static io.flowcatalyst.framework.http.Rules.pattern;
import static io.flowcatalyst.framework.http.openapi.Docs.compact;
import static io.flowcatalyst.framework.http.openapi.Docs.doc;
import static io.flowcatalyst.framework.http.openapi.Docs.problems;
import static io.flowcatalyst.framework.http.openapi.Docs.schema;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Validator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** Constraints from the Validator (contract section 5.4). */
class ConstraintsTest {

  record View(String id) {}

  static <B> Operation<B, View> posting(String id, Validator<B> v) {
    return Operation.post("/" + id, v, View.class).id(id).build();
  }

  static JsonNode property(Validator<?> v, String component) {
    JsonNode doc = doc(posting("postX", v));
    return schema(doc, v.type().getSimpleName()).get("properties").get(component);
  }

  @Test
  void everyRuleOfTheFixtureBody() {
    JsonNode doc = Json.mapper().readTree(OpenApiFixture.fixture());
    JsonNode body = schema(doc, "WidgetBody").get("properties");

    assertThat(compact(body.get("name"))).as("notNull, notBlank, maxLength")
        .isEqualTo("{\"type\":\"string\",\"pattern\":\"\\\\S\",\"minLength\":1,\"maxLength\":100}");
    assertThat(compact(body.get("code"))).as("minLength, maxLength, pattern")
        .isEqualTo("{\"type\":\"string\",\"pattern\":\"^(?:[A-Z][A-Z0-9-]*)$\",\"minLength\":3,\"maxLength\":12}");
    assertThat(compact(body.get("note"))).as("a rule on an optional component").isEqualTo("{\"type\":\"string\",\"maxLength\":500}");
    assertThat(compact(body.get("quantity"))).as("range(long)")
        .isEqualTo("{\"type\":\"integer\",\"format\":\"int32\",\"minimum\":1,\"maximum\":1000}");
    assertThat(compact(body.get("ratio"))).as("range(double)")
        .isEqualTo("{\"type\":\"number\",\"format\":\"double\",\"minimum\":0.5,\"maximum\":2.5}");
    assertThat(compact(body.get("tags"))).as("size and each")
        .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"maxLength\":20},\"minItems\":1,\"maxItems\":5}");
    assertThat(compact(body.get("colour"))).as("oneOf").isEqualTo("{\"type\":\"string\",\"enum\":[\"red\",\"green\"]}");
    assertThat(compact(body.get("free"))).as("custom documents nothing").isEqualTo("{\"type\":\"string\"}");
    assertThat(compact(schema(doc, "Line").get("properties")))
        .as("nested and each(valid) reach the nested type")
        .isEqualTo("{\"sku\":{\"type\":\"string\",\"pattern\":\"\\\\S\",\"minLength\":1},"
            + "\"quantity\":{\"type\":\"integer\",\"format\":\"int32\",\"minimum\":1,\"maximum\":99}}");
    assertThat(schema(doc, "WidgetBody").get("required").toString()).as("notNull does not change required-ness")
        .doesNotContain("\"note\"");
  }

  record Text(String a) {}

  @Test
  void theTighterBoundWinsAndAnExplicitPatternWinsOverNotBlanks() {
    assertThat(compact(property(Validator.of(Text.class).field("a", Text::a, notBlank(), minLength(5)), "a")))
        .isEqualTo("{\"type\":\"string\",\"pattern\":\"\\\\S\",\"minLength\":5}");
    assertThat(compact(property(Validator.of(Text.class).field("a", Text::a, minLength(5), minLength(3), maxLength(9), maxLength(4)), "a")))
        .isEqualTo("{\"type\":\"string\",\"minLength\":5,\"maxLength\":4}");
    assertThat(compact(property(Validator.of(Text.class).field("a", Text::a, notBlank(), pattern("[a-z]+")), "a")))
        .isEqualTo("{\"type\":\"string\",\"pattern\":\"^(?:[a-z]+)$\",\"minLength\":1}");
  }

  @Test
  void twoPatternsOnOneValueFail() {
    Validator<Text> v = Validator.of(Text.class).field("a", Text::a, pattern("[a-z]+"), pattern("[0-9]+"));

    assertThat(problems(posting("postX", v)))
        .containsExactly("postX.body.a: two patterns apply to one value (^(?:[a-z]+)$ and ^(?:[0-9]+)$)");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"(?i)abc", "a++", "a*+", "a?+", "a{2}+", "\\Aabc", "abc\\z", "abc\\Z", "a\\hb", "\\p{javaLowerCase}+",
        "\\p{Lower}+", "\\p{IsAlphabetic}", "(?>a)", "[a-z&&[^b]]", "\\Qa.b\\E", "\\R", "a\\vb", "[]a]", "\\x{41}", "\\p{L}+", "\\P{Lu}"})
  void aPatternEcmaCannotReadFailsTheDump(String regex) {
    Validator<Text> v = Validator.of(Text.class).field("a", Text::a, pattern(regex));

    assertThat(problems(posting("postX", v)))
        .singleElement()
        .asString()
        .startsWith("postX.body.a: the pattern " + regex + " cannot be documented: ")
        .endsWith("; use Rules.custom");
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?:a|b)", "(?<name>a)", "(?=a)b", "(?!a)b", "(?<=a)b", "\\d+\\.\\d{2}", "[A-Za-z_-]+", "a*?", "\\\\A"})
  void aPatternEcmaReadsTheSameIsAnchored(String regex) {
    assertThat(property(Validator.of(Text.class).field("a", Text::a, pattern(regex)), "a").get("pattern").asString())
        .isEqualTo("^(?:" + regex + ")$");
  }

  @Test
  void aPatternCompiledWithFlagsFails() {
    Validator<Text> v = Validator.of(Text.class).field("a", Text::a, pattern(Pattern.compile("abc", Pattern.CASE_INSENSITIVE)));

    assertThat(problems(posting("postX", v))).singleElement().asString().contains("compiled with flags");
  }

  enum Level {
    LOW,
    MID,
    HIGH
  }

  record Levels(Level level, List<String> names) {}

  @Test
  void oneOfNarrowsAnEnumAndFailsOnAList() {
    assertThat(compact(property(Validator.of(Levels.class).field("level", Levels::level, oneOf(Level.HIGH, Level.LOW)), "level")))
        .isEqualTo("{\"type\":\"string\",\"enum\":[\"HIGH\",\"LOW\"]}");
    assertThat(problems(posting("postX", Validator.of(Levels.class).field("names", Levels::names, oneOf(List.of("a"))))))
        .singleElement().asString().contains("postX.body.names: oneOf");
    assertThat(problems(posting("postX", Validator.of(Levels.class).field("level", Levels::level, oneOf("LOW", "NONE")))))
        .containsExactly("postX.body.level: oneOf names NONE, which is not a constant of the enum");
  }

  @Test
  void eachAppliesToTheElements() {
    assertThat(compact(property(Validator.of(Levels.class).field("names", Levels::names, each(notBlank())), "names")))
        .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"\\\\S\",\"minLength\":1}}");
  }

  @Test
  void aValidatorFieldThatIsNotAComponentFails() {
    Validator<Text> v = Validator.of(Text.class).field("b", Text::a, notBlank());

    assertThat(problems(posting("postX", v)))
        .containsExactly("postX.body: the validator of Text checks 'b', which is not a component of the record");
  }

  @Test
  void twoValidatorsThatGiveOneTypeDifferentConstraintsFail() {
    Validator<Text> one = Validator.of(Text.class).field("a", Text::a, maxLength(5));
    Validator<Text> two = Validator.of(Text.class).field("a", Text::a, maxLength(6));

    assertThat(problems(posting("postOne", one), posting("postTwo", two)))
        .singleElement().asString().contains("two validators give Text different constraints");
  }

  @Test
  void twoValidatorsWithTheSameDocumentedConstraintsAreFineAndCustomRulesDoNotCount() {
    Validator<Text> one = Validator.of(Text.class).field("a", Text::a, maxLength(5));
    Validator<Text> two =
        Validator.of(Text.class)
            .field("a", Text::a, maxLength(5), io.flowcatalyst.framework.http.Rules.custom("X", "x", s -> true));

    JsonNode doc = doc(posting("postOne", one), posting("postTwo", two), posting("postThree", one));
    assertThat(compact(schema(doc, "Text").get("properties").get("a"))).isEqualTo("{\"type\":\"string\",\"maxLength\":5}");
  }

  record Wrapper(Text text) {}

  @Test
  void aRuleThatDoesNotFitTheValueFails() {
    Validator<Wrapper> v = Validator.of(Wrapper.class).field("text", Wrapper::text, oneOf(new Text("a")));

    assertThat(problems(posting("postX", v)))
        .singleElement().asString().contains("oneOf values must be strings, numbers, booleans or enum constants");
  }
}
