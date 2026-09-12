package dev.lifus.janetreborn.feature.combat.anchor;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.placement.PlacementPlan;
import dev.lifus.janetreborn.service.rotation.Rotations;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class AnchorAssistModule extends Module {
  private static final int COORDINATOR_PRIORITY = 120;
  private static final int CONFIRMATION_TIMEOUT_TICKS = 12;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final Placement placements;
  private final Hotbar hotbar;
  private final CombatGate combat;
  private final AnchorSequence sequence = new AnchorSequence();
  private final Set<BlockPos> ownedAnchors = new HashSet<>();
  private final ModeSetting<AnchorMode> mode =
      add(new ModeSetting<>("Mode", AnchorMode.CHARGE_AND_DETONATE));
  private final BoolSetting onlyOwned = add(new BoolSetting("Only Owned Anchors", false));
  private final RangeSetting<Integer> switchDelay =
      add(
          new RangeSetting<>("Switch Delay", new Range<>(1, 2), 0, 10, 1)
              .unit("ticks")
              .markAdvanced());
  private final RangeSetting<Integer> chargeDelay =
      add(
          new RangeSetting<>("Charge Delay", new Range<>(1, 3), 0, 10, 1)
              .unit("ticks")
              .markAdvanced());
  private final RangeSetting<Integer> detonateDelay =
      add(
          new RangeSetting<>("Detonate Delay", new Range<>(1, 3), 0, 10, 1)
              .unit("ticks")
              .markAdvanced());
  private final NumberSetting<Integer> cooldown =
      add(new NumberSetting<>("Cooldown", 6, 0, 30, 1).unit("ticks").markAdvanced());
  private final ModeSetting<DetonationItem> detonationItem =
      add(new ModeSetting<>("Detonation Item", DetonationItem.AUTO));
  private final NumberSetting<Integer> detonationSlot =
      add(new NumberSetting<>("Detonation Slot", 1, 1, 9, 1));
  private final BoolSetting restorePrevious = add(new BoolSetting("Restore Previous Slot", true));
  private final BoolSetting allowDuringItemUse =
      add(new BoolSetting("Allow During Item Use", false));
  private final NumberSetting<Double> minimumHealth =
      add(new NumberSetting<>("Minimum Health", 10.0, 1.0, 20.0, 0.5).unit("HP"));
  private BlockPos target;
  private BlockPos pendingOwned;
  private long pendingOwnedExpiresAt;
  private int expectedCharge;
  private long tick;
  private boolean inputReleased = true;
  private ResourceKey<Level> dimension;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<TickEvent> clientTick = this::maintain;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<PostInputEvent> postInput = this::act;

  @Subscribe private final Listener<ServerTeleportEvent> teleport = ignored -> reset();

  public AnchorAssistModule(
      Minecraft minecraft,
      Rotations rotations,
      Placement placements,
      Hotbar hotbar,
      CombatGate combat) {
    super("AnchorAssist", "Assists deliberate respawn-anchor sequences", Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.placements = Objects.requireNonNull(placements, "placements");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.combat = Objects.requireNonNull(combat, "combat");
    detonateDelay.visibleWhen(() -> mode.getValue() != AnchorMode.CHARGE_ONLY);
    detonationItem.visibleWhen(() -> mode.getValue() != AnchorMode.CHARGE_ONLY);
    detonationSlot.visibleWhen(
        () ->
            mode.getValue() != AnchorMode.CHARGE_ONLY
                && detonationItem.getValue() == DetonationItem.FIXED_SLOT);
  }

  @Override
  protected void onDisable() {
    reset();
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!connected()) {
      reset();
      return;
    }
    if (dimension != null && !dimension.equals(minecraft.level.dimension())) {
      reset();
      dimension = minecraft.level.dimension();
      return;
    }
    dimension = minecraft.level.dimension();
    boolean activeBeforeTick = sequence.active();
    sequence.tick(tick);
    if (activeBeforeTick && sequence.phase() == AnchorSequence.Phase.IDLE) {
      abort();
      return;
    }
    observeOwnedPlacement();
    ownedAnchors.removeIf(
        position ->
            minecraft.level.isLoaded(position)
                && !minecraft.level.getBlockState(position).is(Blocks.RESPAWN_ANCHOR));

    boolean useDown = minecraft.options.keyUse.isDown();
    if (!useDown) inputReleased = true;
    if (sequence.active()
        && sequence.phase() != AnchorSequence.Phase.DETONATE_CONFIRMATION
        && sequence.phase() != AnchorSequence.Phase.REPLACE_DELAY
        && !sameAnchorTarget()) {
      abort();
      return;
    }
    if (sequence.phase() == AnchorSequence.Phase.CHARGE_CONFIRMATION
        && anchorCharge(target) >= expectedCharge) {
      sequence.chargeConfirmed(
          tick,
          mode.getValue() == AnchorMode.CHARGE_ONLY,
          delay(switchDelay),
          cooldown.getValue(),
          CONFIRMATION_TIMEOUT_TICKS);
    }
    if (sequence.phase() == AnchorSequence.Phase.DETONATE_CONFIRMATION
        && !minecraft.level.getBlockState(target).is(Blocks.RESPAWN_ANCHOR)) {
      ownedAnchors.remove(target);
      sequence.detonationConfirmed(
          tick,
          mode.getValue() == AnchorMode.DETONATE_AND_REPLACE,
          delay(switchDelay),
          cooldown.getValue(),
          CONFIRMATION_TIMEOUT_TICKS);
    }
    if (!sequence.active()) releaseControl();
  }

  private void act(PostInputEvent ignored) {
    if (!ready()) {
      if (sequence.active()) abort();
      return;
    }
    if (sequence.phase() == AnchorSequence.Phase.IDLE) {
      beginFromCrosshair();
      return;
    }
    if (!minecraft.options.keyUse.isDown()) {
      abort();
      return;
    }
    switch (sequence.phase()) {
      case CHARGE_SWITCH_DELAY -> switchForCharge();
      case CHARGE_DELAY -> charge();
      case DETONATE_SWITCH_DELAY -> switchForDetonation();
      case DETONATE_DELAY -> detonate();
      case REPLACE_DELAY -> replace();
      default -> {}
    }
  }

  private void beginFromCrosshair() {
    if (!inputReleased || !minecraft.options.keyUse.isDown()) return;
    BlockHitResult hit = currentAnchorHit();
    if (hit == null) return;
    BlockPos position = hit.getBlockPos().immutable();
    if (onlyOwned.getValue() && !ownedAnchors.contains(position)) return;
    int charge = anchorCharge(position);
    boolean needsCharge = charge == 0;
    if (!needsCharge && mode.getValue() == AnchorMode.CHARGE_ONLY) return;
    if (!needsCharge && !anchorExplodes(position)) return;
    target = position;
    expectedCharge = charge + 1;
    inputReleased = false;
    sequence.begin(tick, needsCharge, delay(switchDelay), CONFIRMATION_TIMEOUT_TICKS);
  }

  private void switchForCharge() {
    if (!sequence.ready(tick)) return;
    int slot = hotbar.find(Items.GLOWSTONE);
    if (slot < 0 || !sameAnchorTarget()) {
      abort();
      return;
    }
    select(slot);
    sequence.switched(tick, delay(chargeDelay), CONFIRMATION_TIMEOUT_TICKS);
  }

  private void charge() {
    if (!sequence.ready(tick) || !sameAnchorTarget()) return;
    int slot = hotbar.find(Items.GLOWSTONE);
    BlockHitResult hit = currentAnchorHit();
    if (slot < 0 || hit == null) {
      abort();
      return;
    }
    InteractionResult result =
        useSlot(
            slot,
            () -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit));
    if (result != null && result.consumesAction()) {
      sequence.attempted(tick, CONFIRMATION_TIMEOUT_TICKS);
    }
  }

  private void switchForDetonation() {
    if (!sequence.ready(tick)) return;
    int slot = selectedDetonationSlot();
    if (!validDetonationSlot(slot) || !sameAnchorTarget() || !anchorExplodes(target)) {
      abort();
      return;
    }
    select(slot);
    sequence.switched(tick, delay(detonateDelay), CONFIRMATION_TIMEOUT_TICKS);
  }

  private void detonate() {
    if (!sequence.ready(tick) || !sameAnchorTarget() || !anchorExplodes(target)) return;
    int slot = selectedDetonationSlot();
    BlockHitResult hit = currentAnchorHit();
    if (!validDetonationSlot(slot) || hit == null) {
      abort();
      return;
    }
    InteractionResult result =
        useSlot(
            slot,
            () -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit));
    if (result != null && result.consumesAction()) {
      sequence.attempted(tick, CONFIRMATION_TIMEOUT_TICKS);
    }
  }

  private void replace() {
    if (!sequence.ready(tick)) return;
    int slot = hotbar.find(Items.RESPAWN_ANCHOR);
    if (slot < 0 || !minecraft.level.getBlockState(target).canBeReplaced()) {
      abort();
      return;
    }
    PlacementPlan plan =
        placements.solve(List.of(target), position -> position.equals(target)).orElse(null);
    if (plan == null) {
      abort();
      return;
    }
    if (!rotations.request(this, plan.yaw(), plan.pitch(), COORDINATOR_PRIORITY, 2)) return;
    if (!rotations.isInteractionReady(this)
        || !placements.currentRotationHits(
            plan, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) return;
    InteractionResult result =
        useSlot(
            slot,
            () ->
                rotations.runInteraction(
                    this,
                    plan.yaw(),
                    plan.pitch(),
                    () ->
                        minecraft.gameMode.useItemOn(
                            minecraft.player, InteractionHand.MAIN_HAND, plan.hit())));
    if (result == null) {
      releaseControl();
      return;
    }
    if (result.consumesAction()) {
      pendingOwned = target;
      pendingOwnedExpiresAt = tick + CONFIRMATION_TIMEOUT_TICKS;
      sequence.replacementComplete(tick, cooldown.getValue());
    }
  }

  private InteractionResult useSlot(
      int slot, java.util.function.Supplier<InteractionResult> action) {
    if (!combat.acquire(this, CombatAction.ANCHOR_SEQUENCE, COORDINATOR_PRIORITY, 2)) return null;
    if (!combat.markAction(this)) {
      combat.release(this);
      return null;
    }
    InteractionResult result =
        restorePrevious.getValue() ? hotbar.withSlot(slot, action) : selectAndRun(slot, action);
    combat.release(this);
    return result;
  }

  private InteractionResult selectAndRun(
      int slot, java.util.function.Supplier<InteractionResult> action) {
    minecraft.player.getInventory().setSelectedSlot(slot);
    return action.get();
  }

  private void select(int slot) {
    if (!restorePrevious.getValue()) minecraft.player.getInventory().setSelectedSlot(slot);
  }

  private boolean validDetonationSlot(int slot) {
    return slot >= 0
        && slot < 9
        && !minecraft.player.getInventory().getItem(slot).is(Items.GLOWSTONE)
        && !minecraft.player.getOffhandItem().is(Items.GLOWSTONE);
  }

  private int selectedDetonationSlot() {
    if (detonationItem.getValue() == DetonationItem.FIXED_SLOT) {
      return detonationSlot.getValue() - 1;
    }
    int selected = minecraft.player.getInventory().getSelectedSlot();
    if (validDetonationSlot(selected)) return selected;
    for (int slot = 0; slot < 9; slot++) if (validDetonationSlot(slot)) return slot;
    return -1;
  }

  private boolean sameAnchorTarget() {
    BlockHitResult hit = currentAnchorHit();
    return hit != null && hit.getBlockPos().equals(target);
  }

  private BlockHitResult currentAnchorHit() {
    if (!(minecraft.hitResult instanceof BlockHitResult hit)) return null;
    return minecraft.level.getBlockState(hit.getBlockPos()).is(Blocks.RESPAWN_ANCHOR) ? hit : null;
  }

  private int anchorCharge(BlockPos position) {
    var state = minecraft.level.getBlockState(position);
    return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : -1;
  }

  private boolean anchorExplodes(BlockPos position) {
    return !minecraft
        .level
        .environmentAttributes()
        .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, position);
  }

  private void observeOwnedPlacement() {
    if (pendingOwned != null) {
      if (minecraft.level.getBlockState(pendingOwned).is(Blocks.RESPAWN_ANCHOR)) {
        ownedAnchors.add(pendingOwned);
        pendingOwned = null;
      } else if (tick > pendingOwnedExpiresAt) pendingOwned = null;
    }
    if (!minecraft.options.keyUse.isDown()) return;
    BlockHitResult hit = minecraft.hitResult instanceof BlockHitResult blockHit ? blockHit : null;
    if (hit == null || !holdingAnchor()) return;
    BlockPos placement =
        minecraft.level.getBlockState(hit.getBlockPos()).canBeReplaced()
            ? hit.getBlockPos()
            : hit.getBlockPos().relative(hit.getDirection());
    pendingOwned = placement.immutable();
    pendingOwnedExpiresAt = tick + CONFIRMATION_TIMEOUT_TICKS;
  }

  private boolean holdingAnchor() {
    return minecraft.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)
        || minecraft.player.getOffhandItem().is(Items.RESPAWN_ANCHOR);
  }

  private int delay(RangeSetting<Integer> setting) {
    Range<Integer> range = setting.getValue();
    return java.util.concurrent.ThreadLocalRandom.current()
        .nextInt(range.minimum(), range.maximum() + 1);
  }

  private enum DetonationItem {
    AUTO,
    FIXED_SLOT
  }

  private boolean ready() {
    return connected()
        && minecraft.gui.screen() == null
        && minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount()
            >= minimumHealth.getValue()
        && (!minecraft.player.isUsingItem() || allowDuringItemUse.getValue());
  }

  private boolean connected() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.player.isPassenger();
  }

  private void abort() {
    sequence.reset();
    target = null;
    releaseControl();
  }

  private void releaseControl() {
    rotations.clear(this);
    combat.release(this);
  }

  private void reset() {
    sequence.reset();
    ownedAnchors.clear();
    target = null;
    pendingOwned = null;
    pendingOwnedExpiresAt = 0;
    expectedCharge = 0;
    tick = 0;
    inputReleased = true;
    dimension = null;
    releaseControl();
  }
}
