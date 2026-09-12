package opsec.misuyaka.sdk.setting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public final class NumberSetting<N extends Number & Comparable<N>> extends Setting<N> {
  private final N min;
  private final N max;
  private final N step;

  public NumberSetting(String name, N defaultValue, N min, N max, N step) {
    super(name, defaultValue);
    this.min = Objects.requireNonNull(min);
    this.max = Objects.requireNonNull(max);
    this.step = Objects.requireNonNull(step);
    validate(defaultValue);
  }

  public NumberSetting(String id, String name, N defaultValue, N min, N max, N step) {
    super(id, name, defaultValue);
    this.min = Objects.requireNonNull(min);
    this.max = Objects.requireNonNull(max);
    this.step = Objects.requireNonNull(step);
    validate(defaultValue);
  }

  private void validate(N defaultValue) {
    if (min.compareTo(max) > 0
        || step.doubleValue() <= 0
        || defaultValue.compareTo(min) < 0
        || defaultValue.compareTo(max) > 0) {
      throw new IllegalArgumentException("Invalid number range");
    }
  }

  @Override
  public void setValue(N value) {
    Objects.requireNonNull(value);
    super.setValue(convert(quantize(value.doubleValue())));
  }

  public N getMin() {
    return min;
  }

  public N getMax() {
    return max;
  }

  public N getStep() {
    return step;
  }

  @SuppressWarnings("unchecked")
  public void setDouble(double value) {
    setValue(convert(value));
  }

  private double quantize(double value) {
    double clamped = Math.max(min.doubleValue(), Math.min(max.doubleValue(), value));
    double steps = Math.rint((clamped - min.doubleValue()) / step.doubleValue());
    return Math.max(
        min.doubleValue(),
        Math.min(max.doubleValue(), min.doubleValue() + steps * step.doubleValue()));
  }

  @SuppressWarnings("unchecked")
  private N convert(double value) {
    Number current = getDefaultValue();
    Number converted =
        current instanceof Byte
            ? (byte) value
            : current instanceof Short
                ? (short) value
                : current instanceof Integer
                    ? (int) value
                    : current instanceof Long
                        ? (long) value
                        : current instanceof Float
                            ? (float) value
                            : current instanceof Double
                                ? value
                                : current instanceof BigInteger
                                    ? BigDecimal.valueOf(value).toBigInteger()
                                    : current instanceof BigDecimal
                                        ? BigDecimal.valueOf(value)
                                        : null;
    if (converted == null)
      throw new IllegalStateException("Unsupported number type " + current.getClass().getName());
    return (N) converted;
  }
}
