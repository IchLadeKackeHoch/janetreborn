package dev.codeman.eventbusx.benchmark;

import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.EventBus;
import dev.codeman.eventbusx.Listener;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class ConcurrentAccessBenchmark {
  private final ConcurrentEvent event = new ConcurrentEvent(42);
  private EventBus optimized;
  private BaselineEventBus baseline;
  private volatile long optimizedSink;
  private volatile long baselineSink;

  @Setup
  public void setup() {
    optimized = new EventBus();
    baseline = new BaselineEventBus();
    Listener<ConcurrentEvent> optimizedListener = value -> optimizedSink = value.value;
    Listener<ConcurrentEvent> baselineListener = value -> baselineSink = value.value;
    for (int index = 0; index < 8; index++) {
      optimized.subscribe(ConcurrentEvent.class, optimizedListener);
      baseline.subscribe(ConcurrentEvent.class, baselineListener);
    }
  }

  @Benchmark
  @Group("optimized")
  @GroupThreads(4)
  public long optimizedPublish() {
    optimized.publish(event);
    return optimizedSink;
  }

  @Benchmark
  @Group("optimized")
  @GroupThreads(1)
  public void optimizedMutate() {
    optimized.subscribe(ConcurrentEvent.class, ignored -> {}).close();
  }

  @Benchmark
  @Group("baseline")
  @GroupThreads(4)
  public long baselinePublish() {
    baseline.publish(event);
    return baselineSink;
  }

  @Benchmark
  @Group("baseline")
  @GroupThreads(1)
  public void baselineMutate() {
    baseline.subscribe(ConcurrentEvent.class, ignored -> {}).close();
  }

  private static final class ConcurrentEvent extends Event {
    private final long value;

    private ConcurrentEvent(long value) {
      this.value = value;
    }
  }
}
