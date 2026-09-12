package dev.lifus.janetreborn.service.prediction;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;

final class ShadowPlayer extends AbstractClientPlayer {
  private float inputX;
  private float inputY;
  private float inputZ;
  private boolean jumpInput;

  ShadowPlayer(ClientLevel level, GameProfile profile) {
    super(level, profile);
    setInvisible(true);
    setSilent(true);
  }

  void mirror(Player source) {
    restoreFrom(source);
    inputX = source.xxa;
    inputY = source.yya;
    inputZ = source.zza;
    jumpInput = source.isJumping();

    noPhysics = source.isSpectator();
    setInvisible(true);
    setSilent(true);
    setOldPosAndRot();
  }

  void simulateMovementTick() {
    setOldPosAndRot();
    aiStep();
    tickCount++;
  }

  @Override
  protected void applyInput() {
    xxa = inputX;
    yya = inputY;
    zza = inputZ;
    setJumping(jumpInput);
  }

  @Override
  public boolean canSimulateMovement() {
    return true;
  }

  @Override
  public boolean isEffectiveAi() {
    return true;
  }

  @Override
  protected boolean isLocalClientAuthoritative() {
    return true;
  }

  @Override
  protected void pushEntities() {}

  @Override
  public void push(Entity entity) {}

  @Override
  public boolean isPushable() {
    return false;
  }

  @Override
  public boolean canCollideWith(Entity entity) {
    return false;
  }

  @Override
  public boolean canBeCollidedWith(Entity entity) {
    return false;
  }

  @Override
  protected MovementEmission getMovementEmission() {
    return MovementEmission.NONE;
  }

  @Override
  public boolean canSpawnSprintParticle() {
    return false;
  }

  @Override
  protected void spawnSprintParticle() {}

  @Override
  public void playSound(SoundEvent sound, float volume, float pitch) {}

  @Override
  public void playSound(SoundEvent sound) {}

  @Override
  public void gameEvent(Holder<GameEvent> event, Entity source) {}

  @Override
  public void gameEvent(Holder<GameEvent> event) {}

  @Override
  public boolean shouldRenderAtSqrDistance(double distance) {
    return false;
  }
}
