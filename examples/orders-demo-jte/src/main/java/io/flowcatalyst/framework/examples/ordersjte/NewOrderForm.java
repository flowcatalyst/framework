package io.flowcatalyst.framework.examples.ordersjte;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The create form's state: what the user typed, the problems found and whether to show it open.
 *
 * @param customer the customer field
 * @param amount the amount field, in currency units
 * @param status the status field
 * @param errors field name to message
 * @param formError a problem that belongs to no field
 * @param open whether the dialog renders open (a failed submit)
 */
public record NewOrderForm(
    String customer, String amount, String status, Map<String, String> errors, @Nullable String formError, boolean open) {

  /**
   * An empty, closed form.
   *
   * @return the form
   */
  public static NewOrderForm empty() {
    return new NewOrderForm("", "", "PENDING", Map.of(), null, false);
  }

  /**
   * The message of a field, if it has one.
   *
   * @param field the field name
   * @return the message or null
   */
  public @Nullable String error(String field) {
    return errors.get(field);
  }

  /**
   * Validates submitted values.
   *
   * @param values the form values by name
   * @return the form: closed and valid, or open with its errors
   */
  public static NewOrderForm validate(Map<String, String> values) {
    String customer = values.getOrDefault("customer", "").strip();
    String amount = values.getOrDefault("amount", "").strip();
    String status = values.getOrDefault("status", "PENDING");
    Map<String, String> errors = new java.util.LinkedHashMap<>();
    if (customer.length() < 2) {
      errors.put("customer", "Enter the customer's name (at least 2 characters).");
    } else if (customer.length() > 80) {
      errors.put("customer", "The name is too long (80 characters at most).");
    }
    if (cents(amount) <= 0) {
      errors.put("amount", "Enter an amount greater than zero, for example 49.90.");
    }
    if (!Order.STATUSES.contains(status) || status.equals("CANCELLED")) {
      errors.put("status", "Choose a status.");
    }
    return new NewOrderForm(customer, amount, status, Map.copyOf(errors), null, !errors.isEmpty());
  }

  /**
   * The amount in cents.
   *
   * @param amount text such as {@code 49.90}
   * @return the cents, or 0 when it is not a positive amount with at most two decimals
   */
  public static long cents(String amount) {
    if (!amount.matches("\\d{1,9}(\\.\\d{1,2})?")) {
      return 0;
    }
    return new java.math.BigDecimal(amount).movePointRight(2).longValueExact();
  }
}
