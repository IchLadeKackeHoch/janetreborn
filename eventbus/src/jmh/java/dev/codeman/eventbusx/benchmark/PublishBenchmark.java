package dev.codeman.eventbusx.benchmark;

import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.EventBus;
import dev.codeman.eventbusx.Listener;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class PublishBenchmark {
  @Param({"0", "1", "8", "32"})
  private int listenersPerType;

  @Param({"1", "4", "8"})
  private int eventTypes;

  private EventBus optimized;
  private BaselineEventBus baseline;
  private Event event;
  private long optimizedSink;
  private long baselineSink;

  @Setup
  public void setup() {
    optimized = new EventBus();
    baseline = new BaselineEventBus();
    event = BenchmarkEvents.EVENTS[0];
    Listener<Event> optimizedListener = ignored -> optimizedSink++;
    Listener<Event> baselineListener = ignored -> baselineSink++;
    for (int typeIndex = 0; typeIndex < eventTypes; typeIndex++) {
      Class<? extends Event> type = BenchmarkEvents.TYPES[typeIndex];
      for (int listenerIndex = 0; listenerIndex < listenersPerType; listenerIndex++) {
        subscribe(optimized, type, optimizedListener);
        subscribe(baseline, type, baselineListener);
      }
    }
  }

  @Benchmark
  public long optimizedPublish() {
    optimized.publish(event);
    return optimizedSink;
  }

  @Benchmark
  public long baselinePublish() {
    baseline.publish(event);
    return baselineSink;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void subscribe(
      EventBus bus, Class<? extends Event> type, Listener<Event> listener) {
    bus.subscribe((Class) type, (Listener) listener);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void subscribe(
      BaselineEventBus bus, Class<? extends Event> type, Listener<Event> listener) {
    bus.subscribe((Class) type, (Listener) listener);
  }
}
