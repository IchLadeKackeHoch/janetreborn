package dev.lifus.janetreborn.feature.combat;

import dev.lifus.janetreborn.feature.combat.aim.AimActivationPolicy;
import dev.lifus.janetreborn.feature.combat.aim.AimController;
import dev.lifus.janetreborn.feature.combat.aim.AimIntentDetector;
import dev.lifus.janetreborn.feature.combat.aim.AimIntentDetector.Disengagement;
import dev.lifus.janetreborn.feature.combat.aim.AimMath;
import dev.lifus.janetreborn.feature.combat.aim.AimRotation;
import dev.lifus.janetreborn.feature.combat.aim.AimTargetSelector;
import dev.lifus.janetreborn.feature.combat.aim.AimTargetSelector.Candidate;
import dev.lifus.janetreborn.feature.combat.aim.VisibleHitPointSelector;
import dev.lifus.janetreborn.feature.combat.aim.VisibleHitPointSelector.HitPoint;
import dev.lifus.janetreborn.service.friend.FriendStore;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class AimAssistModule extends Module {
  private static final long REACQUIRE_DELAY_NANOS = 250_000_000L;
  private static final double ACQUISITION_RANGE_MARGIN = 1.0;
  private static final float PLAYER_TURN_SCALE = 0.15f;

  private final Minecraft minecraft;
  private final TriggerbotModule triggerbot;
  private final AimController controller = new AimController();
  private final AimIntentDetector intent = new AimIntentDetector();
  private final AimTargetSelector targets;
  private final NumberSetting<Float> strength =
      add(new NumberSetting<>("strength", "Assist Strength", 65.0f, 1.0f, 100.0f, 1.0f).unit("%"));
  private final NumberSetting<Float> acquisitionFov =
      add(
          new NumberSetting<>("Acquisition FOV", 45.0f, 5.0f, 90.0f, 1.0f)
              .unit("°")
              .description("Maximum angle from the crosshair when acquiring a new target."));
  private final ModeSetting<Activation> activation =
      add(new ModeSetting<>("Activation", Activation.BOTH));

  private LivingEntity target;
  private HitPoint hitPoint;
  private long lastFrameNanos;
  private long reacquireAtNanos;
  private float lastOutputYaw;
  private float lastOutputPitch;
  private float lastTargetError;
  private boolean hasLastOutput;
  private boolean skipNextAdjustment;

  public AimAssistModule(Minecraft minecraft, FriendStore friends, TriggerbotModule triggerbot) {
    super(
        "Aim Assist",
        "Gently guides the camera while attacking manually or with TriggerbotModule",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.triggerbot = Objects.requireNonNull(triggerbot, "triggerbot");
    VisibleHitPointSelector hitPoints = new VisibleHitPointSelector(minecraft);
    targets = new AimTargetSelector(minecraft, friends, hitPoints);
  }

  @Override
  protected void onUpdate() {
    if (!ready()) {
      clearTarget(false);
      return;
    }
    long now = System.nanoTime();
    if (now < reacquireAtNanos) return;
    Candidate selected = targets.select(target, hitPoint, targetRange(), acquisitionFov.getValue());
    if (selected == null) {
      clearTarget(false);
      return;
    }
    updateSelection(selected, now);
  }

  @Override
  protected void onDisable() {
    clearTarget(false);
    reacquireAtNanos = 0L;
  }

  public void updateCamera() {
    if (!isEnabled() || target == null || minecraft.player == null) return;
    long now = System.nanoTime();
    if (!ready()) {
      clearTarget(false);
      return;
    }

    Candidate current = targets.evaluateCurrent(target, hitPoint, targetRange());
    if (current == null) {
      clearTarget(true);
      return;
    }
    hitPoint = current.hitPoint();
    AimRotation desiredRotation = current.rotation();

    float cameraYaw = minecraft.player.getYRot();
    float cameraPitch = minecraft.player.getXRot();
    float currentError = AimMath.angularDistance(cameraYaw, cameraPitch, desiredRotation);
    if (hasLastOutput) {
      float manualYaw = AimMath.wrapDegrees(cameraYaw - lastOutputYaw);
      float manualPitch = cameraPitch - lastOutputPitch;
      Disengagement decision =
          intent.evaluate(manualYaw, manualPitch, lastTargetError, currentError);
      if (decision != Disengagement.NONE) {
        clearTarget(true);
        return;
      }
    }

    if (skipNextAdjustment) {
      skipNextAdjustment = false;
      rememberOutput(cameraYaw, cameraPitch, currentError, now);
      return;
    }
    float deltaSeconds =
        lastFrameNanos == 0L ? 1.0f / 60.0f : (now - lastFrameNanos) / 1_000_000_000.0f;
    AimRotation adjusted =
        controller.step(cameraYaw, cameraPitch, desiredRotation, deltaSeconds, strength.getValue());
    minecraft.player.turn(
        AimMath.wrapDegrees(adjusted.yaw() - cameraYaw) / PLAYER_TURN_SCALE,
        (adjusted.pitch() - cameraPitch) / PLAYER_TURN_SCALE);
    float outputYaw = minecraft.player.getYRot();
    float outputPitch = minecraft.player.getXRot();
    rememberOutput(
        outputYaw,
        outputPitch,
        AimMath.angularDistance(outputYaw, outputPitch, desiredRotation),
        now);
  }

  private void updateSelection(Candidate selected, long now) {
    boolean changed = selected.entity() != target;
    target = selected.entity();
    hitPoint = selected.hitPoint();
    if (!changed) return;
    hasLastOutput = false;
    skipNextAdjustment = true;
    lastFrameNanos = now;
  }

  private void rememberOutput(float yaw, float pitch, float error, long now) {
    lastOutputYaw = yaw;
    lastOutputPitch = pitch;
    lastTargetError = error;
    lastFrameNanos = now;
    hasLastOutput = true;
  }

  private boolean ready() {
    if (minecraft.player == null
        || minecraft.level == null
        || minecraft.gameMode == null
        || minecraft.gui.screen() != null
        || !minecraft.player.isAlive()
        || minecraft.player.isDeadOrDying()
        || minecraft.gameMode.isSpectator()) return false;
    return switch (activation.getValue()) {
      case MANUAL -> minecraft.options.keyAttack.isDown();
      case TRIGGERBOT -> triggerbot.isEnabled();
      case BOTH ->
          AimActivationPolicy.shouldAssist(
              minecraft.options.keyAttack.isDown(), triggerbot.isEnabled());
    };
  }

  private float targetRange() {
    return (float) (minecraft.player.entityInteractionRange() + ACQUISITION_RANGE_MARGIN);
  }

  private void clearTarget(boolean playerDisengaged) {
    target = null;
    hitPoint = null;
    hasLastOutput = false;
    skipNextAdjustment = false;
    lastFrameNanos = 0L;
    if (playerDisengaged) reacquireAtNanos = System.nanoTime() + REACQUIRE_DELAY_NANOS;
  }

  private enum Activation {
    MANUAL,
    TRIGGERBOT,
    BOTH
  }
}
