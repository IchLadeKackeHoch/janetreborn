package dev.lifus.janetreborn.scripting;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ScriptManifest(
    int manifestVersion,
    String id,
    String name,
    String description,
    String author,
    String version,
    String sdk,
    String entry,
    Set<ScriptCapability> capabilities,
    List<String> dependencies,
    int schemaVersion) {
  public ScriptManifest {
    id = Objects.requireNonNull(id, "id");
    name = Objects.requireNonNull(name, "name");
    description = Objects.requireNonNull(description, "description");
    author = Objects.requireNonNull(author, "author");
    version = Objects.requireNonNull(version, "version");
    sdk = Objects.requireNonNull(sdk, "sdk");
    entry = Objects.requireNonNull(entry, "entry");
    capabilities = Set.copyOf(capabilities);
    dependencies = List.copyOf(dependencies);
  }

  public boolean requiresApproval() {
    return capabilities.stream().anyMatch(ScriptCapability::isSensitive);
  }
}
