package dev.lifus.janetreborn.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Services {
  private final Map<Class<?>, Object> services = new LinkedHashMap<>();

  public <S> S register(Class<S> type, S service) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(service, "service");
    if (!type.isInstance(service)) {
      throw new IllegalArgumentException(
          service.getClass().getName() + " is not a " + type.getName());
    }
    if (services.putIfAbsent(type, service) != null) {
      throw new IllegalArgumentException("Service already registered: " + type.getName());
    }
    return service;
  }

  public <S> S register(S service) {
    Objects.requireNonNull(service, "service");
    @SuppressWarnings("unchecked")
    Class<S> type = (Class<S>) service.getClass();
    return register(type, service);
  }

  public <S> S require(Class<S> type) {
    return find(type)
        .orElseThrow(
            () -> new IllegalStateException("Service is not registered: " + type.getName()));
  }

  public <S> Optional<S> find(Class<S> type) {
    Objects.requireNonNull(type, "type");
    return Optional.ofNullable(type.cast(services.get(type)));
  }

  public boolean contains(Class<?> type) {
    return services.containsKey(Objects.requireNonNull(type, "type"));
  }

  public Collection<Class<?>> types() {
    return List.copyOf(services.keySet());
  }
}
