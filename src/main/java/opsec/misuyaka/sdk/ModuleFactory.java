package opsec.misuyaka.sdk;

import dev.lifus.janetreborn.api.ClientContext;

@FunctionalInterface
public interface ModuleFactory<M extends Module> {
  M create(ClientContext client);
}
