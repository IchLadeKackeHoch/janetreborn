package dev.lifus.janetreborn.service.friend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lifus.janetreborn.client.JanetClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;

public final class FriendStore {
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Path path;
  private final Map<UUID, String> friends = new LinkedHashMap<>();
  private volatile boolean active;

  public FriendStore() {
    this(
        FabricLoader.getInstance()
            .getConfigDir()
            .resolve(JanetClient.MOD_ID)
            .resolve("friends.json"));
  }

  FriendStore(Path path) {
    this.path = Objects.requireNonNull(path, "path");
  }

  public synchronized void init() {
    friends.clear();
    if (!Files.exists(path)) return;
    try (Reader reader = Files.newBufferedReader(path)) {
      JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
      JsonArray entries = root.getAsJsonArray("friends");
      if (entries == null) return;
      for (var element : entries) {
        JsonObject entry = element.getAsJsonObject();
        UUID uuid = UUID.fromString(entry.get("uuid").getAsString());
        friends.put(uuid, normalizedName(entry.get("name").getAsString(), uuid));
      }
    } catch (IOException | RuntimeException ignored) {
      friends.clear();
    }
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public synchronized boolean isFriend(UUID uuid) {
    return friends.containsKey(uuid);
  }

  public boolean isProtected(Player player) {
    return active && player != null && isFriend(player.getUUID());
  }

  public synchronized boolean toggle(Player player) {
    Objects.requireNonNull(player, "player");
    UUID uuid = player.getUUID();
    if (friends.remove(uuid) == null) {
      friends.put(uuid, normalizedName(player.getName().getString(), uuid));
      save();
      return true;
    }
    save();
    return false;
  }

  public synchronized boolean add(UUID uuid, String name) {
    Objects.requireNonNull(uuid, "uuid");
    String previous = friends.put(uuid, normalizedName(name, uuid));
    if (Objects.equals(previous, friends.get(uuid))) return false;
    save();
    return true;
  }

  public synchronized boolean remove(UUID uuid) {
    if (friends.remove(Objects.requireNonNull(uuid, "uuid")) == null) return false;
    save();
    return true;
  }

  public synchronized boolean clear() {
    if (friends.isEmpty()) return false;
    friends.clear();
    save();
    return true;
  }

  public synchronized List<Friend> getFriends() {
    return friends.entrySet().stream()
        .map(entry -> new Friend(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparing(Friend::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(friend -> friend.uuid().toString()))
        .toList();
  }

  public synchronized void save() {
    JsonObject root = new JsonObject();
    JsonArray entries = new JsonArray();
    for (Friend friend : getFriends()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("uuid", friend.uuid().toString());
      entry.addProperty("name", friend.name());
      entries.add(entry);
    }
    root.add("friends", entries);
    try {
      Files.createDirectories(path.getParent());
      Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(temporary)) {
        gson.toJson(root, writer);
      }
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ignored) {
    }
  }

  private static String normalizedName(String name, UUID uuid) {
    String normalized = name == null ? "" : name.trim();
    return normalized.isEmpty() ? uuid.toString() : normalized;
  }

  public record Friend(UUID uuid, String name) {}
}
