package io.flowcatalyst.framework.http.openapi;

import static io.flowcatalyst.framework.http.openapi.Docs.compact;
import static io.flowcatalyst.framework.http.openapi.Docs.doc;
import static io.flowcatalyst.framework.http.openapi.Docs.getting;
import static io.flowcatalyst.framework.http.openapi.Docs.schema;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.measure.money.Currency;
import io.flowcatalyst.framework.measure.money.Money;
import io.flowcatalyst.framework.measure.units.Quantity;
import io.flowcatalyst.framework.measure.units.Unit;
import io.flowcatalyst.framework.measure.units.UnitPrice;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The measure types in the OpenAPI document (measure contract section 5). */
class MeasureSchemaTest {

  record Shipment(Money charge, Quantity weight, UnitPrice rate, Currency currency, Unit unit) {}

  @Test
  void moneyQuantityAndUnitPriceAreComponents() {
    JsonNode d = doc(getting(Shipment.class));
    assertThat(compact(schema(d, "Money")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"string\",\"format\":\"decimal\"},"
                + "\"currency\":{\"type\":\"string\",\"pattern\":\"^[A-Z]{3}$\"}},\"required\":[\"amount\",\"currency\"],\"additionalProperties\":false}");
    assertThat(compact(schema(d, "Quantity")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\",\"format\":\"decimal\"},"
                + "\"unit\":{\"type\":\"string\"}},\"required\":[\"value\",\"unit\"],\"additionalProperties\":false}");
    assertThat(compact(schema(d, "UnitPrice")))
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"price\":{\"$ref\":\"#/components/schemas/Money\"},\"per\":{\"type\":\"string\"}},"
                + "\"required\":[\"price\",\"per\"],\"additionalProperties\":false}");
  }

  @Test
  void currencyAndUnitAreStringsInline() {
    JsonNode s = schema(doc(getting(Shipment.class)), "Shipment");
    assertThat(compact(s.path("properties").path("currency"))).isEqualTo("{\"type\":\"string\",\"pattern\":\"^[A-Z]{3}$\"}");
    assertThat(compact(s.path("properties").path("unit"))).isEqualTo("{\"type\":\"string\"}");
    assertThat(compact(s.path("properties").path("charge"))).isEqualTo("{\"$ref\":\"#/components/schemas/Money\"}");
  }
}
