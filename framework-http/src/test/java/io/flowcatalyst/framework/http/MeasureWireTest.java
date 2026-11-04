package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.measure.money.Currency;
import io.flowcatalyst.framework.measure.money.Money;
import io.flowcatalyst.framework.measure.units.Quantity;
import io.flowcatalyst.framework.measure.units.Unit;
import io.flowcatalyst.framework.measure.units.UnitPrice;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** The measure types on the wire (measure contract section 5), through the typed-operation mapper. */
class MeasureWireTest {

  record Shipment(Money charge, Quantity weight, UnitPrice rate, Currency currency, Unit unit) {}

  private static final ObjectMapper M = Json.operationMapper();

  @Test
  void writesStringsThatKeepTheirScale() {
    Shipment s =
        new Shipment(
            Money.parse("3132.480", "ZAR"),
            Quantity.parse("7831.2", "KGM"),
            new UnitPrice(Money.parse("0.40", "ZAR"), Unit.KGM),
            Currency.of("USD"),
            Unit.of("m3"));
    String json = M.writeValueAsString(s);
    assertThat(json)
        .isEqualTo(
            "{\"charge\":{\"amount\":\"3132.480\",\"currency\":\"ZAR\"},\"weight\":{\"value\":\"7831.2\",\"unit\":\"KGM\"},"
                + "\"rate\":{\"price\":{\"amount\":\"0.40\",\"currency\":\"ZAR\"},\"per\":\"KGM\"},\"currency\":\"USD\",\"unit\":\"MTQ\"}");
    Shipment back = M.readValue(json, Shipment.class);
    assertThat(back).isEqualTo(s);
    assertThat(back.charge().amount()).isEqualByComparingTo(new BigDecimal("3132.48"));
    assertThat(back.charge().amount().scale()).isEqualTo(3);
  }

  @Test
  void aJsonNumberIsNotAnAmount() {
    assertThatThrownBy(() -> M.readValue("{\"amount\":3132.48,\"currency\":\"ZAR\"}", Money.class)).isInstanceOf(JacksonException.class);
    assertThatThrownBy(() -> M.readValue("{\"amount\":\"1\",\"currency\":\"ZAR\",\"extra\":1}", Money.class)).isInstanceOf(JacksonException.class);
    assertThatThrownBy(() -> M.readValue("{\"amount\":\"1\"}", Money.class)).isInstanceOf(JacksonException.class);
  }

  @Test
  void readingReportsTheContractsErrorCodes() {
    assertThat(codeOf("{\"amount\":\"1e3\",\"currency\":\"ZAR\"}", Money.class)).isEqualTo("DECIMAL_INVALID");
    assertThat(codeOf("{\"amount\":\"1\",\"currency\":\"ZZZ\"}", Money.class)).isEqualTo("CURRENCY_UNKNOWN");
    assertThat(codeOf("{\"value\":\"1\",\"unit\":\"furlong\"}", Quantity.class)).isEqualTo("UNIT_UNKNOWN");
    assertThat(codeOf("{\"price\":{\"amount\":\"1\",\"currency\":\"ZAR\"},\"per\":\"XPX\"}", UnitPrice.class)).isEqualTo("NO_CONVERSION");
    assertThat(codeOf("\"zar\"", Currency.class)).isEqualTo("CURRENCY_UNKNOWN");
  }

  @Test
  void aNestedFailureKeepsItsPathAndCode() {
    try {
      M.readValue("{\"charge\":{\"amount\":\"x\",\"currency\":\"ZAR\"}}", Shipment.class);
    } catch (JacksonException e) {
      ValidationException v = Json.toValidation(e);
      assertThat(v.errors()).singleElement().satisfies(f -> {
        assertThat(f.code()).isEqualTo("DECIMAL_INVALID");
        assertThat(f.field()).isEqualTo("charge");
      });
      return;
    }
    throw new AssertionError("no failure");
  }

  /** The same bodies and detail strings as Go's {@code TestMeasureValuesFailWithTheirContractCode}. */
  @Test
  void theSameDetailsAsGo() {
    String[][] cases = {
      {"{\"charge\":{\"amount\":\"x\",\"currency\":\"ZAR\"}}", "charge", "DECIMAL_INVALID: not a plain decimal number"},
      {"{\"charge\":{\"amount\":\"1\",\"currency\":\"ZZZ\"}}", "charge", "CURRENCY_UNKNOWN: unknown currency code"},
      {"{\"weight\":{\"value\":\"1\",\"unit\":\"furlong\"}}", "weight", "UNIT_UNKNOWN: unknown unit"},
      {"{\"rate\":{\"price\":{\"amount\":\"1.0\",\"currency\":\"ZZZ\"},\"per\":\"KGM\"}}", "rate", "CURRENCY_UNKNOWN: unknown currency code"},
      {"{\"rate\":{\"price\":{\"amount\":\"1\",\"currency\":\"ZAR\"},\"per\":\"XPX\"}}", "rate", "NO_CONVERSION: a price per packaging unit has no conversion"},
      {"{\"currency\":\"zar\"}", "currency", "CURRENCY_UNKNOWN: unknown currency code"},
      {"{\"unit\":\"furlong\"}", "unit", "UNIT_UNKNOWN: unknown unit"},
      {"{\"charge\":{\"amount\":3132.48,\"currency\":\"ZAR\"}}", "charge", "INVALID_JSON: wrong type or format"},
    };
    for (String[] c : cases) {
      try {
        M.readValue(c[0], Shipment.class);
      } catch (JacksonException e) {
        FieldError f = Json.toValidation(e).errors().getFirst();
        assertThat(f.field()).as(c[0]).isEqualTo(c[1]);
        assertThat(f.code() + ": " + f.message()).as(c[0]).isEqualTo(c[2]);
        continue;
      }
      throw new AssertionError("accepted: " + c[0]);
    }
  }

  private static String codeOf(String json, Class<?> type) {
    try {
      M.readValue(json, type);
    } catch (JacksonException e) {
      return Json.toValidation(e).errors().getFirst().code();
    }
    return "none";
  }
}
