package io.takari.maven.builder.smart;

import io.takari.maven.builder.smart.BuildMetrics;
import io.takari.maven.builder.smart.BuildMetrics.Timer;

import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/**
 * Responsible for testing the services {@link BuildMetrics} provides.
 * 
 * @author Brian Toal
 *
 */
public class BuildMetricsTest extends TestCase {

  public void testMetricStopwatch() throws InterruptedException {
    BuildMetrics bm = new BuildMetrics();

    bm.start(Timer.WALLTIME_MS);
    Thread.sleep(1);

    // Validate timer is running.
    assertTrue(bm.getMetricElapsedTime(Timer.WALLTIME_MS, TimeUnit.MILLISECONDS) >= 1);
    assertTrue(bm.getMetricMillis(Timer.WALLTIME_MS) >= 1);

    Thread.sleep(1);
    bm.stop(Timer.WALLTIME_MS);

    assertTrue(bm.getMetricElapsedTime(Timer.WALLTIME_MS, TimeUnit.MILLISECONDS) >= 2);
    assertTrue(bm.getMetricMillis(Timer.WALLTIME_MS) >= 2);

    // Validate after the time has been stopped the associated metrics does not change.
    long before = bm.getMetricElapsedTime(Timer.WALLTIME_MS, TimeUnit.MILLISECONDS);
    Thread.sleep(1);
    assertEquals(before, bm.getMetricElapsedTime(Timer.WALLTIME_MS, TimeUnit.MILLISECONDS));
  }
}
