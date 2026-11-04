package io.flowcatalyst.framework.measure;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import org.junit.jupiter.api.Test;

/** The committed table is what {@link Iso4217Generator} produces from this JDK. */
class Iso4217DataTest {

  @Test
  void committedTableIsTheGeneratedOne() {
    Conformance.check("measure/iso4217.json", Iso4217Generator.generate());
  }

  @Test
  void tableHasTheCurrenciesTheContractNames() {
    String table = Conformance.read("measure/iso4217.json");
    assertThat(table).contains("{\"code\": \"ZAR\", \"minorUnits\": 2}", "{\"code\": \"JPY\", \"minorUnits\": 0}", "{\"code\": \"BHD\", \"minorUnits\": 3}");
    assertThat(table).doesNotContain("\"XAU\"");
  }
}
