package dev.lifus.janetreborn.service.inventory;

import java.util.List;

public final class SlotPathing {
  public static final int SLOT_CENTER_OFFSET = 8;

  private SlotPathing() {}

  public static Point slotCenter(int left, int top, int slotX, int slotY) {
    return new Point(left + slotX + SLOT_CENTER_OFFSET, top + slotY + SLOT_CENTER_OFFSET);
  }

  public static int nearest(Point origin, List<Point> points) {
    int nearest = -1;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (int i = 0; i < points.size(); i++) {
      Point point = points.get(i);
      if (point == null) continue;
      double distance = origin.distanceSquared(point);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = i;
      }
    }
    return nearest;
  }

  public static int travelDelayMillis(
      Point from, Point to, List<Point> layout, int minimum, int maximum) {
    int min = Math.min(minimum, maximum);
    int max = Math.max(minimum, maximum);
    if (min == max || from.equals(to)) return min;

    double layoutDiameter = layoutDiameter(layout);
    if (layoutDiameter <= 0.0) return min;
    double distance = Math.sqrt(from.distanceSquared(to));
    double progress = Math.clamp(distance / layoutDiameter, 0.0, 1.0);
    return (int) Math.round(min + (max - min) * progress);
  }

  private static double layoutDiameter(List<Point> points) {
    if (points.isEmpty()) return 0.0;
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (Point point : points) {
      if (point == null) continue;
      minX = Math.min(minX, point.x);
      minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x);
      maxY = Math.max(maxY, point.y);
    }
    if (!Double.isFinite(minX)) return 0.0;
    return Math.hypot(maxX - minX, maxY - minY);
  }

  public record Point(double x, double y) {
    public double distanceSquared(Point other) {
      double xDistance = x - other.x;
      double yDistance = y - other.y;
      return xDistance * xDistance + yDistance * yDistance;
    }
  }
}
