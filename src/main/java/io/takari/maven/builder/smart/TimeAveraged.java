package io.takari.maven.builder.smart;

//import net.jcip.annotations.GuardedBy;
//import net.jcip.annotations.ThreadSafe;

/**
 * Allows tracking of a time-averaged value. The client code
 * informs the tracker of changes in the concurrency level and can query the 
 * time averaged level at any time, or use {@link #toString()} for a human
 * representation of the average value.
 * <p>
 * The <em>time averaged value</em> is the average of some value, over time.
 * For example, if the value was 1 for 1 second and 10 for 0.5 seconds, the time-averaged
 * value is (1 * 1) + (10 * 0.5) / (1 + 0.5) = 4.
 *
 * @author Travis Downs
 * @author Brian Toal
 */

//@ThreadSafe
public class TimeAveraged {

  //    @GuardedBy("this")
  private long startNanos;

  //    @GuardedBy("this")
  private long lastNanos; // timestamp of the last change to the averaged value

  //    @GuardedBy("this")
  private double value; // current value

  //    @GuardedBy("this")
  private long accumulatedNanos;

  //    @GuardedBy("this")
  private double accumulatedProduct; // sum of nanos elapsed * value for all prior intervals

  //    @GuardedBy("this")
  private State state;

  private enum State {
    STARTED, STOPPED
  };

  /**
   * Create a default, stopped, time averaged value.
   */
  public TimeAveraged() {
    state = State.STOPPED;
  }

  /**
   * Start (or restart) the time averaged value tracker with the given value.
   * @return {@code this}
   */
  public synchronized TimeAveraged start(double value) {
    state = State.STARTED;
    startNanos = lastNanos = now();
    accumulatedProduct = accumulatedNanos = 0;
    this.value = value;
    return this;
  }

  /**
   * Start (or restart) the time averaged value, using the existing value (if the value has been set explicitly),
   * or 0 if no value has been established.
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
   * @return {@code this}
   */
  public synchronized TimeAveraged increment() {
    setValue(value + 1);
    return this;
  }

  /**
   * Decrement the TimeAveraged value by one.
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
  public synchronized double currentValue() {
    return value;
  }

  /**
   * @return the time-averaged value of this instance since {@link #start(int)} was called
   */
  public double averagedValue() {
    double product, value;
    long nanos;
    synchronized (this) {
      setValue((value = this.value)); // make accumulated reflect the current interval, and start a new one with the same value
      product = accumulatedProduct;
      nanos = accumulatedNanos;
    }
    return nanos == 0 ? value : (product / nanos); // nanos == 0 conceivably occurs when a measurement is taken very rapidly after start
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
  public synchronized boolean isStarted() {
    return state == State.STARTED;
  }

  @Override
  public String toString() {
    return String.valueOf(averagedValue());
  }

}
