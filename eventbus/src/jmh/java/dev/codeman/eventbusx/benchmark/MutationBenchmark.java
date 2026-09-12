package dev.codeman.eventbusx.benchmark;

import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.EventBus;
import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscription;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
public class MutationBenchmark {
  private static final Listener<MutationEvent> LISTENER = ignored -> {};

  private EventBus optimized;
  private BaselineEventBus baseline;
  private Subscription optimizedSubscription;
  private Subscription baselineSubscription;

  @Setup(Level.Invocation)
  public void setup() {
    optimized = new EventBus();
    baseline = new BaselineEventBus();
    optimizedSubscription = optimized.subscribe(MutationEvent.class, LISTENER);
    baselineSubscription = baseline.subscribe(MutationEvent.class, LISTENER);
  }

  @Benchmark
  public Subscription optimizedSubscribe() {
    return optimized.subscribe(MutationEvent.class, LISTENER);
  }

  @Benchmark
  public Subscription baselineSubscribe() {
    return baseline.subscribe(MutationEvent.class, LISTENER);
  }

  @Benchmark
  public boolean optimizedUnsubscribe() {
    optimizedSubscription.close();
    return optimizedSubscription.isActive();
  }

  @Benchmark
  public boolean baselineUnsubscribe() {
    baselineSubscription.close();
    return baselineSubscription.isActive();
  }

  private static final class MutationEvent extends Event {}
}
