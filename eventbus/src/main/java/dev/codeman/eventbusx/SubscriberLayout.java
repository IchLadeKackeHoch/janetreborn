package dev.codeman.eventbusx;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SubscriberLayout {
  private final SubscriberField[] fields;

  private SubscriberLayout(SubscriberField[] fields) {
    this.fields = fields;
  }

  static SubscriberLayout inspect(Class<?> subscriberType) {
    List<SubscriberField> fields = new ArrayList<>();
    for (Field field : subscriberType.getDeclaredFields()) {
      Subscribe subscribe = field.getAnnotation(Subscribe.class);
      if (subscribe == null || !Listener.class.isAssignableFrom(field.getType())) continue;
      if (!field.trySetAccessible()) {
        throw new IllegalStateException("Cannot access listener " + field.getName());
      }
      fields.add(new SubscriberField(field, eventType(field), subscribe.priority()));
    }
    return new SubscriberLayout(fields.toArray(SubscriberField[]::new));
  }

  Handler[] bind(Object subscriber) {
    Handler[] handlers = new Handler[fields.length];
    for (int index = 0; index < fields.length; index++) {
      handlers[index] = fields[index].bind(subscriber);
    }
    return handlers;
  }

  private static Class<? extends Event> eventType(Field field) {
    Type genericType = field.getGenericType();
    if (!(genericType instanceof ParameterizedType parameterized)) {
      throw new IllegalStateException("Listener must declare an event type: " + field.getName());
    }
    Type argument = parameterized.getActualTypeArguments()[0];
    if (!(argument instanceof Class<?> type) || !Event.class.isAssignableFrom(type)) {
      throw new IllegalStateException("Invalid event type on listener " + field.getName());
    }
    return type.asSubclass(Event.class);
  }

  private record SubscriberField(Field field, Class<? extends Event> type, int priority) {
    @SuppressWarnings("unchecked")
    private Handler bind(Object subscriber) {
      try {
        Listener<Event> listener =
            (Listener<Event>) Objects.requireNonNull(field.get(subscriber), field.getName());
        return new Handler(subscriber, type, priority, listener);
      } catch (IllegalAccessException exception) {
        throw new IllegalStateException("Cannot read listener " + field.getName(), exception);
      }
    }
  }
}
