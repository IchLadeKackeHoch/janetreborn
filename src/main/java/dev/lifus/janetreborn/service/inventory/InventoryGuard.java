package dev.lifus.janetreborn.service.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

public final class InventoryGuard {
  private InventoryGuard() {}

  public static boolean mayClick(LocalPlayer player, boolean requireStationary) {
    if (hasServerMovementConflict(player.getLastSentInput(), player.isSprinting())) return false;
    if (!requireStationary) return true;

    var keys = player.input.keyPresses;
    var velocity = player.getDeltaMovement();
    return !keys.forward()
        && !keys.backward()
        && !keys.left()
        && !keys.right()
        && !keys.jump()
        && velocity.horizontalDistanceSqr() <= 1.0E-4
        && (player.onGround() || Math.abs(velocity.y) <= 1.0E-3);
  }

  static boolean hasServerMovementConflict(Input input, boolean sprinting) {
    return sprinting
        || input.forward()
        || input.backward()
        || input.left()
        || input.right()
        || input.jump()
        || input.sprint();
  }
}
