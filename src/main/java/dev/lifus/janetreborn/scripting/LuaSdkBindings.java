package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.lifus.janetreborn.api.ClientContext;
import dev.lifus.janetreborn.command.CommandResult;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.ui.Notices;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import org.lwjgl.glfw.GLFW;

final class LuaSdkBindings {
  private final ScriptInstance instance;
  private final ClientContext client;
  private final LuaScriptRuntime runtime;
  private final String scriptId;

  LuaSdkBindings(ScriptInstance instance, ClientContext client) {
    this.instance = Objects.requireNonNull(instance, "instance");
    this.client = Objects.requireNonNull(client, "client");
    runtime = instance.runtime();
    scriptId = instance.manifest().id();
  }

  void install() {
    runtime.newTable();
    runtime.setFieldValue(-1, "sdk_version", new JsonPrimitive(JanetSdkVersion.CURRENT.toString()));
    moduleNamespace();
    eventsNamespace();
    schedulerNamespace();
    storageNamespace();
    loggerNamespace();
    notificationsNamespace();
    commandsNamespace();
    snapshotNamespaces();
    combatNamespace();
    renderingNamespace();
    utilityNamespace();
    emptyNamespace("settings");
    inputNamespace();
    chatNamespace();
    runtime.setGlobalFromTop("janet");
    runtime.setGlobalFunction("print", state -> logCall(ScriptSeverity.INFO));
  }

