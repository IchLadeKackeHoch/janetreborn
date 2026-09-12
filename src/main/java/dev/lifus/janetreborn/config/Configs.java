package dev.lifus.janetreborn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.lifus.janetreborn.client.JanetClient;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import opsec.misuyaka.sdk.Modules;

public final class Configs {
  private static final Pattern INVALID_NAME = Pattern.compile("[^a-zA-Z0-9 _-]");
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final ModuleCodec codec;
  private final Path directory;
  private final Path configs;
  private final Path modules;
  private boolean closed;

  public Configs(Modules modules) {
    this(modules, FabricLoader.getInstance().getConfigDir().resolve(JanetClient.MOD_ID));
  }

  public Configs(Modules modules, Path directory) {
    codec = new ModuleCodec(gson, modules);
    this.directory = directory.toAbsolutePath().normalize();
    configs = this.directory.resolve("configs");
    this.modules = this.directory.resolve("modules.json");
  }

  public void init() {
    try {
      Files.createDirectories(configs);
      if (Files.exists(modules)) load(modules);
      else saveModules();
    } catch (IOException ignored) {
    }
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "janet-reborn-config"));
  }

  public synchronized void saveModules() {
    if (closed) return;
    save(modules);
  }

  public synchronized boolean reloadModules() {
    return !closed && Files.isRegularFile(modules) && load(modules);
  }

  public Path directory() {
    return directory;
  }

  public synchronized void shutdown() {
    if (closed) return;
    save(modules);
    closed = true;
  }

  public synchronized String saveUserConfig(String requestedName) {
    String name = normalizeName(requestedName);
    return name != null && save(configPath(name)) ? name : null;
  }

  public synchronized boolean loadUserConfig(String name) {
    String normalized = normalizeName(name);
    return normalized != null && load(configPath(normalized));
  }

  public synchronized boolean renameUserConfig(String oldName, String requestedName) {
    String oldNormalized = normalizeName(oldName);
    String newNormalized = normalizeName(requestedName);
    if (oldNormalized == null || newNormalized == null || oldNormalized.equals(newNormalized))
      return false;
    Path source = configPath(oldNormalized);
    Path destination = configPath(newNormalized);
    if (!Files.exists(source) || Files.exists(destination)) return false;
    try {
      Files.move(source, destination);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public synchronized boolean deleteUserConfig(String name) {
    String normalized = normalizeName(name);
    if (normalized == null) return false;
    try {
      return Files.deleteIfExists(configPath(normalized));
    } catch (IOException e) {
      return false;
    }
  }

  public synchronized String[] getUserConfigs() {
    try (var files = Files.list(configs)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
          .sorted()
          .toArray(String[]::new);
    } catch (IOException e) {
      return new String[0];
    }
  }

  private boolean save(Path path) {
    try {
      Files.createDirectories(path.getParent());
      Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(temporary)) {
        gson.toJson(codec.encode(), writer);
      }
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private boolean load(Path path) {
    try (Reader reader = Files.newBufferedReader(path)) {
      return codec.decode(JsonParser.parseReader(reader).getAsJsonObject());
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private Path configPath(String name) {
    return configs.resolve(name + ".json");
  }

  private static String normalizeName(String requestedName) {
    if (requestedName == null) return null;
    String name = requestedName.trim();
    if (name.toLowerCase().endsWith(".json")) name = name.substring(0, name.length() - 5).trim();
    name = INVALID_NAME.matcher(name).replaceAll("").trim();
    if (name.isEmpty()) return null;
    return name.length() > 64 ? name.substring(0, 64).trim() : name;
  }
}
