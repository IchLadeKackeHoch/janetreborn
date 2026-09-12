package dev.lifus.janetreborn.feature.misc;

import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;

public final class PackSpoofModule extends Module {
  public PackSpoofModule() {
    super(
        "PackSpoof",
        "Acknowledges server resource packs without downloading or applying them",
        Category.MISC);
  }
}
