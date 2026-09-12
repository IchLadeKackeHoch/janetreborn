package dev.lifus.janetreborn.feature.player;

import dev.codeman.eventbusx.Priority;
import dev.lifus.janetreborn.event.game.MovementInputEvent;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class FlyModule extends Module {
  private static final double MINIMUM_DIRECTION_SQUARED = 1.0E-6;

  private final Minecraft minecraft;
  private final ModeSetting<Mode> mode = add(new ModeSetting<>("Mode", Mode.CREATIVE));
  private final NumberSetting<Double> horizontalSpeed =
      add(new NumberSetting<>("Horizontal Speed", 0.32, 0.05, 1.0, 0.01).unit("blocks/tick"));
  private final NumberSetting<Double> verticalSpeed =
      add(new NumberSetting<>("Vertical Speed", 0.32, 0.05, 1.0, 0.01).unit("blocks/tick"));
  private LocalPlayer trackedPlayer;
  private ClientLevel trackedLevel;
  private FlightSnapshot snapshot;
  private Mode appliedMode;

  public FlyModule(Minecraft minecraft) {
    super("Fly", "Provides Creative-style or controlled-motion flight", Category.PLAYER);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    horizontalSpeed.visibleWhen(() -> mode.getValue() == Mode.MOTION_FLY);
    verticalSpeed.visibleWhen(() -> mode.getValue() == Mode.MOTION_FLY);
  }

  @Override
  protected void onInitialize() {
    listen(MovementInputEvent.class, Priority.HIGHEST, this::onMovementInput);
    listen(ServerTeleportEvent.class, ignored -> suspend());
  }

  @Override
  protected void onEnable() {
    synchronizeWorld();
  }

  @Override
  protected void onUpdate() {
    synchronizeWorld();
    LocalPlayer player = minecraft.player;
    if (!canFlyNormally(player)) {
      suspend();
      return;
    }
    applyMode(player, mode.getValue());
  }

  @Override
  protected void onDisable() {
    suspend();
    trackedPlayer = null;
    trackedLevel = null;
  }

  private void onMovementInput(MovementInputEvent event) {
    LocalPlayer player = event.player();
    synchronizeWorld();
    if (player != minecraft.player || event.isClaimed() || !canFlyNormally(player)) {
      suspend();
      return;
    }
    if (!event.claim(this, event.input())) {
      suspend();
      return;
    }

    Mode selected = mode.getValue();
    applyMode(player, selected);
    if (selected == Mode.MOTION_FLY) applyMotion(player, event.input());
  }

  private void applyMode(LocalPlayer player, Mode selected) {
    if (snapshot != null && appliedMode == selected && trackedPlayer == player) return;
    if (snapshot != null) restoreTrackedPlayer();
    trackedPlayer = player;
    trackedLevel = minecraft.level;
    snapshot = FlightSnapshot.capture(access(player));
    appliedMode = selected;
    if (selected == Mode.CREATIVE) {
      player.getAbilities().mayfly = true;
      player.getAbilities().flying = true;
    } else {
      player.getAbilities().flying = false;
    }
  }

  private void applyMotion(LocalPlayer player, Input input) {
    int forward = axis(input.forward(), input.backward());
    int strafe = axis(input.left(), input.right());
    double horizontalX = 0;
    double horizontalZ = 0;
    if (forward != 0 || strafe != 0) {
      double radians = Math.toRadians(player.getYRot());
      horizontalX = -Math.sin(radians) * forward + Math.cos(radians) * strafe;
      horizontalZ = Math.cos(radians) * forward + Math.sin(radians) * strafe;
      double lengthSquared = horizontalX * horizontalX + horizontalZ * horizontalZ;
      if (lengthSquared > MINIMUM_DIRECTION_SQUARED) {
        double scale = horizontalSpeed.getValue() / Math.sqrt(lengthSquared);
        horizontalX *= scale;
        horizontalZ *= scale;
      }
    }
    double vertical = axis(input.jump(), input.shift()) * verticalSpeed.getValue();
    player.setDeltaMovement(horizontalX, vertical, horizontalZ);
    player.fallDistance = 0;
  }

  private boolean canFlyNormally(LocalPlayer player) {
    return player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.gui.screen() == null
        && minecraft.gui.overlay() == null
        && minecraft.mouseHandler.isMouseGrabbed()
        && player.isAlive()
        && !player.isDeadOrDying()
        && !player.isSleeping()
        && !player.isPassenger()
        && !player.isFallFlying()
        && !player.isMobilityRestricted()
        && !player.isHandsBusy()
        && !minecraft.gameMode.isSpectator();
  }

  private void synchronizeWorld() {
    if (trackedPlayer == minecraft.player && trackedLevel == minecraft.level) return;
    restoreTrackedPlayer();
    trackedPlayer = minecraft.player;
    trackedLevel = minecraft.level;
  }

  private void suspend() {
    restoreTrackedPlayer();
  }

  private void restoreTrackedPlayer() {
    if (snapshot != null && trackedPlayer != null) snapshot.restore(access(trackedPlayer));
    snapshot = null;
    appliedMode = null;
  }

  private static FlightSnapshot.Access access(LocalPlayer player) {
    return new FlightSnapshot.Access() {
      @Override
      public boolean mayFly() {
        return player.getAbilities().mayfly;
      }

      @Override
      public boolean flying() {
        return player.getAbilities().flying;
      }

      @Override
      public float flyingSpeed() {
        return player.getAbilities().getFlyingSpeed();
      }

      @Override
      public void setMayFly(boolean value) {
        player.getAbilities().mayfly = value;
      }

      @Override
      public void setFlying(boolean value) {
        player.getAbilities().flying = value;
      }

      @Override
      public void setFlyingSpeed(float value) {
        player.getAbilities().setFlyingSpeed(value);
      }

      @Override
      public void clearMotion() {
        player.setDeltaMovement(Vec3.ZERO);
      }

      @Override
      public void clearFallDistance() {
        player.fallDistance = 0;
      }
    };
  }

  private static int axis(boolean positive, boolean negative) {
    return (positive ? 1 : 0) - (negative ? 1 : 0);
  }

  public enum Mode {
    CREATIVE("Creative"),
    MOTION_FLY("MotionFly");

    private final String label;

    Mode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
