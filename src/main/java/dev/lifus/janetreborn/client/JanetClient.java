package dev.lifus.janetreborn.client;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.api.ClientContext;
import dev.lifus.janetreborn.api.DefaultContext;
import dev.lifus.janetreborn.command.CommandResult;
import dev.lifus.janetreborn.command.Commands;
import dev.lifus.janetreborn.config.Configs;
import dev.lifus.janetreborn.platform.minecraft.MinecraftNetwork;
import dev.lifus.janetreborn.platform.minecraft.MinecraftRender;
import dev.lifus.janetreborn.scripting.ScriptManager;
import dev.lifus.janetreborn.service.Services;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.service.placement.FluidAim;
import dev.lifus.janetreborn.service.placement.OwnedFluids;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.player.Teleports;
import dev.lifus.janetreborn.service.prediction.Predictor;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.ClientUi;
import dev.lifus.janetreborn.ui.Notices;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.Modules;

public final class JanetClient {
  public static final String MOD_ID = "janet_reborn";
  public static final String NAME = "Janet Reborn";
  public static final String VERSION = "4.0.0";
  private static final JanetClient INSTANCE = new JanetClient();
  private final EventBus events = new EventBus();
  private final Modules modules = new Modules(events);
  private final Services services = new Services();
  private final Commands commands = new Commands();
  private final Notices notices = new Notices();
  private final Rotations rotations = new Rotations();
  private final FriendStore friends = new FriendStore();
  private final CombatGate combat = new CombatGate();
  private final Configs configs = new Configs(modules);
  private ClientUi ui;
  private ClientContext context;
  private Teleports teleports;
  private Predictor predictor;
  private Hotbar hotbar;
  private InventoryActionGate inventoryActions;
  private OwnedFluids ownedFluids;
  private ScriptManager scripts;
  private boolean initialized;
  private boolean shuttingDown;

  private JanetClient() {}

  public static JanetClient instance() {
    return INSTANCE;
  }

  public void init() {
    if (initialized) return;
    initialized = true;
    Minecraft minecraft = Minecraft.getInstance();
    teleports = new Teleports(events, minecraft);
    predictor = new Predictor(minecraft);
    friends.init();
    ui = new ClientUi(minecraft, notices, events, modules, configs);
    ownedFluids = new OwnedFluids(minecraft);
    hotbar = new Hotbar(minecraft);
    inventoryActions = new InventoryActionGate();
    registerServices(minecraft);
    context =
        new DefaultContext(
            events,
            modules,
            services,
            minecraft,
            new MinecraftNetwork(minecraft),
            new MinecraftRender(minecraft, ui),
            configs,
            commands);
    modules.bind(context);
    commands.bind(context);
    registerCommands();
    ModuleCatalog.RegisteredModules registered = new ModuleCatalog(context).register();
    ui.setClientSettings(registered.clientSettings());
    ui.setPostProcessingConfiguration(registered.postProcessing());
    scripts = new ScriptManager(context, configs.directory());
    services.register(ScriptManager.class, scripts);
    ui.setScriptManager(scripts);
    scripts.init();
    configs.init();
    ui.init();
  }

  public void shutdown() {
    if (!initialized || shuttingDown) return;
    shuttingDown = true;
    configs.shutdown();
    if (scripts != null) scripts.close();
    modules.cleanup();
    friends.save();
    ui.shutdown();
  }

  public ClientContext context() {
    if (context == null) throw new IllegalStateException("Client is not initialized");
    return context;
  }

  public EventBus events() {
    return events;
  }

  public Modules modules() {
    return modules;
  }

  public Services services() {
    return services;
  }

  public Commands commands() {
    return commands;
  }

  public Rotations rotations() {
    return rotations;
  }

  public Predictor predictor() {
    return predictor;
  }

  public FriendStore friends() {
    return friends;
  }

  public Hotbar hotbar() {
    return hotbar;
  }

  public InventoryActionGate inventoryActions() {
    return inventoryActions;
  }

  public CombatGate combat() {
    return combat;
  }

  public OwnedFluids ownedFluids() {
    return ownedFluids;
  }

  public Notices notices() {
    return notices;
  }

  public Teleports teleports() {
    return teleports;
  }

  public Configs configs() {
    return configs;
  }

  public ScriptManager scripts() {
    if (scripts == null) throw new IllegalStateException("Scripting is not initialized");
    return scripts;
  }

  public ClientUi ui() {
    return ui;
  }

  private void registerServices(Minecraft minecraft) {
    services.register(Minecraft.class, minecraft);
    services.register(Notices.class, notices);
    services.register(Teleports.class, teleports);
    services.register(Rotations.class, rotations);
    services.register(Predictor.class, predictor);
    services.register(FriendStore.class, friends);
    services.register(CombatGate.class, combat);
    services.register(Hotbar.class, hotbar);
    services.register(InventoryActionGate.class, inventoryActions);
    services.register(OwnedFluids.class, ownedFluids);
    services.register(Placement.class, new Placement(minecraft, rotations));
    services.register(FluidAim.class, new FluidAim(minecraft, rotations));
    services.register(Configs.class, configs);
  }

  private void registerCommands() {
    commands.register(
        "help",
        "Lists available client commands",
        ignored ->
            CommandResult.success(
                commands.commands().stream()
                    .map(command -> commands.prefix() + command.name())
                    .collect(Collectors.joining(", "))));
    commands.register(
        "modules",
        "Lists enabled modules",
        ignored -> {
          String enabled =
              modules.getAll().stream()
                  .filter(Module::isEnabled)
                  .filter(Module::isToggleable)
                  .map(Module::getName)
                  .collect(Collectors.joining(", "));
          return CommandResult.success(enabled.isBlank() ? "No modules enabled" : enabled);
        });
    commands.register(
        "toggle",
        "Toggles a module by name",
        command -> {
          String name = String.join(" ", command.arguments());
          if (name.isBlank()) return CommandResult.error("Usage: .toggle <module>");
          Module module = modules.find(name).orElse(null);
          if (module == null) return CommandResult.error("Unknown module: " + name);
          if (!module.isToggleable()) return CommandResult.error(module.getName() + " is internal");
          module.toggle();
          return CommandResult.success(
              module.getName() + (module.isEnabled() ? " enabled" : " disabled"));
        });
  }
}
