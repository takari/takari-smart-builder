package io.takari.maven.builder.smart;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

// import net.jcip.annotations.GuardedBy;
// import net.jcip.annotations.ThreadSafe;

/**
 * Periodically writes current build stats to the log.
 */
class BuildProgressReportThread extends Thread implements SmartBuilderImpl.Listener {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  /**
   * Allows tracking of a time-averaged value. The client code informs the tracker of changes in the
   * concurrency level and can query the time averaged level at any time, or use {@link #toString()}
   * for a human representation of the average value.
   * <p>
   * The <em>time averaged value</em> is the average of some value, over time. For example, if the
   * value was 1 for 1 second and 10 for 0.5 seconds, the time-averaged value is (1 * 1) + (10 *
   * 0.5) / (1 + 0.5) = 4.
   *
   * @author Travis Downs
   * @author Brian Toal
   */

  // @ThreadSafe
  private static class TimeAveraged {

    // @GuardedBy("this")
    private long startNanos;

    // @GuardedBy("this")
    private long lastNanos; // timestamp of the last change to the averaged value

    // @GuardedBy("this")
    private double value; // current value

    // @GuardedBy("this")
    private long accumulatedNanos;

    // @GuardedBy("this")
    private double accumulatedProduct; // sum of nanos elapsed * value for all prior intervals

    // @GuardedBy("this")
    // private State state;

    // private enum State {
    // STARTED, STOPPED
    // };

    /**
     * Create a default, stopped, time averaged value.
     */
    public TimeAveraged() {
      // state = State.STOPPED;
    }

    /**
     * Start (or restart) the time averaged value tracker with the given value.
     * 
     * @return {@code this}
     */
    public synchronized TimeAveraged start(double value) {
      // state = State.STARTED;
      startNanos = lastNanos = now();
      accumulatedProduct = accumulatedNanos = 0;
      this.value = value;
      return this;
    }

    /**
     * Start (or restart) the time averaged value, using the existing value (if the value has been
     * set explicitly), or 0 if no value has been established.
     * 
     * @return {@code this}
     */
    public synchronized TimeAveraged start() {
      return start(value);
    }

    // tests can override the time source for deterministic results
    long now() {
      return System.nanoTime();
    }

    /**
     * Increment the TimeAveraged value by one.
     * 
     * @return {@code this}
     */
    public synchronized TimeAveraged increment() {
      setValue(value + 1);
      return this;
    }

    /**
     * Decrement the TimeAveraged value by one.
     * 
     * @return {@code this}
     */
    public synchronized TimeAveraged decrement() {
      setValue(value - 1);
      return this;
    }

    /**
     * Set the current value of this {@code TimeAveraged}
     */
    public synchronized void setValue(double newValue) {
      long now = now(), deltaNanos = now - lastNanos;
      accumulatedProduct += deltaNanos * value;
      accumulatedNanos += deltaNanos;
      lastNanos = now;
      value = newValue;
    }

    /**
     * @return the current underlying value (not the value averaged over time)
     */
    // public synchronized double currentValue() {
    // return value;
    // }

    /**
     * @return the time-averaged value of this instance since {@link #start(int)} was called
     */
    public double averagedValue() {
      double product, value;
      long nanos;
      synchronized (this) {
        setValue((value = this.value)); // make accumulated reflect the current interval, and start
                                        // a
                                        // new one with the same value
        product = accumulatedProduct;
        nanos = accumulatedNanos;
      }
      return nanos == 0 ? value : (product / nanos); // nanos == 0 conceivably occurs when a
                                                     // measurement is taken very rapidly after
                                                     // start
    }

    /**
     * @return the "time" this object was started as determined by {@code System.nanoTime()}
     */
    public synchronized long getStartNanos() {
      return startNanos;
    }

    /**
     * @return true if this time averaged value has been started
     */
    // public synchronized boolean isStarted() {
    // return state == State.STARTED;
    // }

    @Override
    public String toString() {
      return String.valueOf(averagedValue());
    }
  }


  private static class ConcurrencyTracker {

    private TimeAveraged overall, delta;

    private final ConcurrentMap<String, TimeAveraged> trackers =
        new ConcurrentHashMap<String, TimeAveraged>();

    public void startTask(String name) {
      SmartBuilderImpl.checkState(!trackers.containsKey(name), "duplicate task name: " + name);
      TimeAveraged ret = new TimeAveraged();
      synchronized (this) {
        ret.start(trackers.size());
        trackers.put(name, ret);
        for (TimeAveraged existing : trackers.values()) {
          existing.increment();
        }
        if (overall == null) {
          overall = new TimeAveraged().start(1);
          delta = new TimeAveraged().start(1);
        } else {
          overall.increment();
          delta.increment();
        }
        delta.hashCode();
      }

    }

