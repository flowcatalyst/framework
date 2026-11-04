package io.flowcatalyst.framework.measure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;

/**
 * Generates {@code conformance/measure/iso4217.json} from the JDK's {@link Currency} table (codes
 * with a fraction of -1, such as XAU, are left out). Run it when the JDK's table changes and the
 * change is wanted:
 *
 * <pre>
 * java framework-measure/src/test/java/io/flowcatalyst/framework/measure/Iso4217Generator.java conformance/measure/iso4217.json
 * </pre>
 *
 * {@code Iso4217DataTest} fails when the committed file differs from what this produces.
 */
public final class Iso4217Generator {

  private Iso4217Generator() {}

  /**
   * The file's text.
   *
   * @return the JSON document
   */
  public static String generate() {
    List<Currency> all =
        Currency.getAvailableCurrencies().stream()
            .filter(c -> c.getDefaultFractionDigits() >= 0)
            .sorted((a, b) -> a.getCurrencyCode().compareTo(b.getCurrencyCode()))
            .toList();
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"description\": \"ISO 4217 currencies and their minor units, generated from JDK 25 java.util.Currency.getAvailableCurrencies() (codes with a default fraction of -1, such as XAU, are left out) by framework-measure Iso4217Generator. Sorted by code. Both languages embed this table; an unknown code is CURRENCY_UNKNOWN.\",\n");
    sb.append("  \"currencies\": [\n");
    for (int i = 0; i < all.size(); i++) {
      Currency c = all.get(i);
      sb.append("    {\"code\": \"")
          .append(c.getCurrencyCode())
          .append("\", \"minorUnits\": ")
          .append(c.getDefaultFractionDigits())
          .append('}');
      sb.append(i + 1 < all.size() ? ",\n" : "\n");
    }
    sb.append("  ]\n}\n");
    return sb.toString();
  }

  /**
   * Writes the file.
   *
   * @param args the path to write
   * @throws IOException when the file cannot be written
   */
  public static void main(String[] args) throws IOException {
    Files.writeString(Path.of(args[0]), generate(), StandardCharsets.UTF_8);
  }
}
