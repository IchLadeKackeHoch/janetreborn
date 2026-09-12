package dev.lifus.janetreborn.scripting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class ScriptStorage implements AutoCloseable {
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Path file;
  private JsonObject data = new JsonObject();
  private boolean dirty;
  private boolean closed;

  public ScriptStorage(Path storageDirectory, String scriptId) {
    Path root = storageDirectory.toAbsolutePath().normalize();
    file = root.resolve(scriptId + ".json").normalize();
    if (!file.startsWith(root)) throw new SecurityException("Invalid script storage id");
    load();
  }

  public synchronized Optional<JsonElement> get(String key) {
    requireOpen();
    JsonElement value = data.get(key);
    return value == null ? Optional.empty() : Optional.of(value.deepCopy());
  }

  public synchronized void put(String key, JsonElement value) {
    requireOpen();
    validateKey(key);
    data.add(key, value.deepCopy());
    dirty = true;
  }

  public synchronized void remove(String key) {
    requireOpen();
    validateKey(key);
    dirty |= data.remove(key) != null;
  }

  public synchronized void flush() throws IOException {
    requireOpen();
    if (!dirty) return;
    Files.createDirectories(file.getParent());
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporary)) {
      gson.toJson(data, writer);
    }
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    dirty = false;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    flush();
    closed = true;
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try (Reader reader = Files.newBufferedReader(file)) {
      JsonElement parsed = JsonParser.parseReader(reader);
      data = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    } catch (IOException | RuntimeException ignored) {
      data = new JsonObject();
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Storage is closed");
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 128) {
      throw new IllegalArgumentException("Storage key must contain 1-128 characters");
    }
  }
}
