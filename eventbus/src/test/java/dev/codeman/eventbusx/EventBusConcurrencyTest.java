package dev.codeman.eventbusx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class EventBusConcurrencyTest {
  @Test
  @Timeout(15)
  void publishesWhileSubscriptionsMutate() throws Exception {
    int publisherCount = 4;
    int publishesPerThread = 50_000;
    EventBus bus = new EventBus();
    AtomicInteger permanentCalls = new AtomicInteger();
    AtomicInteger transientCalls = new AtomicInteger();
    Subscription permanent =
        bus.subscribe(TestEvent.class, ignored -> permanentCalls.incrementAndGet());
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(publisherCount + 1)) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int thread = 0; thread < publisherCount; thread++) {
        tasks.add(
            executor.submit(
                () -> {
                  await(start);
                  TestEvent event = new TestEvent();
                  for (int index = 0; index < publishesPerThread; index++) bus.publish(event);
                }));
      }
      tasks.add(
          executor.submit(
              () -> {
                await(start);
                for (int index = 0; index < 10_000; index++) {
                  Subscription transientSubscription =
                      bus.subscribe(TestEvent.class, ignored -> transientCalls.incrementAndGet());
                  transientSubscription.close();
                }
              }));

      start.countDown();
      for (Future<?> task : tasks) task.get();
    }

    assertEquals(publisherCount * publishesPerThread, permanentCalls.get());
    assertTrue(transientCalls.get() >= 0);
    assertTrue(permanent.isActive());
  }

  @Test
  void concurrentCloseIsSafeAndVisible() throws Exception {
    EventBus bus = new EventBus();
    AtomicInteger calls = new AtomicInteger();
    Subscription subscription = bus.subscribe(TestEvent.class, ignored -> calls.incrementAndGet());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> closes = new ArrayList<>();
      for (int index = 0; index < 100; index++) {
        closes.add(executor.submit(subscription::close));
      }
      for (Future<?> close : closes) {
        close.get(2, TimeUnit.SECONDS);
      }
    }

    bus.publish(new TestEvent());
    assertFalse(subscription.isActive());
    assertEquals(0, calls.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static final class TestEvent extends Event {}
}
