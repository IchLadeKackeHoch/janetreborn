package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.MovementInputEvent;
import dev.lifus.janetreborn.service.rotation.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
  @Unique private float janetReborn$cameraYaw;
  @Unique private float janetReborn$cameraPitch;
  @Unique private float janetReborn$cameraYawBob;
  @Unique private float janetReborn$cameraPitchBob;
  @Unique private boolean janetReborn$rotating;

  @Inject(
      method = "aiStep",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/player/ClientInput;tick()V",
              shift = At.Shift.AFTER))
  private void beginServerRotation(CallbackInfo ci) {
    LocalPlayer player = (LocalPlayer) (Object) this;
    MovementInputEvent movementInput = new MovementInputEvent(player, player.input.keyPresses);
    JanetClient.instance().events().publish(movementInput);
    Input input = movementInput.input();
    if (input != player.input.keyPresses) {
      player.input.keyPresses = input;
      janetReborn$updateMoveVector(player, input);
    }

    Rotations rotations = JanetClient.instance().rotations();
    janetReborn$rotating = false;
    janetReborn$cameraYaw = player.getYRot();
    janetReborn$cameraPitch = player.getXRot();
    rotations.prepare(janetReborn$cameraYaw, janetReborn$cameraPitch);
    if (!rotations.isDirty()) return;

    janetReborn$rotating = true;
    janetReborn$cameraYawBob = player.yBob;
    janetReborn$cameraPitchBob = player.xBob;
    Input fixed = rotations.fixMovement(player.input.keyPresses, janetReborn$cameraYaw);
    player.input.keyPresses = fixed;
    janetReborn$updateMoveVector(player, fixed);
    player.setYRot(rotations.getServerYaw());
    player.setXRot(rotations.getServerPitch());
  }

  @Inject(method = "tick", at = @At("RETURN"))
  private void finishServerRotation(CallbackInfo ci) {
    if (!janetReborn$rotating) return;
    LocalPlayer player = (LocalPlayer) (Object) this;
    player.setYRot(janetReborn$cameraYaw);
    player.setXRot(janetReborn$cameraPitch);
    player.yBobO = janetReborn$cameraYawBob;
    player.xBobO = janetReborn$cameraPitchBob;
    player.yBob =
        janetReborn$cameraYawBob + (janetReborn$cameraYaw - janetReborn$cameraYawBob) * 0.5f;
    player.xBob =
        janetReborn$cameraPitchBob + (janetReborn$cameraPitch - janetReborn$cameraPitchBob) * 0.5f;
    janetReborn$rotating = false;
  }

  @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
  private void validateMovementPacket(CallbackInfo ci) {
    if (!janetReborn$rotating) return;
    LocalPlayer player = (LocalPlayer) (Object) this;
    Rotations rotations = JanetClient.instance().rotations();
    if (rotations.validatePacketState(
        player.position(),
        player.getYRot(),
        player.getXRot(),
        player.onGround(),
        player.horizontalCollision)) return;
    rotations.recoverFromInvalidPacket(janetReborn$cameraYaw, janetReborn$cameraPitch);
    player.setYRot(rotations.getServerYaw());
    player.setXRot(rotations.getServerPitch());
    ci.cancel();
  }

  @Inject(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z",
              ordinal = 0),
      cancellable = true)
  private void validatePassengerPacket(CallbackInfo ci) {
    if (!janetReborn$rotating) return;
    LocalPlayer player = (LocalPlayer) (Object) this;
    if (!player.isPassenger()) return;
    Rotations rotations = JanetClient.instance().rotations();
    if (rotations.validatePacketState(
        player.position(),
        player.getYRot(),
        player.getXRot(),
        player.onGround(),
        player.horizontalCollision)) return;
    rotations.recoverFromInvalidPacket(janetReborn$cameraYaw, janetReborn$cameraPitch);
    player.setYRot(rotations.getServerYaw());
    player.setXRot(rotations.getServerPitch());
    if (rotations.validatePacketState(
        player.position(),
        player.getYRot(),
        player.getXRot(),
        player.onGround(),
        player.horizontalCollision)) return;
    player.setYRot(janetReborn$cameraYaw);
    player.setXRot(janetReborn$cameraPitch);
    janetReborn$rotating = false;
    ci.cancel();
  }

  @Unique
  private static void janetReborn$updateMoveVector(LocalPlayer player, Input input) {
    int forward = bool(input.forward()) - bool(input.backward());
    int strafe = bool(input.left()) - bool(input.right());
    ((ClientInputAccessor) player.input)
        .janetReborn$setMoveVector(new Vec2(strafe, forward).normalized());
  }

  @Unique
  private static int bool(boolean value) {
    return value ? 1 : 0;
  }
}
