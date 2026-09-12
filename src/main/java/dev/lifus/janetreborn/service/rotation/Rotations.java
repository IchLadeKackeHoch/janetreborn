package dev.lifus.janetreborn.service.rotation;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

public final class Rotations {
  private static final Object DEFAULT_OWNER = new Object();
  private static final float ANGLE_EPSILON = 1.0E-3f;
  private final Map<Object, RotationRequest> requests = new IdentityHashMap<>();
  private Configuration configuration = () -> 0.0;
  private Object activeOwner;
  private Object interactionOwner;
  private float interactionYaw;
  private float interactionPitch;
  private RotationRequest active;
  private long tick;
  private long sequence;
  private long preparedTick = Long.MIN_VALUE;
  private long lastPacketTick = Long.MIN_VALUE;
  private PacketState lastPacketState;
  private boolean initialized;
  private boolean artificialRotationSuspended;
  private boolean lockNextRotationTick;
  private float lockedYaw;
  private float lockedPitch;
  private boolean rotationLockedThisTick;
  private boolean rotationDirtyThisTick;
  private float serverYaw;
  private float serverPitch;
  private float previousServerYaw;
  private float previousServerPitch;

  public void tick() {
    tick++;
    artificialRotationSuspended = false;
    rotationLockedThisTick = false;
    rotationDirtyThisTick = false;
    if (requests.entrySet().removeIf(entry -> tick > entry.getValue().expiresAt)) selectActive();
  }

  public void suspendArtificialRotation() {
    artificialRotationSuspended = true;
    if (lockNextRotationTick) return;
    rotationLockedThisTick = false;
    rotationDirtyThisTick = false;
  }

  public void lockCurrentRotationForNextTick() {
    if (initialized && validRotation(serverYaw, serverPitch)) {
      lockedYaw = serverYaw;
      lockedPitch = serverPitch;
      lockNextRotationTick = true;
    }
  }

  public boolean lockRequestedRotationForNextTick(Object owner) {
    if (!isInteractionReady(owner)) return false;
    lockedYaw = requestedYaw();
    lockedPitch = active.pitch;
    lockNextRotationTick = true;
    return true;
  }

  public boolean hasPendingMovementRotation() {
    return lockNextRotationTick;
  }

  public boolean pendingMovementRotationMatches(float yaw, float pitch) {
    return !lockNextRotationTick || yaw == lockedYaw && pitch == lockedPitch;
  }

  public void setRotation(float yaw, float pitch) {
    request(DEFAULT_OWNER, yaw, pitch, 0, Integer.MAX_VALUE);
  }

  public void clearRotation() {
    clear(DEFAULT_OWNER);
  }

  public boolean request(Object owner, float yaw, float pitch, int priority, int keepTicks) {
    Objects.requireNonNull(owner, "owner");
    if (!validRotation(yaw, pitch)) {
      clear(owner);
      return false;
    }
    long expiresAt =
        keepTicks == Integer.MAX_VALUE ? Long.MAX_VALUE : tick + Math.max(1, keepTicks);
    requests.put(
        owner,
        new RotationRequest(wrapDegrees(yaw), clampPitch(pitch), priority, expiresAt, ++sequence));
    selectActive();
    return activeOwner == owner;
  }

  public void clear(Object owner) {
    if (requests.remove(owner) != null) selectActive();
  }

  public void setConfiguration(Configuration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  public double getHitVectorVariation() {
    double configured = configuration.hitVectorVariation();
    return Double.isFinite(configured) ? Math.max(0.0, Math.min(0.2, configured)) : 0.0;
  }

  public long getVariationEpoch() {
    return tick / 10L;
  }

  public boolean isActive() {
    return active != null;
  }

  public boolean isActive(Object owner) {
    return activeOwner == owner;
  }

  public <T> T runInteraction(Object owner, float yaw, float pitch, Supplier<T> interaction) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(interaction, "interaction");
    if (!validRotation(yaw, pitch))
      throw new IllegalArgumentException("Invalid interaction rotation");
    Object previousOwner = interactionOwner;
    float previousYaw = interactionYaw;
    float previousPitch = interactionPitch;
    interactionOwner = owner;
    if (isInteractionReady(owner)) {
      interactionYaw = requestedYaw();
      interactionPitch = active.pitch;
    } else {
      float normalizedYaw = wrapDegrees(yaw);
      interactionYaw = initialized ? equivalentYawNear(normalizedYaw, serverYaw) : normalizedYaw;
      interactionPitch = clampPitch(pitch);
    }
    try {
      return interaction.get();
    } finally {
      interactionOwner = previousOwner;
      interactionYaw = previousYaw;
      interactionPitch = previousPitch;
    }
  }

