package dev.lifus.janetreborn.feature.combat.crystal;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.combat.ExplosionDamage;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.placement.PlacementPlan;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.Notices;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import opsec.misuyaka.sdk.BindMode;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class AutoHitCrystalModule extends Module {
  private static final int COORDINATOR_PRIORITY = 95;
  private static final double TARGET_VALIDITY_MARGIN = 1.0;
  private static final int SEQUENCE_TIMEOUT_TICKS = 20;
  private static final int PENDING_ATTACK_TIMEOUT_TICKS = 8;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final Placement placements;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final FriendStore friends;
  private final Notices notifications;
  private final CrystalSitePlanner sites;
  private final CrystalSequence sequence = new CrystalSequence();
  private final PendingCrystalAttacks pendingAttacks = new PendingCrystalAttacks();
  private final ModeSetting<CrystalActivationMode> activationMode =
      add(new ModeSetting<>("Activation Mode", CrystalActivationMode.MANUAL_HIT));
  private final BoolSetting placeObsidian = add(new BoolSetting("Place Obsidian", true));
  private final BoolSetting requireSword = add(new BoolSetting("Require Sword", true));
  private final RangeSetting<Integer> actionDelay =
      add(
          new RangeSetting<>("Action Delay", new Range<>(2, 4), 1, 8, 1)
              .unit("ticks")
              .markAdvanced());
  private final RangeSetting<Integer> placeDelay =
      add(
          new RangeSetting<>("Place Delay", new Range<>(1, 3), 0, 8, 1)
              .unit("ticks")
              .markAdvanced());
  private final RangeSetting<Integer> breakDelay =
      add(
          new RangeSetting<>("Break Delay", new Range<>(1, 3), 0, 8, 1)
              .unit("ticks")
              .markAdvanced());
  private final NumberSetting<Integer> cooldown =
      add(new NumberSetting<>("Cooldown", 8, 4, 30, 1).unit("ticks").markAdvanced());
  private final BoolSetting antiWeakness = add(new BoolSetting("Anti Weakness", true));
  private final BoolSetting stopWhileUsingItem =
      add(new BoolSetting("Stop While Using Item", true));
  private final NumberSetting<Double> minimumHealth =
      add(new NumberSetting<>("Minimum Health", 10.0, 1.0, 20.0, 0.5).unit("HP"));
  private final NumberSetting<Double> minimumSelfDistance =
      add(new NumberSetting<>("Minimum Self Distance", 2.5, 1.5, 4.5, 0.1).unit("blocks"));
  private final NumberSetting<Double> maximumSelfDamage =
      add(
          new NumberSetting<>("Maximum Self Damage", 8.0, 0.0, 20.0, 0.5)
              .unit("HP")
              .description("Rejects crystal positions estimated to exceed this damage."));
  private UUID targetId;
  private ResourceKey<Level> targetDimension;
  private CrystalSite site;
  private Set<Integer> crystalsBeforePlacement = Set.of();
  private int sequenceCrystalId = -1;
  private int plannedBreakId = -1;
  private long plannedBreakAt;
  private boolean crosshairSequence;
  private long tick;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<TickEvent> clientTick = this::maintain;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<PostInputEvent> postInput = this::act;

  @Subscribe private final Listener<AttackEvent> attack = this::armFromManualHit;

  @Subscribe private final Listener<ServerTeleportEvent> teleport = ignored -> resetState();

  public AutoHitCrystalModule(
      Minecraft minecraft,
      Rotations rotations,
      Placement placements,
      Hotbar hotbar,
      CombatGate coordinator,
      FriendStore friends,
      Notices notifications) {
    super(
        "AutoHitCrystal",
        "Performs one crystal follow-up after a manual melee hit",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.placements = Objects.requireNonNull(placements, "placements");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.friends = Objects.requireNonNull(friends, "friends");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    sites = new CrystalSitePlanner(minecraft, placements);
    getKeybindMode().setValue(BindMode.HOLD);
    placeObsidian.visibleWhen(() -> activationMode.getValue().manualHit());
    requireSword.visibleWhen(() -> activationMode.getValue().manualHit());
    actionDelay.visibleWhen(() -> activationMode.getValue().manualHit());
    placeDelay.visibleWhen(() -> activationMode.getValue().crosshair());
    breakDelay.visibleWhen(() -> activationMode.getValue().crosshair());
  }

  @Override
  protected void onDisable() {
    resetState();
  }

  private void armFromManualHit(AttackEvent event) {
    if (!activationMode.getValue().manualHit()) return;
    boolean playerTarget = event.getTarget() instanceof Player;
    Player target = playerTarget ? (Player) event.getTarget() : null;
    if (!CrystalTriggerPolicy.mayArm(
        sequence.phase() == CrystalSequence.Phase.IDLE,
        playerTarget,
        minecraft.options.keyAttack.isDown(),
        ready(),
        playerTarget && validTarget(target),
        requireSword.getValue(),
        minecraft.player != null && minecraft.player.getMainHandItem().is(ItemTags.SWORDS),
        healthy())) return;

    int crystalSlot = hotbar.find(Items.END_CRYSTAL);
    if (crystalSlot < 0) {
      reject("Missing end crystals");
      return;
    }
    boolean hasObsidian = hotbar.find(Items.OBSIDIAN) >= 0;
    var selected =
        sites.select(
            target,
            event.getHitPosition(),
            placeObsidian.getValue() && hasObsidian,
            minimumSelfDistance.getValue(),
            maximumSelfDamage.getValue());
    if (selected.isEmpty()) {
      reject(
          placeObsidian.getValue() && !hasObsidian ? "Missing obsidian" : "No safe crystal site");
      return;
    }

    CrystalSite candidate = selected.get();
    targetId = target.getUUID();
    targetDimension = minecraft.level.dimension();
    site = candidate;
    crystalsBeforePlacement = sites.currentCrystalIds();
    sequenceCrystalId = -1;
    Range<Integer> delay = actionDelay.getValue();
    sequence.begin(tick, candidate.needsBase(), configuration(delay));
  }

  private void maintain(TickEvent ignored) {
    tick++;
    pendingAttacks.update(
        tick,
        id -> {
          Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(id);
          return entity instanceof EndCrystal crystal && crystal.isAlive() && !crystal.isRemoved();
        });
    if (plannedBreakId >= 0) {
      Entity planned = minecraft.level == null ? null : minecraft.level.getEntity(plannedBreakId);
      if (!(planned instanceof EndCrystal crystal)
          || !crystal.isAlive()
          || crystal.isRemoved()
          || !crosshairInputActive()) clearPlannedBreak();
    }
    CrystalSequence.Phase before = sequence.phase();
    sequence.tick(tick);
    if (before != CrystalSequence.Phase.COOLDOWN
        && sequence.phase() == CrystalSequence.Phase.COOLDOWN) {
      clearContext();
      return;
    }
    if (!sequence.isActive()) return;
    if (!ready()
        || site == null
        || crosshairSequence && !crosshairInputActive()
        || !crosshairSequence && target() == null) {
      abort();
      return;
    }
    if (!healthy()) {
      abort("Health safety stop");
      return;
    }
    if (sequence.phase() == CrystalSequence.Phase.BASE_CONFIRMATION && sites.baseConfirmed(site)) {
      sequence.confirmBase(tick);
      return;
    }
    if (sequence.phase() == CrystalSequence.Phase.CRYSTAL_CONFIRMATION) {
      EndCrystal crystal = sites.findSequenceCrystal(site.base(), crystalsBeforePlacement);
      if (crystal != null && sequence.confirmCrystal(tick)) sequenceCrystalId = crystal.getId();
    }
  }

  private void act(PostInputEvent ignored) {
    if (sequence.isReady(tick)
        && ready()
        && healthy()
        && site != null
        && (crosshairSequence || target() != null)) {
      switch (sequence.phase()) {
        case BASE_DELAY -> placeBase();
        case CRYSTAL_DELAY -> placeCrystal();
        case BREAK_DELAY -> breakCrystal();
        default -> {}
      }
      return;
    }
    if (sequence.phase() == CrystalSequence.Phase.IDLE) handleCrosshair();
  }

  private void placeBase() {
    int slot = hotbar.find(Items.OBSIDIAN);
    PlacementPlan plan = sites.refreshBasePlacement(site, minimumSelfDistance.getValue());
    if (slot < 0 || plan == null) {
      abort();
      return;
    }
    InteractionResult result = usePlan(plan, slot, CombatAction.CRYSTAL_SEQUENCE);
    if (result != null && result.consumesAction() && sequence.acceptBaseAttempt(tick)) return;
    if (result != null) abort();
  }

  private void placeCrystal() {
    int slot = hotbar.find(Items.END_CRYSTAL);
    PlacementPlan plan =
        sites.crystalPlacement(
            site.base(), minimumSelfDistance.getValue(), maximumSelfDamage.getValue());
    if (slot < 0 || plan == null) {
      abort();
      return;
    }
    InteractionResult result =
        usePlan(
            plan,
            slot,
            crosshairSequence ? CombatAction.CRYSTAL_ASSIST : CombatAction.CRYSTAL_SEQUENCE);
    if (result != null && result.consumesAction() && sequence.acceptCrystalAttempt(tick)) return;
    if (result != null) abort();
  }

  private InteractionResult usePlan(PlacementPlan plan, int slot, CombatAction action) {
    if (!rotations.request(this, plan.yaw(), plan.pitch(), COORDINATOR_PRIORITY, 2)) return null;
    if (!rotations.isInteractionReady(this)) {
      coordinator.release(this);
      return null;
    }
    if (!placements.currentRotationHits(
        plan, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      releaseControl();
      return null;
    }
    if (!coordinator.acquire(this, action, COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)
        || !rotations.lockRequestedRotationForNextTick(this)) {
      releaseControl();
      return null;
    }
    InteractionResult result =
        hotbar.withSlot(
            slot,
            () ->
                rotations.runInteraction(
                    this,
                    plan.yaw(),
                    plan.pitch(),
                    () ->
                        minecraft.gameMode.useItemOn(
                            minecraft.player, InteractionHand.MAIN_HAND, plan.hit())));
    releaseControl();
    return result;
  }

  private void breakCrystal() {
    Entity entity = minecraft.level.getEntity(sequenceCrystalId);
    if (!(entity instanceof EndCrystal crystal)
        || !crystal.isAlive()
        || crystal.isRemoved()
        || !CrystalSitePlanner.isMatchingCrystal(
            crystal.getId(), crystalsBeforePlacement, site.base(), crystal.position())
        || ExplosionDamage.estimateSelf(minecraft, crystal.position(), crystal)
            > maximumSelfDamage.getValue()
        || !minecraft.player.isWithinEntityInteractionRange(crystal, 0.0)
        || !minecraft.player.hasLineOfSight(crystal)) {
      abort();
      return;
    }
    if (pendingAttacks.contains(crystal.getId())) return;
    float[] aim = sites.aimAt(crystal);
    if (!rotations.request(this, aim[0], aim[1], COORDINATOR_PRIORITY, 2)) return;
    if (!rotations.isInteractionReady(this)
        || !sites.rotationHits(
            crystal, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      coordinator.release(this);
      return;
    }
    if (!coordinator.acquire(this, CombatAction.CRYSTAL_SEQUENCE, COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)
        || !rotations.lockRequestedRotationForNextTick(this)) {
      releaseControl();
      return;
    }
    if (!attackCrystal(crystal)) {
      releaseControl();
      abort();
      return;
    }
    releaseControl();
    pendingAttacks.add(crystal.getId(), tick, PENDING_ATTACK_TIMEOUT_TICKS);
    if (sequence.completeBreak(tick)) clearContext();
  }

  private void handleCrosshair() {
    if (!ready() || !crosshairInputActive() || !healthy()) {
      clearPlannedBreak();
      return;
    }
    if (minecraft.hitResult instanceof EntityHitResult hit
        && hit.getEntity() instanceof EndCrystal crystal
        && validCrosshairCrystal(crystal)) {
      if (plannedBreakId != crystal.getId()) {
        plannedBreakId = crystal.getId();
        plannedBreakAt = tick + randomDelay(breakDelay.getValue());
        return;
      }
      if (tick >= plannedBreakAt) breakCrosshairCrystal(crystal);
      return;
    }
    clearPlannedBreak();
    if (!(minecraft.hitResult instanceof BlockHitResult hit)) return;
    BlockPos base = hit.getBlockPos().immutable();
    PlacementPlan plan =
        sites.crystalPlacement(base, minimumSelfDistance.getValue(), maximumSelfDamage.getValue());
    if (plan == null) return;
    targetId = null;
    targetDimension = minecraft.level.dimension();
    site = new CrystalSite(base, null);
    crystalsBeforePlacement = sites.currentCrystalIds();
    sequenceCrystalId = -1;
    crosshairSequence = true;
    Range<Integer> place = placeDelay.getValue();
    Range<Integer> destroy = breakDelay.getValue();
    sequence.begin(
        tick,
        false,
        new CrystalSequence.Configuration(
            place.minimum(),
            place.maximum(),
            place.minimum(),
            place.maximum(),
            destroy.minimum(),
            destroy.maximum(),
            SEQUENCE_TIMEOUT_TICKS,
            cooldown.getValue()));
  }

  private void breakCrosshairCrystal(EndCrystal crystal) {
    if (!coordinator.acquire(this, CombatAction.CRYSTAL_ASSIST, COORDINATOR_PRIORITY, 2)) return;
    if (!coordinator.markAction(this)) {
      coordinator.release(this);
      return;
    }
    if (!attackCrystal(crystal)) {
      coordinator.release(this);
      return;
    }
    coordinator.release(this);
    pendingAttacks.add(crystal.getId(), tick, PENDING_ATTACK_TIMEOUT_TICKS);
    clearPlannedBreak();
  }

  private boolean attackCrystal(EndCrystal crystal) {
    int tool =
        antiWeakness.getValue() && weaknessPreventsAttack()
            ? hotbar.find(stack -> stack.is(ItemTags.SWORDS))
            : -1;
    if (antiWeakness.getValue() && weaknessPreventsAttack() && tool < 0) return false;
    if (tool >= 0) {
      hotbar.withSlot(
          tool,
          () -> {
            minecraft.gameMode.attack(minecraft.player, crystal);
            minecraft.player.swing(InteractionHand.MAIN_HAND);
            return null;
          });
      return true;
    }
    minecraft.gameMode.attack(minecraft.player, crystal);
    minecraft.player.swing(InteractionHand.MAIN_HAND);
    return true;
  }

  private boolean weaknessPreventsAttack() {
    MobEffectInstance weakness = minecraft.player.getEffect(MobEffects.WEAKNESS);
    if (weakness == null) return false;
    MobEffectInstance strength = minecraft.player.getEffect(MobEffects.STRENGTH);
    return strength == null || strength.getAmplifier() <= weakness.getAmplifier();
  }

  private boolean validCrosshairCrystal(EndCrystal crystal) {
    return crystal.isAlive()
        && !crystal.isRemoved()
        && !pendingAttacks.contains(crystal.getId())
        && ExplosionDamage.estimateSelf(minecraft, crystal.position(), crystal)
            <= maximumSelfDamage.getValue()
        && minecraft.player.isWithinEntityInteractionRange(crystal, 0.0)
        && minecraft.player.hasLineOfSight(crystal);
  }

  private boolean crosshairInputActive() {
    return activationMode.getValue().crosshair()
        && minecraft.options.keyUse.isDown()
        && minecraft.gui.screen() == null;
  }

  private int randomDelay(Range<Integer> range) {
    return java.util.concurrent.ThreadLocalRandom.current()
        .nextInt(range.minimum(), range.maximum() + 1);
  }

  private void clearPlannedBreak() {
    plannedBreakId = -1;
    plannedBreakAt = 0;
  }

  private Player target() {
    if (minecraft.player == null
        || minecraft.level == null
        || targetId == null
        || targetDimension == null
        || !minecraft.level.dimension().equals(targetDimension)) return null;
    Player target = minecraft.level.getPlayerByUUID(targetId);
    return target != null && validTarget(target) ? target : null;
  }

  private boolean validTarget(Player target) {
    Player player = minecraft.player;
    if (player == null || target == player) return false;
    double range = player.entityInteractionRange() + TARGET_VALIDITY_MARGIN;
    return target.isAlive()
        && !target.isDeadOrDying()
        && !target.isSpectator()
        && !friends.isProtected(target)
        && player.distanceToSqr(target) <= range * range
        && player.hasLineOfSight(target);
  }

  private boolean healthy() {
    return minecraft.player != null
        && minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount()
            >= minimumHealth.getValue();
  }

  private boolean ready() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.gui.screen() == null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.player.isPassenger()
        && (!stopWhileUsingItem.getValue()
            || !minecraft.player.isHandsBusy() && !minecraft.player.isUsingItem());
  }

  private void reject(String message) {
    notifications.hud("AutoHitCrystal", message, Notices.Type.WARNING);
    Range<Integer> delay = actionDelay.getValue();
    if (sequence.begin(tick, false, configuration(delay))) sequence.abort(tick);
  }

  private void abort() {
    sequence.abort(tick);
    clearContext();
  }

  private void abort(String message) {
    notifications.hud("AutoHitCrystal", message, Notices.Type.WARNING);
    abort();
  }

  private CrystalSequence.Configuration configuration(Range<Integer> delay) {
    return new CrystalSequence.Configuration(
        delay.minimum(), delay.maximum(), SEQUENCE_TIMEOUT_TICKS, cooldown.getValue());
  }

  private void releaseControl() {
    rotations.clear(this);
    coordinator.release(this);
  }

  private void clearContext() {
    releaseControl();
    targetId = null;
    targetDimension = null;
    site = null;
    crystalsBeforePlacement = Set.of();
    sequenceCrystalId = -1;
    crosshairSequence = false;
  }

  private void resetState() {
    sequence.reset();
    pendingAttacks.clear();
    clearPlannedBreak();
    clearContext();
  }
}
