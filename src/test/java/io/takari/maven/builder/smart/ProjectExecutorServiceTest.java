package io.takari.maven.builder.smart;

import static io.takari.maven.builder.smart.ProjectComparator.id;
import io.takari.maven.builder.smart.ProjectExecutorService.ProjectRunnable;

import java.util.*;

import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.util.concurrent.Monitor;

public class ProjectExecutorServiceTest extends AbstractSmartBuilderTest {

  // copy&paste from ThreadPoolExecutor javadoc (use of Guava is a nice touch there)
  private static class PausibleProjectExecutorService extends ProjectExecutorService {

    private boolean isPaused = true;

    private final Monitor monitor = new Monitor();

    private final Monitor.Guard paused = new Monitor.Guard(monitor) {
      @Override
      public boolean isSatisfied() {
        return isPaused;
      }
    };

    private final Monitor.Guard notPaused = new Monitor.Guard(monitor) {
      @Override
      public boolean isSatisfied() {
        return !isPaused;
      }
    };

    public PausibleProjectExecutorService(int degreeOfConcurrency,
        Comparator<MavenProject> projectComparator) {
      super(degreeOfConcurrency, projectComparator);
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
      monitor.enterWhenUninterruptibly(notPaused);
      try {
        monitor.waitForUninterruptibly(notPaused);
      } finally {
        monitor.leave();
      }
    }

    public void resume() {
      monitor.enterIf(paused);
      try {
        isPaused = false;
      } finally {
        monitor.leave();
      }
    }
  }

  @Test
  public void testBuildOrder() throws Exception {
    final MavenProject a = newProject("a");
    final MavenProject b = newProject("b");
    final MavenProject c = newProject("c");
    TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
    graph.addDependency(b, a);

    HashMap<String, Long> serviceTimes = new HashMap<>();
    serviceTimes.put(id(a), 1L);
    serviceTimes.put(id(b), 1L);
    serviceTimes.put(id(c), 3L);

    Comparator<MavenProject> cmp = ProjectComparator.create(graph, serviceTimes);

    PausibleProjectExecutorService executor = new PausibleProjectExecutorService(1, cmp);

    final List<MavenProject> executed = new ArrayList<>();

    class TestProjectRunnable implements ProjectRunnable {
      private final MavenProject project;

      TestProjectRunnable(MavenProject project) {
        this.project = project;
      }

      @Override
      public void run() {
        executed.add(project);
      }

      @Override
      public MavenProject getProject() {
        return project;
      }
    }

    // the executor has single work thread and is paused
    // first task execution is blocked because the executor is paused
    // the subsequent tasks are queued and thus queue order can be asserted

    // this one gets stuck on the worker thread
    executor.submitAll(Collections.singleton(new TestProjectRunnable(a)));

    // these are queued and ordered
    executor.submitAll(Arrays.asList(new TestProjectRunnable(a), new TestProjectRunnable(b),
        new TestProjectRunnable(c)));

    executor.resume();
    executor.shutdown();

    Assert.assertEquals(Arrays.asList(a, c, a, b), executed);
  }
}
