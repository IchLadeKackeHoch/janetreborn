package opsec.misuyaka.sdk.setting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public final class RangeSetting<N extends Number & Comparable<N>> extends Setting<Range<N>> {
  private final N min;
  private final N max;
  private final N step;
  private String legacyMinimumId;
  private String legacyMaximumId;
  private boolean legacyCenterVariation;

  public RangeSetting(String name, Range<N> defaultValue, N min, N max, N step) {
    super(name, defaultValue);
    this.min = Objects.requireNonNull(min, "min");
    this.max = Objects.requireNonNull(max, "max");
    this.step = Objects.requireNonNull(step, "step");
    validate(defaultValue);
  }

  public RangeSetting(String id, String name, Range<N> defaultValue, N min, N max, N step) {
    super(id, name, defaultValue);
    this.min = Objects.requireNonNull(min, "min");
    this.max = Objects.requireNonNull(max, "max");
    this.step = Objects.requireNonNull(step, "step");
    validate(defaultValue);
  }

  private void validate(Range<N> defaultValue) {
    if (min.compareTo(max) > 0
        || step.doubleValue() <= 0
        || defaultValue.minimum().compareTo(min) < 0
        || defaultValue.maximum().compareTo(max) > 0) {
      throw new IllegalArgumentException("Invalid range setting bounds");
    }
  }

  @Override
  public void setValue(Range<N> value) {
    Objects.requireNonNull(value, "value");
    N minimum = convert(quantize(value.minimum().doubleValue()));
    N maximum = convert(quantize(value.maximum().doubleValue()));
    super.setValue(new Range<>(minimum, maximum));
  }

  public void setDouble(double minimum, double maximum) {
    setValue(new Range<>(convert(minimum), convert(maximum)));
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

  public RangeSetting<N> legacyBounds(String minimumId, String maximumId) {
    legacyMinimumId = Objects.requireNonNull(minimumId, "minimumId");
    legacyMaximumId = Objects.requireNonNull(maximumId, "maximumId");
    return this;
  }

  public RangeSetting<N> legacyCenterVariation(String centerId, String variationId) {
    legacyMinimumId = Objects.requireNonNull(centerId, "centerId");
    legacyMaximumId = Objects.requireNonNull(variationId, "variationId");
    legacyCenterVariation = true;
    return this;
  }

  public String getLegacyMinimumId() {
    return legacyMinimumId;
  }

  public String getLegacyMaximumId() {
    return legacyMaximumId;
  }

  public boolean isLegacyCenterVariation() {
    return legacyCenterVariation;
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
    Number current = getDefaultValue().minimum();
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
    if (converted == null) {
      throw new IllegalStateException("Unsupported number type " + current.getClass().getName());
    }
    return (N) converted;
  }
}
