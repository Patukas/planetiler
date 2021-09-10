package com.onthegomap.flatmap.stats;

import static io.prometheus.client.Collector.NANOSECONDS_PER_SECOND;

import com.onthegomap.flatmap.util.LogUtil;
import com.onthegomap.flatmap.util.MemoryEstimator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A utility that collects and reports more detailed statistics about the JVM and running tasks than logs can convey.
 * <p>
 * {@link #inMemory()} stores basic stats in-memory to report at the end of the job and {@link
 * #prometheusPushGateway(String, String, Duration)} pushes stats at a regular interval to a <a
 * href="https://github.com/prometheus/pushgateway">prometheus push gateway</a>.
 */
public interface Stats extends AutoCloseable {

  /** Returns a new stat collector that stores basic stats in-memory to report through {@link #printSummary()}. */
  static Stats inMemory() {
    return new InMemory();
  }

  /**
   * Returns a new stat collector pushes stats at a regular interval to a <a href="https://github.com/prometheus/pushgateway">prometheus
   * push gateway</a> at {@code destination}.
   */
  static Stats prometheusPushGateway(String destination, String job, Duration interval) {
    return PrometheusStats.createAndStartPushing(destination, job, interval);
  }

  /** Logs top-level stats at the end of a job like the amount of user and CPU time that each task has taken. */
  default void printSummary() {
    timers().printSummary();
  }

  /**
   * Records that a long-running task with {@code name} has started and returns a handle to call when finished.
   * <p>
   * Also sets the "stage" prefix that shows up in the logs to {@code name}.
   */
  default Timers.Finishable startStage(String name) {
    LogUtil.setStage(name);
    var timer = timers().startTimer(name);
    return () -> {
      timer.stop();
      LogUtil.clearStage();
    };
  }

  /**
   * Records that {@code numFeatures} features have been rendered to an output {@code layer} while processing a data
   * source.
   */
  void emittedFeatures(int z, String layer, int numFeatures);

  /** Records that an input element was processed and emitted some output features in {@code layer}. */
  void processedElement(String elemType, String layer);

  /** Records that a tile has been written to the mbtiles output where compressed size is {@code bytes}. */
  void wroteTile(int zoom, int bytes);

  /** Returns the timers for all stages started with {@link #startStage(String)}. */
  Timers timers();

  /** Adds a stat that will track the size of a file or directory located at {@code path}. */
  void monitorFile(String name, Path path);

  /** Adds a stat that will track the estimated in-memory size of {@code object}. */
  void monitorInMemoryObject(String name, MemoryEstimator.HasEstimate object);

  /** Tracks a stat with {@code name} that always has a constant {@value}. */
  default void gauge(String name, Number value) {
    gauge(name, () -> value);
  }

  /** Tracks a stat with {@code name} that can go up or down over time. */
  void gauge(String name, Supplier<Number> value);

  /** Tracks a stat with {@code name} that should only go up over time. */
  void counter(String name, Supplier<Number> supplier);

  /**
   * Returns and starts tracking a new counter with {@code name} optimized for the caller to increment from multiple
   * threads.
   */
  default Counter.MultiThreadCounter longCounter(String name) {
    Counter.MultiThreadCounter counter = Counter.newMultiThreadCounter();
    counter(name, counter::get);
    return counter;
  }

  /**
   * Returns and starts tracking a new counter where the value should be treated as number of milliseconds.
   */
  default Counter.MultiThreadCounter nanoCounter(String name) {
    Counter.MultiThreadCounter counter = Counter.newMultiThreadCounter();
    counter(name, () -> counter.get() / NANOSECONDS_PER_SECOND);
    return counter;
  }

  /**
   * Tracks a group of counters with a {@code label} key that is set to the key in each entry of the map returned from
   * {@code values}.
   */
  void counter(String name, String label, Supplier<Map<String, Counter.Readable>> values);

  /**
   * Records that an invalid input feature was discarded where {@code errorCode} can be used to identify the kind of
   * failure.
   */
  void dataError(String errorCode);

  /**
   * A stat collector that stores top-level metrics in-memory to report through {@link #printSummary()}.
   * <p>
   * Returns counters for the code to use, but doesn't keep a central listing of them.
   */
  class InMemory implements Stats {

    /** use {@link #inMemory()} */
    private InMemory() {
    }

    private final Timers timers = new Timers();

    @Override
    public void wroteTile(int zoom, int bytes) {
    }

    @Override
    public Timers timers() {
      return timers;
    }

    @Override
    public void monitorFile(String name, Path path) {
    }

    @Override
    public void monitorInMemoryObject(String name, MemoryEstimator.HasEstimate object) {
    }

    @Override
    public void counter(String name, Supplier<Number> supplier) {
    }

    @Override
    public Counter.MultiThreadCounter longCounter(String name) {
      return Counter.newMultiThreadCounter();
    }

    @Override
    public Counter.MultiThreadCounter nanoCounter(String name) {
      return Counter.newMultiThreadCounter();
    }

    @Override
    public void counter(String name, String label, Supplier<Map<String, Counter.Readable>> values) {
    }

    @Override
    public void processedElement(String elemType, String layer) {
    }

    @Override
    public void dataError(String errorCode) {
    }

    @Override
    public void gauge(String name, Supplier<Number> value) {
    }

    @Override
    public void emittedFeatures(int z, String layer, int numFeatures) {
    }

    @Override
    public void close() {

    }
  }
}
