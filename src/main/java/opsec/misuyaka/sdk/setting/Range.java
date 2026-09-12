package opsec.misuyaka.sdk.setting;

import java.util.Objects;

public record Range<N extends Number & Comparable<N>>(N minimum, N maximum) {
  public Range {
    Objects.requireNonNull(minimum, "minimum");
    Objects.requireNonNull(maximum, "maximum");
    if (minimum.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("Range minimum cannot exceed its maximum");
    }
  }
}
