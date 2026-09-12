package dev.lifus.janetreborn.client;

import dev.lifus.janetreborn.api.ClientContext;
import dev.lifus.janetreborn.feature.client.ClientSettingsModule;
import dev.lifus.janetreborn.feature.client.PostFxModule;
import dev.lifus.janetreborn.feature.combat.AimAssistModule;
import dev.lifus.janetreborn.feature.combat.AttributeSwapModule;
import dev.lifus.janetreborn.feature.combat.ShieldBreakerModule;
import dev.lifus.janetreborn.feature.combat.SmartTapModule;
import dev.lifus.janetreborn.feature.combat.TriggerbotModule;
import dev.lifus.janetreborn.feature.combat.VelocityModule;
import dev.lifus.janetreborn.feature.combat.anchor.AnchorAssistModule;
import dev.lifus.janetreborn.feature.combat.crystal.AutoHitCrystalModule;
import dev.lifus.janetreborn.feature.combat.drain.AutoDrainModule;
import dev.lifus.janetreborn.feature.combat.lava.AutoLavaModule;
import dev.lifus.janetreborn.feature.combat.totem.TotemGuardModule;
import dev.lifus.janetreborn.feature.combat.web.AutoWebModule;
import dev.lifus.janetreborn.feature.hud.ClockHudModule;
import dev.lifus.janetreborn.feature.hud.ModuleListHudModule;
import dev.lifus.janetreborn.feature.hud.WarnerModule;
import dev.lifus.janetreborn.feature.hud.WatermarkHudModule;
import dev.lifus.janetreborn.feature.misc.FriendsModule;
import dev.lifus.janetreborn.feature.misc.PackSpoofModule;
import dev.lifus.janetreborn.feature.misc.RadioModule;
import dev.lifus.janetreborn.feature.player.AutoWaterModule;
import dev.lifus.janetreborn.feature.player.ChestStealerModule;
import dev.lifus.janetreborn.feature.player.FlyModule;
import dev.lifus.janetreborn.feature.player.InventoryManagerModule;
import dev.lifus.janetreborn.feature.player.RotationSettingsModule;
import dev.lifus.janetreborn.feature.render.EmojifyModule;
import dev.lifus.janetreborn.feature.render.EspModule;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.service.placement.FluidAim;
import dev.lifus.janetreborn.service.placement.OwnedFluids;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.prediction.Predictor;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.Notices;
import java.util.Objects;
import opsec.misuyaka.sdk.Modules;

final class ModuleCatalog {
  private final ClientContext client;

  ModuleCatalog(ClientContext client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  RegisteredModules register() {
    Modules modules = client.modules();
    Rotations rotations = service(Rotations.class);
    FriendStore friends = service(FriendStore.class);
    RotationSettingsModule rotationSettings =
        modules.register(ignored -> new RotationSettingsModule());
    rotations.setConfiguration(rotationSettings);
    ClientSettingsModule clientSettings = modules.register(ignored -> new ClientSettingsModule());
    PostFxModule postProcessing = modules.register(ignored -> new PostFxModule());
    postProcessing.setEnabled(true);
    FriendsModule friendsModule =
        modules.register(ignored -> new FriendsModule(client.minecraft(), friends));
    friendsModule.setEnabled(true);
    modules.register(ignored -> new PackSpoofModule());
    TriggerbotModule triggerbot =
        modules.register(
            ignored ->
                new TriggerbotModule(client.minecraft(), service(CombatGate.class), friends));
    modules.register(ignored -> new AimAssistModule(client.minecraft(), friends, triggerbot));
    modules.register(ignored -> new VelocityModule(client.minecraft()));
    modules.register(
        ignored -> new SmartTapModule(client.minecraft(), friends, service(CombatGate.class)));
    modules.register(
        ignored ->
            new ShieldBreakerModule(
                client.minecraft(), service(Hotbar.class), service(CombatGate.class), friends));
    modules.register(
        ignored -> new AttributeSwapModule(client.minecraft(), service(Hotbar.class), friends));
    modules.register(
        ignored ->
            new TotemGuardModule(
                client.minecraft(),
                service(CombatGate.class),
                service(InventoryActionGate.class),
                friends));
    modules.register(
        ignored ->
            new AnchorAssistModule(
                client.minecraft(),
                rotations,
                service(Placement.class),
                service(Hotbar.class),
                service(CombatGate.class)));
    modules.register(
        ignored ->
            new AutoHitCrystalModule(
                client.minecraft(),
                rotations,
                service(Placement.class),
                service(Hotbar.class),
                service(CombatGate.class),
                friends,
                service(Notices.class)));
    modules.register(
        ignored ->
            new AutoWebModule(
                client.minecraft(),
                rotations,
                service(Placement.class),
                service(Hotbar.class),
                service(CombatGate.class),
                service(Predictor.class),
                friends,
                service(Notices.class)));
    modules.register(
        ignored ->
            new AutoLavaModule(
                client.minecraft(),
                rotations,
                service(Placement.class),
                service(FluidAim.class),
                service(Hotbar.class),
                service(CombatGate.class),
                service(Predictor.class),
                friends,
                service(Notices.class)));
    modules.register(
        ignored ->
            new AutoDrainModule(
                client.minecraft(),
                rotations,
                service(FluidAim.class),
                service(Hotbar.class),
                service(CombatGate.class),
                service(OwnedFluids.class),
                friends,
                service(Notices.class)));
    modules.register(
        ignored ->
            new AutoWaterModule(
                client.minecraft(),
                rotations,
                service(Placement.class),
                service(FluidAim.class),
                service(Hotbar.class),
                service(CombatGate.class),
                service(OwnedFluids.class),
                service(Notices.class)));
    modules.register(ignored -> new FlyModule(client.minecraft()));
    modules.register(
        ignored -> new ChestStealerModule(client.minecraft(), service(InventoryActionGate.class)));
    modules.register(
        ignored ->
            new InventoryManagerModule(client.minecraft(), service(InventoryActionGate.class)));
    modules.register(ignored -> new EmojifyModule(client.minecraft()));
    modules.register(ignored -> new EspModule(client.minecraft(), friends));

    ModuleListHudModule moduleList =
        modules.register(
            ignored -> new ModuleListHudModule(modules, client.rendering()::drawHudSurface));
    moduleList.setEnabled(true);
    modules.register(ignored -> new ClockHudModule(client.rendering()::drawHudSurface));
    modules.register(ignored -> new WatermarkHudModule(client.rendering()::drawHudSurface));
    modules.register(ignored -> new WarnerModule(client.minecraft(), service(Notices.class)));
    modules.register(ignored -> new RadioModule(client.minecraft()));
    return new RegisteredModules(clientSettings, postProcessing);
  }

  private <S> S service(Class<S> type) {
    return client.services().require(type);
  }

  record RegisteredModules(ClientSettingsModule clientSettings, PostFxModule postProcessing) {}
}