  public boolean isModuleInteractionActive() {
    return interactionOwner != null;
  }

  public float getInteractionYaw() {
    return interactionYaw;
  }

  public float getInteractionPitch() {
    return interactionPitch;
  }

  public boolean isArtificialRotationActive() {
    return active != null && !artificialRotationSuspended || rotationLockedThisTick;
  }

  public boolean isDirty() {
    return rotationDirtyThisTick;
  }

  public float getServerYaw() {
    return serverYaw;
  }

  public float getServerPitch() {
    return serverPitch;
  }

  @Deprecated
  public float getYaw() {
    return getServerYaw();
  }

  @Deprecated
  public float getPitch() {
    return getServerPitch();
  }

  public boolean isAtTarget(Object owner) {
    return activeOwner == owner
        && active != null
        && initialized
        && !artificialRotationSuspended
        && Math.abs(wrapDegrees(active.yaw - serverYaw)) <= ANGLE_EPSILON
        && Math.abs(active.pitch - serverPitch) <= ANGLE_EPSILON;
  }

  public boolean isInteractionReady(Object owner) {
    return initialized && activeOwner == owner && active != null && !artificialRotationSuspended;
  }

  public float getRequestedYaw(Object owner) {
    if (!isInteractionReady(owner))
      throw new IllegalStateException("Owner has no interaction-ready rotation");
    return requestedYaw();
  }

  public float getRequestedPitch(Object owner) {
    if (!isInteractionReady(owner))
      throw new IllegalStateException("Owner has no interaction-ready rotation");
    return active.pitch;
  }

  public int estimateTicksTo(float yaw, float pitch) {
    return validRotation(yaw, pitch) ? 1 : Integer.MAX_VALUE;
  }

  public void prepare(float cameraYaw, float cameraPitch) {
    float safeCameraYaw =
        Float.isFinite(cameraYaw)
            ? cameraYaw
            : initialized && Float.isFinite(serverYaw) ? serverYaw : 0.0f;
    float safeCameraPitch =
        Float.isFinite(cameraPitch)
            ? clampPitch(cameraPitch)
            : initialized && Float.isFinite(serverPitch) ? serverPitch : 0.0f;
    if (!initialized) {
      initialized = true;
      serverYaw = safeCameraYaw;
      serverPitch = safeCameraPitch;
    }
    previousServerYaw = serverYaw;
    previousServerPitch = serverPitch;
    rotationLockedThisTick = false;
    rotationDirtyThisTick = false;

    if (lockNextRotationTick) {
      lockNextRotationTick = false;
      rotationLockedThisTick = true;
      rotationDirtyThisTick = true;
      serverYaw = lockedYaw;
      serverPitch = lockedPitch;
    } else {
      lockNextRotationTick = false;
      boolean artificial = active != null && !artificialRotationSuspended;
      rotationDirtyThisTick = artificial;
      serverYaw = artificial ? equivalentYawNear(active.yaw, serverYaw) : cameraYaw;
      serverPitch = artificial ? active.pitch : cameraPitch;
    }
    if (!validRotation(serverYaw, serverPitch)) {
      serverYaw = safeCameraYaw;
      serverPitch = safeCameraPitch;
      rotationLockedThisTick = false;
      rotationDirtyThisTick = false;
    }
    if (rotationDirtyThisTick) {
      serverPitch = clampPitch(serverPitch);
    }
    preparedTick = tick;
  }

  public boolean validatePacketState(
      Vec3 actualPosition,
      float actualYaw,
      float actualPitch,
      boolean onGround,
      boolean horizontalCollision) {
    if (!rotationDirtyThisTick) return true;
    if (preparedTick != tick
        || lastPacketTick == tick
        || actualPosition == null
        || !Double.isFinite(actualPosition.x)
        || !Double.isFinite(actualPosition.y)
        || !Double.isFinite(actualPosition.z)
        || !validRotation(actualYaw, actualPitch)) return false;
    if (Math.abs(wrapDegrees(actualYaw - serverYaw)) > ANGLE_EPSILON
        || Math.abs(actualPitch - serverPitch) > ANGLE_EPSILON) return false;
    lastPacketTick = tick;
    lastPacketState =
        new PacketState(
            actualPosition, actualYaw, actualPitch, onGround, horizontalCollision, tick);
    return true;
  }

