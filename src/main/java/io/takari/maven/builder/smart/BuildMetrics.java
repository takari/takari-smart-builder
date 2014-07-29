package io.takari.maven.builder.smart;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;

/**
 * Responsible for maintaining metrics related to a unit of work. The unit of work can be
 * characterized by total elapsed time it takes to complete the task. This is called wall clock
 * time. Wall time can be further characterized by the various by the time the unit of work spends
 * in each state. When the unit of work is started it's either in a running or waiting state. Time
 * corresponding to the running state is called service time, where time corresponding to the
 * waiting state is called queue time. Knowing how much time a unit of work spends in each state,
 * provides insight in to the behavior of the build and where opportunities to improve runtime
 * performance are.
 * 
 * {@code BuildMetrics}'s provides the services to record time spent in the various states and
 * should be tied to a unit of work. The {@link BuildMetrics#start} and {@link BuildMetrics#stop}
 * services used with the corresponding {@code Timer} provide the capability to accumulate time
 * towards the {@code Timer}.
 * 
 * @author Brian Toal
 *
 */
public class BuildMetrics implements Comparable<BuildMetrics> {
  public static enum Timer {
    WALLTIME_MS, QUEUETIME_MS, SERVICETIME_MS
  }

  private static class ThreadSafeStopwatch {

    private final Stopwatch stopwatch = new Stopwatch();

    public synchronized void start() {
      stopwatch.start();
    }

    public synchronized void stop() {
      stopwatch.stop();
    }

    public synchronized long elapsed(TimeUnit desiredUnit) {
      return stopwatch.elapsed(desiredUnit);
    }

  }

  private final Map<Timer, ThreadSafeStopwatch> timers;

  /**
   * Default constructor. Creates a {@link Stopwatch} for each value in {@code Timer}.
   */
  public BuildMetrics() {
    ConcurrentHashMap<Timer, ThreadSafeStopwatch> timers = new ConcurrentHashMap<>();
    for (Timer t : Timer.values()) {
      timers.put(t, new ThreadSafeStopwatch());
    }
    this.timers = Collections.unmodifiableMap(timers);
  }

  /**
   * Start the {@link Stopwatch} for the given {@code Timer}.
   * 
   * @param timer
   */
  public void start(Timer timer) {
    timers.get(timer).start();
  }

  /**
   * Stops the {@link Stopwatch} for the given {@code Timer}.
   * 
   * @param timer
   */
  public void stop(Timer timer) {
    timers.get(timer).stop();
  }

  /**
   * Returns the provided {@code timer} elapsed time in the unit specified by {@code desiredUnit}.
   * 
   * @param timer
   * @param desiredUnit
   * @return elapsed time in {@code desiredUnit}
   */
  public long getMetricElapsedTime(Timer timer, TimeUnit desiredUnit) {
    return timers.get(timer).elapsed(desiredUnit);
  }

  /**
   * Returns the provided {@code timer} elapsed time in milliseconds.
   * 
   * @param timer
   * @return elapsed time in milliseconds.
   */
  public long getMetricMillis(Timer timer) {
    return getMetricElapsedTime(timer, TimeUnit.MILLISECONDS);
  }

  @Override
  public String toString() {
    return String.format("wall time (ms) = %d, service time (ms) = %d, queue time (ms) = %d",
        getMetricMillis(Timer.WALLTIME_MS), getMetricMillis(Timer.SERVICETIME_MS),
        getMetricMillis(Timer.QUEUETIME_MS));
  }

  @Override
  public int compareTo(BuildMetrics bm) {
    return descCompare(bm);
  }

  private int descCompare(BuildMetrics bm) {
    long delta = this.getMetricMillis(Timer.WALLTIME_MS) - bm.getMetricMillis(Timer.WALLTIME_MS);
    return delta < 0 ? 1 : delta > 0 ? -1 : 0;
  }
}
