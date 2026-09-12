package dev.codeman.eventbusx.benchmark;

import dev.codeman.eventbusx.Event;

final class BenchmarkEvents {
  static final Event[] EVENTS = {
    new Event0(), new Event1(), new Event2(), new Event3(),
    new Event4(), new Event5(), new Event6(), new Event7()
  };

  @SuppressWarnings("unchecked")
  static final Class<? extends Event>[] TYPES =
      new Class[] {
        Event0.class, Event1.class, Event2.class, Event3.class,
        Event4.class, Event5.class, Event6.class, Event7.class
      };

  private BenchmarkEvents() {}

  private static final class Event0 extends Event {}

  private static final class Event1 extends Event {}

  private static final class Event2 extends Event {}

  private static final class Event3 extends Event {}

  private static final class Event4 extends Event {}

  private static final class Event5 extends Event {}

  private static final class Event6 extends Event {}

  private static final class Event7 extends Event {}
}
