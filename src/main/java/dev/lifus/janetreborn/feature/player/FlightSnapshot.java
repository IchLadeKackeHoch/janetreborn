package dev.lifus.janetreborn.feature.player;

final class FlightSnapshot {
  interface Access {
    boolean mayFly();

    boolean flying();

    float flyingSpeed();

    void setMayFly(boolean value);

    void setFlying(boolean value);

    void setFlyingSpeed(float value);

    void clearMotion();

    void clearFallDistance();
  }

  private final boolean mayFly;
  private final boolean flying;
  private final float flyingSpeed;

  private FlightSnapshot(boolean mayFly, boolean flying, float flyingSpeed) {
    this.mayFly = mayFly;
    this.flying = flying;
    this.flyingSpeed = flyingSpeed;
  }

  static FlightSnapshot capture(Access access) {
    return new FlightSnapshot(access.mayFly(), access.flying(), access.flyingSpeed());
  }

  void restore(Access access) {
    access.setMayFly(mayFly);
    access.setFlying(flying);
    access.setFlyingSpeed(flyingSpeed);
    access.clearMotion();
    access.clearFallDistance();
  }
}
