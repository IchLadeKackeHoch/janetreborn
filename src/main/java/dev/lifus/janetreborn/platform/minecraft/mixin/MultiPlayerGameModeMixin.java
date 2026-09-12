package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.AttackEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
  @Unique private float janetReborn$useCameraYaw;
  @Unique private float janetReborn$useCameraPitch;
  @Unique private boolean janetReborn$usingServerRotation;
  @Unique private boolean janetReborn$usingEnderPearl;

  @Inject(method = "attack", at = @At("TAIL"))
  private void attack(Player player, Entity target, CallbackInfo ci) {
    JanetClient.instance().combat().noteVanillaAction();
    var minecraft = net.minecraft.client.Minecraft.getInstance();
    Vec3 hitPosition = target.getBoundingBox().getCenter();
    if (minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
      hitPosition = hit.getLocation();
    }
    JanetClient.instance().events().publish(new AttackEvent(target, hitPosition));
  }

  @Inject(
      method = {"startDestroyBlock", "continueDestroyBlock"},
      at = @At("HEAD"))
  private void prioritizeCameraRotationForBreaking(
      BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> cir) {
    var minecraft = net.minecraft.client.Minecraft.getInstance();
    if (minecraft.player == null) return;
    JanetClient.instance().combat().noteVanillaAction();
    JanetClient.instance().rotations().suspendArtificialRotation();
  }

  @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
  private void beginUseItemRotation(
      Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
    janetReborn$usingEnderPearl = player.getItemInHand(hand).is(Items.ENDER_PEARL);
    JanetClient.instance().combat().noteVanillaAction();
    var rotations = JanetClient.instance().rotations();
    if (rotations.isModuleInteractionActive()) {
      janetReborn$useCameraYaw = player.getYRot();
      janetReborn$useCameraPitch = player.getXRot();
      janetReborn$usingServerRotation = true;
      player.setYRot(rotations.getInteractionYaw());
      player.setXRot(rotations.getInteractionPitch());
    } else {
      if (rotations.hasPendingMovementRotation()
          && !rotations.pendingMovementRotationMatches(player.getYRot(), player.getXRot())) {
        cir.setReturnValue(InteractionResult.PASS);
        return;
      }
      rotations.suspendArtificialRotation();
    }
    JanetClient.instance().ownedFluids().beginUse(player, hand);
  }

  @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
  private void beginUseItemOnRotation(
      LocalPlayer player,
      InteractionHand hand,
      BlockHitResult hit,
      CallbackInfoReturnable<InteractionResult> cir) {
    JanetClient.instance().combat().noteVanillaAction();
    var rotations = JanetClient.instance().rotations();
    if (!rotations.isModuleInteractionActive()) {
      if (rotations.hasPendingMovementRotation()
          && !rotations.pendingMovementRotationMatches(player.getYRot(), player.getXRot())) {
        cir.setReturnValue(InteractionResult.PASS);
        return;
      }
      rotations.suspendArtificialRotation();
      return;
    }
    janetReborn$useCameraYaw = player.getYRot();
    janetReborn$useCameraPitch = player.getXRot();
    janetReborn$usingServerRotation = true;
    player.setYRot(rotations.getInteractionYaw());
    player.setXRot(rotations.getInteractionPitch());
  }

  @Inject(method = "useItem", at = @At("RETURN"))
  private void finishUseItemRotation(
      Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
    JanetClient.instance().ownedFluids().finishUse(cir.getReturnValue());
    if (janetReborn$usingEnderPearl && cir.getReturnValue().consumesAction()) {
      JanetClient.instance().teleports().noteEnderPearlUse();
    }
    janetReborn$usingEnderPearl = false;
    if (!janetReborn$usingServerRotation) return;
    player.setYRot(janetReborn$useCameraYaw);
    player.setXRot(janetReborn$useCameraPitch);
    janetReborn$usingServerRotation = false;
  }

  @Inject(method = "useItemOn", at = @At("RETURN"))
  private void finishUseItemOnRotation(
      LocalPlayer player,
      InteractionHand hand,
      BlockHitResult hit,
      CallbackInfoReturnable<InteractionResult> cir) {
    if (!janetReborn$usingServerRotation) return;
    player.setYRot(janetReborn$useCameraYaw);
    player.setXRot(janetReborn$useCameraPitch);
    janetReborn$usingServerRotation = false;
  }

  @Inject(method = "interact", at = @At("HEAD"))
  private void noteEntityInteraction(
      Player player,
      Entity target,
      EntityHitResult hit,
      InteractionHand hand,
      CallbackInfoReturnable<InteractionResult> cir) {
    JanetClient.instance().combat().noteVanillaAction();
  }

  @Inject(method = "releaseUsingItem", at = @At("HEAD"))
  private void noteReleaseUsingItem(Player player, CallbackInfo ci) {
    JanetClient.instance().combat().noteVanillaAction();
  }
}