  public void recoverFromInvalidPacket(float cameraYaw, float cameraPitch) {
    requests.clear();
    active = null;
    activeOwner = null;
    lockNextRotationTick = false;
    rotationLockedThisTick = false;
    rotationDirtyThisTick = false;
    serverYaw = Float.isFinite(cameraYaw) ? cameraYaw : 0.0f;
    serverPitch = Float.isFinite(cameraPitch) ? clampPitch(cameraPitch) : 0.0f;
    previousServerYaw = serverYaw;
    previousServerPitch = serverPitch;
    preparedTick = tick;
  }

  public float getVisibleYaw(float partialTick) {
    float progress = clamp01(partialTick);
    return wrapDegrees(previousServerYaw + wrapDegrees(serverYaw - previousServerYaw) * progress);
  }

  public float getVisiblePitch(float partialTick) {
    return previousServerPitch + (serverPitch - previousServerPitch) * clamp01(partialTick);
  }

  @Deprecated
  public float getVisualYaw(float partialTick) {
    return getVisibleYaw(partialTick);
  }

  @Deprecated
  public float getVisualPitch(float partialTick) {
    return getVisiblePitch(partialTick);
  }

  public Input fixMovement(Input input, float cameraYaw) {
    if (!rotationDirtyThisTick || !Float.isFinite(cameraYaw)) return input;
    int forward = bool(input.forward()) - bool(input.backward());
    int strafe = bool(input.left()) - bool(input.right());
    if (forward == 0 && strafe == 0) return input;
    float intendedAngle =
        wrapDegrees(cameraYaw + (float) Math.toDegrees(Math.atan2(-strafe, forward)));
    int bestForward = 0;
    int bestStrafe = 0;
    float bestDifference = Float.MAX_VALUE;
    for (int candidateForward = -1; candidateForward <= 1; candidateForward++) {
      for (int candidateStrafe = -1; candidateStrafe <= 1; candidateStrafe++) {
        if (candidateForward == 0 && candidateStrafe == 0) continue;
        float angle =
            wrapDegrees(
                serverYaw + (float) Math.toDegrees(Math.atan2(-candidateStrafe, candidateForward)));
        float difference = Math.abs(wrapDegrees(intendedAngle - angle));
        if (difference < bestDifference) {
          bestDifference = difference;
          bestForward = candidateForward;
          bestStrafe = candidateStrafe;
        }
      }
    }
    return new Input(
        bestForward > 0,
        bestForward < 0,
        bestStrafe > 0,
        bestStrafe < 0,
        input.jump(),
        input.shift(),
        input.sprint());
  }

  public static boolean validRotation(float yaw, float pitch) {
    return Float.isFinite(yaw) && Float.isFinite(pitch) && pitch >= -90.0f && pitch <= 90.0f;
  }

  public static float wrapDegrees(float value) {
    if (!Float.isFinite(value)) return 0.0f;
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }

  public static float clampPitch(float pitch) {
    return Float.isFinite(pitch) ? Math.max(-90.0f, Math.min(90.0f, pitch)) : 0.0f;
  }

  public static float equivalentYawNear(float yaw, float referenceYaw) {
    if (!Float.isFinite(yaw) || !Float.isFinite(referenceYaw)) return 0.0f;
    return referenceYaw + wrapDegrees(yaw - referenceYaw);
  }

  public PacketState getLastPacketState() {
    return lastPacketState;
  }

  private void selectActive() {
    var selected =
        requests.entrySet().stream()
            .max(
                Comparator.<Map.Entry<Object, RotationRequest>>comparingInt(
                        entry -> entry.getValue().priority)
                    .thenComparingLong(entry -> entry.getValue().sequence))
            .orElse(null);
    activeOwner = selected == null ? null : selected.getKey();
    active = selected == null ? null : selected.getValue();
  }

  private float requestedYaw() {
    return equivalentYawNear(active.yaw, serverYaw);
  }

  private static float clamp01(float value) {
    return Math.max(0.0f, Math.min(1.0f, value));
  }

  private static int bool(boolean value) {
    return value ? 1 : 0;
  }

  private record RotationRequest(
      float yaw, float pitch, int priority, long expiresAt, long sequence) {}

  public record PacketState(
      Vec3 position,
      float yaw,
      float pitch,
      boolean onGround,
      boolean horizontalCollision,
      long tick) {}

  public interface Configuration {
    double hitVectorVariation();
  }
}
