package dev.codeman.eventbusx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventBusTest {
  @Test
  void publishesToTheExactEventType() {
    EventBus bus = new EventBus();
    AtomicInteger baseCalls = new AtomicInteger();
    AtomicInteger testCalls = new AtomicInteger();
    bus.subscribe(Event.class, ignored -> baseCalls.incrementAndGet());
    bus.subscribe(TestEvent.class, ignored -> testCalls.incrementAndGet());

    bus.publish(new TestEvent());

    assertEquals(0, baseCalls.get());
    assertEquals(1, testCalls.get());
  }

  @Test
  void dispatchesInStablePriorityOrder() {
    EventBus bus = new EventBus();
    List<String> calls = new ArrayList<>();
    bus.subscribe(TestEvent.class, Priority.LOW, ignored -> calls.add("low"));
    bus.subscribe(TestEvent.class, Priority.HIGH, ignored -> calls.add("high-first"));
    bus.subscribe(TestEvent.class, Priority.HIGH, ignored -> calls.add("high-second"));
    bus.subscribe(TestEvent.class, ignored -> calls.add("default"));

    bus.publish(new TestEvent());

    assertEquals(List.of("high-first", "high-second", "default", "low"), calls);
  }

  @Test
  void directSubscriptionCanBeClosedIdempotently() {
    EventBus bus = new EventBus();
    AtomicInteger calls = new AtomicInteger();
    Subscription subscription = bus.subscribe(TestEvent.class, ignored -> calls.incrementAndGet());

    bus.publish(new TestEvent());
    subscription.close();
    subscription.close();
    bus.publish(new TestEvent());

    assertEquals(1, calls.get());
    assertFalse(subscription.isActive());
  }

  @Test
  void annotatedObjectCanBeSubscribedAndUnsubscribed() {
    EventBus bus = new EventBus();
    AnnotatedSubscriber subscriber = new AnnotatedSubscriber();

    bus.subscribe(subscriber);
    bus.subscribe(subscriber);
    assertTrue(bus.isSubscribed(subscriber));
    bus.publish(new TestEvent());
    assertEquals(List.of("high", "low"), subscriber.calls);

    bus.unsubscribe(subscriber);
    assertFalse(bus.isSubscribed(subscriber));
    bus.publish(new TestEvent());
    assertEquals(List.of("high", "low"), subscriber.calls);
  }

  @Test
  void rejectsNullArguments() {
    EventBus bus = new EventBus();
    assertThrows(NullPointerException.class, () -> bus.publish(null));
    assertThrows(NullPointerException.class, () -> bus.subscribe(null));
    assertThrows(
        NullPointerException.class,
        () -> bus.subscribe(TestEvent.class, (Listener<TestEvent>) null));
  }

  private static final class TestEvent extends Event {}

  private static final class AnnotatedSubscriber {
    private final List<String> calls = new ArrayList<>();

    @Subscribe(priority = Priority.LOW)
    private final Listener<TestEvent> low = ignored -> calls.add("low");

    @Subscribe(priority = Priority.HIGH)
    private final Listener<TestEvent> high = ignored -> calls.add("high");
  }
}
