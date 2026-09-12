package dev.lifus.janetreborn.service.placement;

public final class HitJitter {
  private HitJitter() {}

  public static double offset(long seed, int axisSalt, double maximum) {
    if (!Double.isFinite(maximum) || maximum <= 0.0) return 0.0;
    long mixed = mix(seed + 0x9E3779B97F4A7C15L * (axisSalt + 1L));
    double unit = (mixed >>> 11) * 0x1.0p-53;
    return (unit * 2.0 - 1.0) * maximum;
  }

  private static long mix(long value) {
    value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
    value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
    return value ^ value >>> 31;
  }
}
