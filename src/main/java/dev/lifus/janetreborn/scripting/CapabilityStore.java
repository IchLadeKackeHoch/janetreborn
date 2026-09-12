package dev.lifus.janetreborn.scripting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CapabilityStore {
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final Path path;
  private JsonObject grants = new JsonObject();

  public CapabilityStore(Path path) {
    this.path = path.toAbsolutePath().normalize();
    load();
  }

  public synchronized Set<ScriptCapability> missing(ScriptManifest manifest) {
    Set<ScriptCapability> missing = new LinkedHashSet<>();
    JsonObject grant = grants.getAsJsonObject(manifest.id());
    String fingerprint = fingerprint(manifest);
    JsonArray approved = grant == null ? null : grant.getAsJsonArray("capabilities");
    boolean sameIdentity =
        grant != null
            && grant.has("fingerprint")
            && fingerprint.equals(grant.get("fingerprint").getAsString());
    for (ScriptCapability capability : manifest.capabilities()) {
      if (!capability.isSensitive()) continue;
      boolean present = false;
      if (sameIdentity && approved != null) {
        for (var value : approved) {
          if (capability.manifestName().equals(value.getAsString())) present = true;
        }
      }
      if (!present) missing.add(capability);
    }
    return Set.copyOf(missing);
  }

  public synchronized void approve(ScriptManifest manifest, Set<ScriptCapability> capabilities)
      throws IOException {
    if (!manifest.capabilities().containsAll(capabilities)) {
      throw new IllegalArgumentException("Cannot grant undeclared capabilities");
    }
    JsonObject grant = new JsonObject();
    grant.addProperty("fingerprint", fingerprint(manifest));
    JsonArray approved = new JsonArray();
    capabilities.stream()
        .filter(ScriptCapability::isSensitive)
        .map(ScriptCapability::manifestName)
        .sorted()
        .forEach(approved::add);
    grant.add("capabilities", approved);
    grants.add(manifest.id(), grant);
    save();
  }

  public synchronized void revoke(String scriptId) throws IOException {
    grants.remove(scriptId);
    save();
  }

  public static String fingerprint(ScriptManifest manifest) {
    String identity =
        String.join(
            "\n",
            manifest.id(),
            manifest.name(),
            manifest.author(),
            manifest.entry(),
            manifest.capabilities().stream()
                .map(ScriptCapability::manifestName)
                .sorted()
                .toList()
                .toString());
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void load() {
    if (!Files.isRegularFile(path)) return;
    try (Reader reader = Files.newBufferedReader(path)) {
      JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
      grants = root;
    } catch (IOException | RuntimeException ignored) {
      grants = new JsonObject();
    }
  }

  private void save() throws IOException {
    Files.createDirectories(path.getParent());
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporary)) {
      gson.toJson(grants, writer);
    }
    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
  }
}