    public long stopTask(String name) {
      overall.decrement();
      delta.decrement();
      synchronized (this) {
        TimeAveraged removed = trackers.remove(name);
        SmartBuilderImpl.checkState(removed != null,
            String.format("task isn't being tracked: %s", name));
        for (TimeAveraged existing : trackers.values()) {
          existing.decrement();
        }
        return System.nanoTime() - removed.getStartNanos();
      }
    }

    public ConcurrentMap<String, TimeAveraged> getTrackers() {
      return trackers;
    }

    public int executingCount() {
      return trackers.size();
    }

    public synchronized TimeAveraged getOverall() {
      return overall == null ? new TimeAveraged() : overall;
    }

    public synchronized TimeAveraged getDelta() {
      return delta == null ? new TimeAveraged() : delta;
    }
  }

  final ConcurrencyTracker executing = new ConcurrencyTracker();

  // count of all ready tasks waiting for available executor thread
  final AtomicInteger blockedProjects = new AtomicInteger();

  // count of all not ready tasks waiting for other projects to finish
  final AtomicInteger notReady;

  private static final int POLLER_SLEEP_MS = 500;

  // private final boolean CSV_OUTPUT =
  // Boolean.parseBoolean(System.getProperty("pimg.csv.output", "false"));
  // private final Once csvHeaderOnce = new Once();

  volatile boolean stop;

  private long startTimeMs;

  private final int degreeOfConcurrency;


  public BuildProgressReportThread(int projectCount, int degreeOfConcurrency) {
    super(BuildProgressReportThread.class.getName());
    this.degreeOfConcurrency = degreeOfConcurrency;
    this.startTimeMs = System.currentTimeMillis();
    this.notReady = new AtomicInteger(projectCount);

    setDaemon(true);
  }

  @Override
  public void run() {
    String lastKeyString = ""; // track the set of executing tasks to
                               // avoid many redundant log messages
    long backoffLeft = 1, backoffCount = 1;
    while (!stop) {
      int execCount = this.executing.executingCount();
      TimeAveraged delta = this.executing.getDelta();
      TimeAveraged overall = this.executing.getOverall();
      String elapsedMs = String.format("%6s (ms) : ", System.currentTimeMillis() - startTimeMs);

      String message =
          elapsedMs + "Executing=" + execCount + ", blocked=" + this.blockedProjects.get()
              + ", not ready=" + this.notReady.get() + ", delta conc=" + this.asPercent(delta)
              + "%, avg conc=" + this.asPercent(overall) + "%" + "\nLTB : " + elapsedMs
              + "Targets: [" + execString(this.executing.getTrackers()) + "]";

      // if (CSV_OUTPUT) {
      // if (csvHeaderOnce.get()) {
      // log("LTBCSV,time,executing,cdelta,cavg");
      // }
      // log(String.format("LTBCSV,%s,%s,%s,%s",
      // String.format("%.1f", timer.elapsed(TimeUnit.MILLISECONDS) /
      // 1000.0),
      // execCount,
      // asPercent(delta,1),
      // asPercent(overall,1)));
      // }

      String keyString = this.executing.getTrackers().keySet().toString();
      if (!lastKeyString.equals(keyString)) {
        backoffLeft = backoffCount = 1;
        logger.info(message);
        delta.start();
        lastKeyString = keyString;
      } else {
        if (backoffLeft-- <= 0) {
          logger.info(message);
          delta.start();
          backoffLeft = (backoffCount *= 2);
        }
      }

      try {
        Thread.sleep(POLLER_SLEEP_MS);
      } catch (InterruptedException e) {
        SmartBuilderImpl.checkState(stop, "IE recieved when not stopped");
      }
    }
  }

  private String execString(ConcurrentMap<String, TimeAveraged> executing) {
    long now = System.nanoTime();
    List<String> output = Lists.newArrayList();
    for (Entry<String, TimeAveraged> entry : executing.entrySet()) {
      TimeAveraged avg = entry.getValue();
      output.add(entry.getKey() + " (exec="
          + TimeUnit.NANOSECONDS.toMillis(now - avg.getStartNanos()) + "ms, conc="
          + this.asPercent(avg) + "%)");
    }
    return Joiner.on(", ").join(output);
  }

  public void terminate() {
    stop = true;
    this.interrupt();
  }

  String asPercent(TimeAveraged avg) {
    return asPercent(avg, 0);
  }

  private String asPercent(TimeAveraged avg, int precision) {
    return String.format("%." + precision + "f", 100 * avg.averagedValue() / degreeOfConcurrency);
  }

  @Override
  public void onReady(MavenProject project) {
    notReady.decrementAndGet();
    blockedProjects.incrementAndGet();
  }

  @Override
  public void onStart(MavenProject project) {
    blockedProjects.decrementAndGet();

    executing.startTask(project.getId());
  }

  @Override
  public void onFinish(MavenProject project) {
    executing.stopTask(project.getId());
  }

}