  private void moduleNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "create", ignored -> createModule());
    runtime.setFieldFunction(-1, "find", ignored -> findModule());
    runtime.setFieldTable(-2, "module");
  }

  private int createModule() {
    int config = runtime.top();
    if (!runtime.isTable(config))
      throw new IllegalArgumentException("module.create expects a table");
    String localId = runtime.stringField(config, "id", "");
    if (!localId.matches("[a-z][a-z0-9_.-]{1,63}")) {
      throw new IllegalArgumentException("Invalid script module id: " + localId);
    }
    String name = runtime.stringField(config, "name", localId);
    String description = runtime.stringField(config, "description", "Lua module " + name);
    Category category =
        Category.valueOf(runtime.stringField(config, "category", "MISC").toUpperCase(Locale.ROOT));
    boolean enabled = runtime.booleanField(config, "enabled", false);
    List<String> dependencies = stringList(runtime.field(config, "dependencies", new JsonArray()));
    List<String> conflicts = stringList(runtime.field(config, "conflicts", new JsonArray()));

    runtime.newTable();
    runtime.setFieldValue(-1, "id", new JsonPrimitive(localId));
    runtime.setFieldValue(-1, "name", new JsonPrimitive(name));
    LuaRef table = runtime.reference(-1);
    ScriptModule module =
        new ScriptModule(
            scriptId,
            localId,
            name,
            description,
            category,
            enabled,
            runtime,
            table,
            dependencies,
            conflicts,
            (ignored, failure) -> instance.recordFailure(failure),
            this::moduleStateChanged);
    runtime.newTable();
    for (ScriptSettingKind kind : ScriptSettingKind.values()) {
      runtime.setFieldFunction(-1, settingFunction(kind), ignored -> createSetting(module, kind));
    }
    runtime.setFieldTable(-2, "settings");
    runtime.setFieldFunction(-1, "on", ignored -> registerEvent(module));
    runtime.setFieldFunction(-1, "command", ignored -> registerCommand(module));
    instance.addModule(module);
    return 1;
  }

  private int findModule() {
    int argument = runtime.top();
    String id = runtime.stringArgument(argument, "module id");
    Module module = client.modules().find(id).orElse(null);
    if (module == null) return runtime.returnValue(JsonNull.INSTANCE);
    JsonObject snapshot = new JsonObject();
    snapshot.addProperty("id", module.getId());
    snapshot.addProperty("name", module.getName());
    snapshot.addProperty("enabled", module.isEnabled());
    snapshot.addProperty("category", module.getCategory().name());
    return runtime.returnValue(snapshot);
  }

  private int createSetting(ScriptModule module, ScriptSettingKind kind) {
    int config = runtime.top();
    if (!runtime.isTable(config))
      throw new IllegalArgumentException("setting definition must be a table");
    String id = runtime.stringField(config, "id", "");
    String name = runtime.stringField(config, "name", id);
    double minimum = runtime.numberField(config, "minimum", defaultMinimum(kind));
    double maximum = runtime.numberField(config, "maximum", defaultMaximum(kind));
    double step = runtime.numberField(config, "step", 1);
    List<String> modes = stringList(runtime.field(config, "values", new JsonArray()));
    JsonElement defaultValue = runtime.field(config, "default", defaultValue(kind, minimum));
    ScriptSetting setting =
        new ScriptSetting(
            id,
            name,
            kind,
            defaultValue,
            minimum,
            maximum,
            step,
            modes,
            runtime.stringField(config, "section", ""),
            runtime.booleanField(config, "advanced", false));
    String visibleWhen = runtime.stringField(config, "visible_when", "");
    if (!visibleWhen.isBlank()) {
      ScriptSetting dependency = module.findSetting(visibleWhen);
      if (dependency == null)
        throw new IllegalArgumentException("Unknown visible_when setting: " + visibleWhen);
      setting.visibleWhen(() -> dependency.getValue().getAsBoolean());
    }
    String enabledWhen = runtime.stringField(config, "enabled_when", "");
    if (!enabledWhen.isBlank()) {
      ScriptSetting dependency = module.findSetting(enabledWhen);
      if (dependency == null)
        throw new IllegalArgumentException("Unknown enabled_when setting: " + enabledWhen);
      setting.enabledWhen(() -> dependency.getValue().getAsBoolean());
    }
    LuaRef normalizer = runtime.functionField(config, "normalize");
    if (normalizer != null) {
      module.ownedResources().own(normalizer);
      setting.normalizer(value -> runtime.invoke(normalizer, value));
    }
    LuaRef onChange = runtime.functionField(config, "on_change");
    if (onChange != null) {
      module.ownedResources().own(onChange);
      setting.onChange((before, after) -> runtime.invoke(onChange, before, after));
    }
    LuaRef serializer = runtime.functionField(config, "serialize");
    if (serializer != null) {
      module.ownedResources().own(serializer);
      setting.serializer(value -> runtime.invoke(serializer, value));
    }
    LuaRef deserializer = runtime.functionField(config, "deserialize");
    if (deserializer != null) {
      module.ownedResources().own(deserializer);
      setting.deserializer(value -> runtime.invoke(deserializer, value));
    }
    module.registerSetting(setting);
    runtime.newTable();
    runtime.setFieldFunction(-1, "get", ignored -> runtime.returnValue(setting.getValue()));
    runtime.setFieldFunction(
        -1,
        "set",
        ignored -> {
          setting.setValue(runtime.argument(runtime.top()));
          return runtime.returnValue(setting.getValue());
        });
    runtime.setFieldFunction(
        -1,
        "reset",
        ignored -> {
          setting.reset();
          return runtime.returnValue(setting.getValue());
        });
    runtime.setFieldValue(-1, "id", new JsonPrimitive(id));
    return 1;
  }

  private void eventsNamespace() {
    runtime.newTable();
    runtime.setFieldValue(-1, "HIGHEST", new JsonPrimitive(2));
    runtime.setFieldValue(-1, "HIGH", new JsonPrimitive(1));
    runtime.setFieldValue(-1, "DEFAULT", new JsonPrimitive(0));
    runtime.setFieldValue(-1, "LOW", new JsonPrimitive(-1));
    runtime.setFieldValue(-1, "LOWEST", new JsonPrimitive(-2));
    runtime.setFieldFunction(-1, "subscribe", ignored -> registerEvent(null));
    runtime.setFieldTable(-2, "events");
  }

  private int registerEvent(ScriptModule module) {
    int offset = runtime.isTable(1) ? 1 : 0;
    String eventName = runtime.stringArgument(1 + offset, "event name");
    LuaRef callback = runtime.referenceFunction(2 + offset);
    int options = 3 + offset <= runtime.top() && runtime.isTable(3 + offset) ? 3 + offset : -1;
    int priority = options < 0 ? 0 : (int) runtime.numberField(options, "priority", 0);
    priority = Math.clamp(priority, -100, 100);
    boolean once = options >= 0 && runtime.booleanField(options, "once", false);
    LuaRef filter = options < 0 ? null : runtime.functionField(options, "filter");
    OwnedResources owner = module == null ? null : module.ownedResources();
    own(owner, callback);
    if (filter != null) own(owner, filter);
    BooleanSupplier active =
        module == null ? instance::isActive : () -> instance.isActive() && module.isEnabled();
    int selectedPriority = priority;
    java.util.function.Supplier<AutoCloseable> installer =
        () ->
            instance
                .manager()
                .eventBridge()
                .subscribe(
                    eventName,
                    selectedPriority,
                    once,
                    runtime,
                    callback,
                    filter,
                    ignored -> active.getAsBoolean(),
                    instance::recordFailure);
    ScriptInstance.DeferredHandle handle =
        owner == null ? instance.stage(installer) : instance.stage(installer, owner);
    return returnHandle(handle, handle::isActive);
  }

  private void commandsNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "register", ignored -> registerCommand(null));
    runtime.setFieldTable(-2, "commands");
  }

  private int registerCommand(ScriptModule module) {
    int offset = runtime.isTable(1) ? 1 : 0;
    String name = runtime.stringArgument(1 + offset, "command name");
    String description = runtime.stringArgument(2 + offset, "command description");
    LuaRef callback = runtime.referenceFunction(3 + offset);
    OwnedResources owner = module == null ? null : module.ownedResources();
    own(owner, callback);
    String registeredName = scriptId.replace('.', '-') + "-" + name;
    java.util.function.Supplier<AutoCloseable> installer =
        () ->
            client
                .commands()
                .register(
                    registeredName,
                    description,
                    call -> {
                      if (!instance.isActive() || module != null && !module.isEnabled()) {
                        return CommandResult.error("Script or module is disabled");
                      }
                      JsonArray arguments = new JsonArray();
                      call.arguments().forEach(arguments::add);
                      JsonElement result = runtime.invoke(callback, arguments);
                      if (result.isJsonObject()) {
                        JsonObject object = result.getAsJsonObject();
                        boolean success =
                            !object.has("success") || object.get("success").getAsBoolean();
                        String message =
                            object.has("message") ? object.get("message").getAsString() : "";
                        return success
                            ? CommandResult.success(message)
                            : CommandResult.error(message);
                      }
                      return CommandResult.success(result.isJsonNull() ? "" : result.getAsString());
                    });
    ScriptInstance.DeferredHandle handle =
        owner == null ? instance.stage(installer) : instance.stage(installer, owner);
    return returnHandle(handle, handle::isActive);
  }

  private void schedulerNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "after", ignored -> schedule(false));
    runtime.setFieldFunction(-1, "every", ignored -> schedule(true));
    runtime.setFieldTable(-2, "scheduler");
  }

  private int schedule(boolean repeating) {
    int offset = runtime.isTable(1) ? 1 : 0;
    long ticks = runtime.integerArgument(1 + offset, "ticks");
    LuaRef callback = runtime.referenceFunction(2 + offset);
    instance.own(callback);
    ScriptScheduler.ScheduledHandle handle =
        instance
            .manager()
            .scheduler()
            .schedule(
                scriptId,
                ticks,
                repeating ? Math.max(1, ticks) : 0,
                () -> {
                  if (instance.isActive()) runtime.invoke(callback);
                });
    instance.own(handle);
    return returnHandle(handle, handle::isActive);
  }

  private void storageNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(
        -1,
        "get",
        ignored -> {
          String key = runtime.stringArgument(runtime.top(), "storage key");
          return runtime.returnValue(instance.storage().get(key).orElse(JsonNull.INSTANCE));
        });
    runtime.setFieldFunction(
        -1,
        "set",
        ignored -> {
          int top = runtime.top();
          instance
              .storage()
              .put(runtime.stringArgument(top - 1, "storage key"), runtime.argument(top));
          return 0;
        });
    runtime.setFieldFunction(
        -1,
        "remove",
        ignored -> {
          instance.storage().remove(runtime.stringArgument(runtime.top(), "storage key"));
          return 0;
        });
    runtime.setFieldFunction(
        -1,
        "flush",
        ignored -> {
          try {
            instance.storage().flush();
          } catch (IOException exception) {
            throw new RuntimeException(exception);
          }
          return 0;
        });
    runtime.setFieldTable(-2, "storage");
  }

  private void loggerNamespace() {
    runtime.newTable();
    for (ScriptSeverity severity : ScriptSeverity.values()) {
      runtime.setFieldFunction(
          -1, severity.name().toLowerCase(Locale.ROOT), ignored -> logCall(severity));
    }
    runtime.setFieldTable(-2, "logger");
  }

  private int logCall(ScriptSeverity severity) {
    List<String> values = new ArrayList<>();
    for (int index = 1; index <= runtime.top(); index++) {
      if (index == 1 && runtime.isTable(index)) continue;
      values.add(runtime.displayArgument(index));
    }
    instance.manager().log(scriptId, severity, String.join(" ", values));
    return 0;
  }

  private void notificationsNamespace() {
    runtime.newTable();
    for (Notices.Type type : Notices.Type.values()) {
      runtime.setFieldFunction(
          -1,
          type.name().toLowerCase(Locale.ROOT),
          ignored -> {
            int top = runtime.top();
            String message = runtime.stringArgument(top, "message");
            String title =
                top > 1 ? runtime.stringArgument(top - 1, "title") : instance.manifest().name();
            client.services().require(Notices.class).hud(title, message, type);
            return 0;
          });
    }
    runtime.setFieldTable(-2, "notifications");
  }

  private void snapshotNamespaces() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "snapshot", ignored -> runtime.returnValue(clientSnapshot()));
    runtime.setFieldTable(-2, "client");
    runtime.newTable();
    runtime.setFieldFunction(-1, "snapshot", ignored -> runtime.returnValue(playerSnapshot()));
    runtime.setFieldTable(-2, "player");
    runtime.newTable();
    runtime.setFieldFunction(-1, "snapshot", ignored -> runtime.returnValue(worldSnapshot()));
    runtime.setFieldFunction(-1, "block", ignored -> worldBlock());
    runtime.setFieldTable(-2, "world");
    runtime.newTable();
    runtime.setFieldFunction(-1, "snapshot", ignored -> runtime.returnValue(inventorySnapshot()));
    runtime.setFieldFunction(-1, "select_hotbar", ignored -> selectHotbar());
    runtime.setFieldTable(-2, "inventory");
  }

  private void inputNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(
        -1,
        "is_key_down",
        ignored -> {
          int key = (int) runtime.integerArgument(runtime.top(), "key");
          boolean down =
              GLFW.glfwGetKey(client.minecraft().getWindow().handle(), key) == GLFW.GLFW_PRESS;
          return runtime.returnBoolean(down);
        });
    runtime.setFieldTable(-2, "input");
  }

  private void combatNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "nearest_player", ignored -> nearestPlayer());
    runtime.setFieldFunction(-1, "attack_ready", ignored -> attackReady());
    runtime.setFieldFunction(-1, "attack", ignored -> attack());
    runtime.setFieldTable(-2, "combat");
  }

  private int nearestPlayer() {
    JsonElement requestedRange = runtime.argument(runtime.top());
    if (!requestedRange.isJsonPrimitive() || !requestedRange.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException("range must be a number");
    }
    double range = requestedRange.getAsDouble();
    if (range <= 0 || range > 6) {
      throw new IllegalArgumentException("range must be between 0 and 6 blocks");
    }
    Minecraft minecraft = client.minecraft();
    if (minecraft == null || minecraft.player == null || minecraft.level == null) {
      return runtime.returnValue(JsonNull.INSTANCE);
    }
    FriendStore friends = client.services().find(FriendStore.class).orElse(null);
    Player nearest = null;
    double nearestDistance = range * range;
    for (Player candidate : minecraft.level.players()) {
      if (candidate == minecraft.player
          || !candidate.isAlive()
          || candidate.isDeadOrDying()
          || friends != null && friends.isProtected(candidate)
          || !minecraft.player.canAttack(candidate)) continue;
      double distance = minecraft.player.distanceToSqr(candidate);
      if (distance > nearestDistance) continue;
      nearest = candidate;
      nearestDistance = distance;
    }
    if (nearest == null) return runtime.returnValue(JsonNull.INSTANCE);
    JsonObject result = new JsonObject();
    result.addProperty("id", nearest.getId());
    result.addProperty("name", nearest.getName().getString());
    result.addProperty("distance", Math.sqrt(nearestDistance));
    result.addProperty("health", nearest.getHealth());
    return runtime.returnValue(result);
  }

  private int attackReady() {
    Minecraft minecraft = client.minecraft();
    boolean ready =
        minecraft != null
            && minecraft.player != null
            && minecraft.level != null
            && minecraft.gameMode != null
            && minecraft.gui.screen() == null
            && minecraft.player.isAlive()
            && !minecraft.player.isUsingItem()
            && minecraft.player.getAttackStrengthScale(0.5f) > 0.9f;
    return runtime.returnBoolean(ready);
  }

  private int attack() {
    int entityId = (int) runtime.integerArgument(runtime.top(), "entity id");
    Minecraft minecraft = client.minecraft();
    if (minecraft == null
        || minecraft.player == null
        || minecraft.level == null
        || minecraft.gameMode == null) return runtime.returnBoolean(false);
    Entity entity = minecraft.level.getEntity(entityId);
    if (!(entity instanceof Player target)
        || target == minecraft.player
        || !target.isAlive()
        || target.isDeadOrDying()
        || !minecraft.player.canAttack(target)
        || !minecraft.player.isWithinEntityInteractionRange(target, 0)) {
      return runtime.returnBoolean(false);
    }
    FriendStore friends = client.services().find(FriendStore.class).orElse(null);
    if (friends != null && friends.isProtected(target)) return runtime.returnBoolean(false);
    CombatGate gate = client.services().find(CombatGate.class).orElse(null);
    if (gate != null
        && (!gate.acquire(instance, CombatAction.SCRIPT, 50, 1) || !gate.markAction(instance))) {
      return runtime.returnBoolean(false);
    }
    try {
      minecraft.gameMode.attack(minecraft.player, target);
      minecraft.player.swing(InteractionHand.MAIN_HAND);
      return runtime.returnBoolean(true);
    } finally {
      if (gate != null) gate.release(instance);
    }
  }

  private void chatNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(
        -1,
        "system",
        ignored -> {
          requireCapability(ScriptCapability.CHAT_OUTPUT);
          String message = runtime.stringArgument(runtime.top(), "message");
          client
              .minecraft()
              .gui
              .chatListener()
              .handleSystemMessage(Component.literal(message), false);
          return 0;
        });
    runtime.setFieldTable(-2, "chat");
  }

  private JsonObject clientSnapshot() {
    Minecraft minecraft = client.minecraft();
    JsonObject data = new JsonObject();
    data.addProperty("connected", minecraft.getConnection() != null);
    data.addProperty(
        "screen",
        minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getClass().getSimpleName());
    data.addProperty("window_width", minecraft.getWindow().getWidth());
    data.addProperty("window_height", minecraft.getWindow().getHeight());
    return data;
  }

  private JsonElement playerSnapshot() {
    var player = client.minecraft().player;
    if (player == null) return JsonNull.INSTANCE;
    JsonObject data = new JsonObject();
    data.addProperty("uuid", player.getUUID().toString());
    data.addProperty("name", player.getName().getString());
    data.addProperty("x", player.getX());
    data.addProperty("y", player.getY());
    data.addProperty("z", player.getZ());
    data.addProperty("yaw", player.getYRot());
    data.addProperty("pitch", player.getXRot());
    data.addProperty("velocity_x", player.getDeltaMovement().x());
    data.addProperty("velocity_y", player.getDeltaMovement().y());
    data.addProperty("velocity_z", player.getDeltaMovement().z());
    data.addProperty("health", player.getHealth());
    data.addProperty("max_health", player.getMaxHealth());
    data.addProperty("hunger", player.getFoodData().getFoodLevel());
    data.addProperty("armor", player.getArmorValue());
    data.addProperty("on_ground", player.onGround());
    return data;
  }

  private JsonElement worldSnapshot() {
    var level = client.minecraft().level;
    if (level == null) return JsonNull.INSTANCE;
    JsonObject data = new JsonObject();
    data.addProperty("time", level.getGameTime());
    data.addProperty("day_time", Math.floorMod(level.getGameTime(), 24_000));
    data.addProperty("dimension", level.dimension().identifier().toString());
    return data;
  }

  private int worldBlock() {
    int top = runtime.top();
    int x = (int) runtime.integerArgument(top - 2, "x");
    int y = (int) runtime.integerArgument(top - 1, "y");
    int z = (int) runtime.integerArgument(top, "z");
    var level = client.minecraft().level;
    if (level == null) return runtime.returnValue(JsonNull.INSTANCE);
    var state = level.getBlockState(new BlockPos(x, y, z));
    JsonObject block = new JsonObject();
    block.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    block.addProperty("air", state.isAir());
    return runtime.returnValue(block);
  }

  private JsonElement inventorySnapshot() {
    var player = client.minecraft().player;
    if (player == null) return JsonNull.INSTANCE;
    Inventory inventory = player.getInventory();
    JsonObject data = new JsonObject();
    data.addProperty("selected_slot", inventory.getSelectedSlot());
    JsonArray hotbar = new JsonArray();
    for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
      hotbar.add(itemSnapshot(inventory.getItem(slot), slot));
    }
    data.add("hotbar", hotbar);
    return data;
  }

  private JsonObject itemSnapshot(ItemStack stack, int slot) {
    JsonObject item = new JsonObject();
    item.addProperty("slot", slot);
    item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    item.addProperty("name", stack.getHoverName().getString());
    item.addProperty("count", stack.getCount());
    item.addProperty("damage", stack.getDamageValue());
    item.addProperty("max_damage", stack.getMaxDamage());
    item.addProperty("empty", stack.isEmpty());
    return item;
  }

  private int selectHotbar() {
    requireCapability(ScriptCapability.INVENTORY_ACTIONS);
    int slot = (int) runtime.integerArgument(runtime.top(), "hotbar slot");
    if (slot < 0 || slot >= Inventory.getSelectionSize()) {
      throw new IllegalArgumentException("Hotbar slot is out of range");
    }
    InventoryActionGate gate = client.services().require(InventoryActionGate.class);
    boolean acquired = gate.acquire(instance, 0, 1) && gate.markAction(instance);
    if (acquired) client.services().require(Hotbar.class).selectTemporarily(slot);
    return runtime.returnBoolean(acquired);
  }

  private void renderingNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(-1, "text", ignored -> drawText());
    runtime.setFieldFunction(-1, "line", ignored -> drawLine());
    runtime.setFieldFunction(-1, "rect", ignored -> drawRect(false));
    runtime.setFieldFunction(-1, "filled_rect", ignored -> drawRect(true));
    runtime.setFieldFunction(-1, "circle", ignored -> drawCircle());
    runtime.setFieldFunction(-1, "gradient_rect", ignored -> drawGradient());
    runtime.setFieldFunction(-1, "clip", ignored -> drawClipped());
    runtime.setFieldFunction(-1, "world_box", ignored -> drawWorldBox());
    runtime.setFieldFunction(-1, "world_to_screen", ignored -> worldToScreen());
    runtime.setFieldTable(-2, "render");
  }

  private int drawText() {
    int top = runtime.top();
    float x = (float) runtime.argument(top - 3).getAsDouble();
    float y = (float) runtime.argument(top - 2).getAsDouble();
    String text = runtime.stringArgument(top - 1, "text");
    int color = color(runtime.argument(top));
    ImGui.getForegroundDrawList().addText(x, y, color, text);
    return 0;
  }

  private int drawLine() {
    int top = runtime.top();
    float x1 = (float) runtime.argument(top - 5).getAsDouble();
    float y1 = (float) runtime.argument(top - 4).getAsDouble();
    float x2 = (float) runtime.argument(top - 3).getAsDouble();
    float y2 = (float) runtime.argument(top - 2).getAsDouble();
    int color = color(runtime.argument(top - 1));
    float thickness = (float) runtime.argument(top).getAsDouble();
    ImGui.getForegroundDrawList().addLine(x1, y1, x2, y2, color, Math.clamp(thickness, 0.5f, 20));
    return 0;
  }

  private int drawRect(boolean filled) {
    int top = runtime.top();
    float x1 = (float) runtime.argument(top - 4).getAsDouble();
    float y1 = (float) runtime.argument(top - 3).getAsDouble();
    float x2 = (float) runtime.argument(top - 2).getAsDouble();
    float y2 = (float) runtime.argument(top - 1).getAsDouble();
    int color = color(runtime.argument(top));
    ImDrawList drawList = ImGui.getForegroundDrawList();
    if (filled) drawList.addRectFilled(x1, y1, x2, y2, color);
    else drawList.addRect(x1, y1, x2, y2, color);
    return 0;
  }

  private int drawCircle() {
    int top = runtime.top();
    float x = (float) runtime.argument(top - 3).getAsDouble();
    float y = (float) runtime.argument(top - 2).getAsDouble();
    float radius = (float) runtime.argument(top - 1).getAsDouble();
    int color = color(runtime.argument(top));
    ImGui.getForegroundDrawList().addCircle(x, y, Math.clamp(radius, 0, 10_000), color);
    return 0;
  }

  private int drawGradient() {
    int top = runtime.top();
    float x1 = (float) runtime.argument(top - 7).getAsDouble();
    float y1 = (float) runtime.argument(top - 6).getAsDouble();
    float x2 = (float) runtime.argument(top - 5).getAsDouble();
    float y2 = (float) runtime.argument(top - 4).getAsDouble();
    int topLeft = color(runtime.argument(top - 3));
    int topRight = color(runtime.argument(top - 2));
    int bottomRight = color(runtime.argument(top - 1));
    int bottomLeft = color(runtime.argument(top));
    ImGui.getForegroundDrawList()
        .addRectFilledMultiColor(x1, y1, x2, y2, topLeft, topRight, bottomRight, bottomLeft);
    return 0;
  }

  private int drawClipped() {
    int top = runtime.top();
    float x1 = (float) runtime.argument(top - 4).getAsDouble();
    float y1 = (float) runtime.argument(top - 3).getAsDouble();
    float x2 = (float) runtime.argument(top - 2).getAsDouble();
    float y2 = (float) runtime.argument(top - 1).getAsDouble();
    LuaRef callback = runtime.referenceFunction(top);
    ImDrawList drawList = ImGui.getForegroundDrawList();
    drawList.pushClipRect(x1, y1, x2, y2, true);
    try {
      runtime.invoke(callback);
    } finally {
      callback.close();
      drawList.popClipRect();
    }
    return 0;
  }

  private int drawWorldBox() {
    int top = runtime.top();
    double minX = runtime.argument(top - 6).getAsDouble();
    double minY = runtime.argument(top - 5).getAsDouble();
    double minZ = runtime.argument(top - 4).getAsDouble();
    double maxX = runtime.argument(top - 3).getAsDouble();
    double maxY = runtime.argument(top - 2).getAsDouble();
    double maxZ = runtime.argument(top - 1).getAsDouble();
    int color = color(runtime.argument(top));
    float[][] points = new float[8][];
    for (int corner = 0; corner < 8; corner++) {
      points[corner] =
          project(
              new Vec3(
                  (corner & 1) == 0 ? minX : maxX,
                  (corner & 2) == 0 ? minY : maxY,
                  (corner & 4) == 0 ? minZ : maxZ));
      if (points[corner] == null) return 0;
    }
    ImDrawList drawList = ImGui.getForegroundDrawList();
    for (int corner = 0; corner < 8; corner++) {
      if ((corner & 1) == 0) worldEdge(drawList, points, corner, corner | 1, color);
      if ((corner & 2) == 0) worldEdge(drawList, points, corner, corner | 2, color);
      if ((corner & 4) == 0) worldEdge(drawList, points, corner, corner | 4, color);
    }
    return 0;
  }

  private int worldToScreen() {
    int top = runtime.top();
    Vec3 point =
        new Vec3(
            runtime.argument(top - 2).getAsDouble(),
            runtime.argument(top - 1).getAsDouble(),
            runtime.argument(top).getAsDouble());
    float[] projected = project(point);
    if (projected == null) return runtime.returnValue(JsonNull.INSTANCE);
    JsonObject result = new JsonObject();
    result.addProperty("x", projected[0]);
    result.addProperty("y", projected[1]);
    return runtime.returnValue(result);
  }

  private float[] project(Vec3 point) {
    Camera camera = client.minecraft().gameRenderer.mainCamera();
    Vec3 offset = point.subtract(camera.position());
    var forward = camera.forwardVector();
    if (offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z() <= 0.05)
      return null;
    Vec3 projected = client.minecraft().gameRenderer.projectPointToScreen(point);
    return new float[] {
      (float) ((projected.x + 1) * 0.5 * ImGui.getIO().getDisplaySizeX()),
      (float) ((1 - projected.y) * 0.5 * ImGui.getIO().getDisplaySizeY())
    };
  }

  private static void worldEdge(
      ImDrawList drawList, float[][] points, int from, int to, int color) {
    drawList.addLine(points[from][0], points[from][1], points[to][0], points[to][1], color, 2);
  }

  private void utilityNamespace() {
    runtime.newTable();
    runtime.setFieldFunction(
        -1,
        "clamp",
        ignored -> {
          int top = runtime.top();
          double value = runtime.argument(top - 2).getAsDouble();
          double minimum = runtime.argument(top - 1).getAsDouble();
          double maximum = runtime.argument(top).getAsDouble();
          return runtime.returnValue(new JsonPrimitive(Math.clamp(value, minimum, maximum)));
        });
    runtime.setFieldFunction(
        -1,
        "rgba",
        ignored -> {
          int top = runtime.top();
          int red = (int) runtime.integerArgument(top - 3, "red");
          int green = (int) runtime.integerArgument(top - 2, "green");
          int blue = (int) runtime.integerArgument(top - 1, "blue");
          int alpha = (int) runtime.integerArgument(top, "alpha");
          return runtime.returnValue(
              new JsonPrimitive(new Color(red, green, blue, alpha).getRGB()));
        });
    runtime.setFieldTable(-2, "util");
  }

  private void moduleStateChanged(ScriptModule module, boolean enabled) {
    JsonObject event = new JsonObject();
    event.addProperty("module_id", module.getId());
    event.addProperty("module_name", module.getName());
    event.addProperty("enabled", enabled);
    instance.manager().eventBridge().fireSynthetic("module_change", event);
  }

  private int returnHandle(AutoCloseable handle, BooleanSupplier active) {
    runtime.newTable();
    runtime.setFieldFunction(
        -1,
        "close",
        ignored -> {
          try {
            handle.close();
          } catch (Exception exception) {
            throw new RuntimeException(exception);
          }
          return 0;
        });
    runtime.setFieldFunction(
        -1, "is_active", ignored -> runtime.returnBoolean(active.getAsBoolean()));
    return 1;
  }

  private void own(OwnedResources owner, AutoCloseable resource) {
    if (owner == null) instance.own(resource);
    else owner.own(resource);
  }

  private void emptyNamespace(String name) {
    runtime.newTable();
    runtime.setFieldTable(-2, name);
  }

  private void requireCapability(ScriptCapability capability) {
    if (!instance.manifest().capabilities().contains(capability)) {
      throw new SecurityException("Script did not declare capability " + capability.manifestName());
    }
  }

  private static List<String> stringList(JsonElement value) {
    if (!value.isJsonArray()) throw new IllegalArgumentException("Expected an array of strings");
    List<String> result = new ArrayList<>();
    for (JsonElement element : value.getAsJsonArray()) result.add(element.getAsString());
    return List.copyOf(result);
  }

  private static String settingFunction(ScriptSettingKind kind) {
    return switch (kind) {
      case INTEGER -> "integer";
      case ENUM -> "mode";
      default -> kind.name().toLowerCase(Locale.ROOT);
    };
  }

  private static double defaultMinimum(ScriptSettingKind kind) {
    return switch (kind) {
      case INTEGER, NUMBER, RANGE -> -1_000_000;
      case KEYBIND -> GLFW.GLFW_KEY_UNKNOWN;
      default -> -Double.MAX_VALUE;
    };
  }

  private static double defaultMaximum(ScriptSettingKind kind) {
    return switch (kind) {
      case INTEGER, NUMBER, RANGE -> 1_000_000;
      case KEYBIND -> GLFW.GLFW_KEY_LAST;
      default -> Double.MAX_VALUE;
    };
  }

  private static JsonElement defaultValue(ScriptSettingKind kind, double minimum) {
    return switch (kind) {
      case BOOLEAN -> new JsonPrimitive(false);
      case INTEGER, KEYBIND -> new JsonPrimitive((long) Math.max(0, minimum));
      case NUMBER -> new JsonPrimitive(Math.max(0, minimum));
      case RANGE -> {
        JsonObject range = new JsonObject();
        range.addProperty("minimum", Math.max(0, minimum));
        range.addProperty("maximum", Math.max(0, minimum));
        yield range;
      }
      case STRING, MULTILINE, ITEM, BLOCK, ENUM -> new JsonPrimitive("");
      case COLOR -> new JsonPrimitive(Color.WHITE.getRGB());
      case LIST, SET -> new JsonArray();
      case GROUP, SECTION -> JsonNull.INSTANCE;
    };
  }

  private static int color(JsonElement value) {
    Color color = new Color(value.getAsInt(), true);
    return ImColor.rgba(color);
  }
}
