package io.flowcatalyst.framework.measure.money;

import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A rate from one currency to another as of an instant. There is no rate provider: where rates come
 * from is the application's, which stores the rate it used next to the converted amount.
 *
 * @param from the currency converted from
 * @param to the currency converted to
 * @param rate units of {@code to} per one unit of {@code from}, greater than zero
 * @param asOf when the rate was valid
 */
public record ExchangeRate(Currency from, Currency to, BigDecimal rate, Instant asOf) {

  /**
   * Checks the rate.
   *
   * @throws MeasureException {@code RATE_INVALID} when the rate is not greater than zero
   */
  public ExchangeRate {
    if (rate.signum() <= 0) {
      throw new MeasureException(MeasureException.RATE_INVALID, "the exchange rate must be greater than zero");
    }
  }

  /**
   * Converts: {@code amount * rate} in {@code to}, rounded to {@code to}'s minor units.
   *
   * @param money an amount in {@code from}
   * @param mode how to round
   * @return the amount in {@code to}
   * @throws MeasureException {@code CURRENCY_MISMATCH} when the money is not in {@code from}
   */
  public Money convert(Money money, RoundingMode mode) {
    if (!money.currency().equals(from)) {
      throw new MeasureException(MeasureException.CURRENCY_MISMATCH, "the amount is not in the rate's source currency");
    }
    return new Money(money.amount().multiply(rate), to).round(mode);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ExchangeRate r
        && from.equals(r.from)
        && to.equals(r.to)
        && rate.compareTo(r.rate) == 0
        && asOf.equals(r.asOf);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(from, to, io.flowcatalyst.framework.measure.Decimals.hash(rate), asOf);
  }
}
