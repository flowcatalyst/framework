package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Rules describe themselves as data (OpenAPI contract section 5.4); a validator keeps them per field. */
class RulesDescribeTest {

  record R(String a, List<String> b) {}

  @Test
  void everyStandardRuleSaysWhatItChecks() {
    assertThat(Rules.notNull().describe()).isEqualTo(new Constraint.NotNull());
    assertThat(Rules.notBlank().describe()).isEqualTo(new Constraint.NotBlank());
    assertThat(Rules.minLength(2).describe()).isEqualTo(new Constraint.MinLength(2));
    assertThat(Rules.maxLength(9).describe()).isEqualTo(new Constraint.MaxLength(9));
    assertThat(Rules.pattern("[a-z]+").describe()).isEqualTo(new Constraint.Pattern("[a-z]+", 0));
    assertThat(Rules.pattern(Pattern.compile("x", Pattern.CASE_INSENSITIVE)).describe())
        .isEqualTo(new Constraint.Pattern("x", Pattern.CASE_INSENSITIVE));
    assertThat(Rules.range(1, 5).describe()).isEqualTo(new Constraint.Range(BigDecimal.valueOf(1), BigDecimal.valueOf(5)));
    assertThat(Rules.range(0.5, 1.5).describe()).isEqualTo(new Constraint.Range(BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.5)));
    assertThat(Rules.size(1, 3).describe()).isEqualTo(new Constraint.Size(1, 3));
    assertThat(Rules.oneOf("b", "a").describe()).isEqualTo(new Constraint.OneOf(List.of("b", "a")));
    assertThat(Rules.each(Rules.maxLength(3)).describe()).isEqualTo(new Constraint.Each(new Constraint.MaxLength(3)));
    Validator<R> v = Validator.of(R.class);
    assertThat(Rules.valid(v).describe()).isEqualTo(new Constraint.Valid(v));
    assertThat(Rules.custom("X", "x", s -> true).describe()).isEqualTo(new Constraint.None());
    Rule<String> lambda = (value, out) -> {};
    assertThat(lambda.describe()).isEqualTo(new Constraint.None());
  }

  @Test
  void describedRulesStillCheck() {
    Violations out = new Violations();
    Rules.maxLength(2).check("abc", out);
    Rules.oneOf("a").check("b", out);
    Rules.each(Rules.notBlank()).check(List.of("x", " "), out);

    assertThat(out.entries()).extracting(Violations.Entry::code).containsExactly("TOO_LONG", "NOT_ONE_OF", "NOT_BLANK");
  }

  @Test
  void aValidatorKeepsTheRulesOfEachFieldInOrder() {
    Validator<R> v =
        Validator.of(R.class)
            .field("a", R::a, Rules.notBlank(), Rules.maxLength(5))
            .field("b", R::b, Rules.size(0, 2))
            .field("a", R::a, Rules.minLength(1));

    assertThat(v.fields())
        .containsExactly(
            new Validator.FieldRules("a", List.of(new Constraint.NotBlank(), new Constraint.MaxLength(5))),
            new Validator.FieldRules("b", List.of(new Constraint.Size(0, 2))),
            new Validator.FieldRules("a", List.of(new Constraint.MinLength(1))));
    assertThat(Validator.of(R.class).nested("a", R::a, Validator.of(String.class)).fields().getFirst().constraints().getFirst())
        .isInstanceOf(Constraint.Valid.class);
  }
}
