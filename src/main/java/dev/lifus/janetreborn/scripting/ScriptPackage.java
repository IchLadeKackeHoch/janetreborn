package dev.lifus.janetreborn.scripting;

import java.nio.file.Path;
import java.util.Objects;

public record ScriptPackage(Path root, Path manifestPath, ScriptManifest manifest) {
  public ScriptPackage {
    root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    manifestPath =
        Objects.requireNonNull(manifestPath, "manifestPath").toAbsolutePath().normalize();
    Objects.requireNonNull(manifest, "manifest");
  }

  public Path resolveOwned(String relative) {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root))
      throw new SecurityException("Path leaves script package: " + relative);
    return resolved;
  }

  public Path entryPoint() {
    return resolveOwned(manifest.entry());
  }
}
