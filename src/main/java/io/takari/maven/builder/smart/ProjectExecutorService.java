package io.takari.maven.builder.smart;

import org.apache.maven.lifecycle.internal.BuildThreadFactory;
import org.apache.maven.project.MavenProject;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * {@link ThreadPoolExecutor} wrapper.
 * <p>
 * Uses {@link PriorityBlockingQueue} and provided {@link Comparator} to order queue
 * {@link ProjectRunnable} tasks.
 */
class ProjectExecutorService {

  interface ProjectRunnable extends Runnable {
    MavenProject getProject();
  }

  private class ProjectFutureTask extends FutureTask<MavenProject> implements ProjectRunnable {
    private final ProjectRunnable task;

    public ProjectFutureTask(ProjectRunnable task) {
      super(task, task.getProject());
      this.task = task;
    }

    @Override
    protected void done() {
      completion.add(this);
    }

    @Override
    public MavenProject getProject() {
      return task.getProject();
    }
  }

  private final ExecutorService executor;

  private final BlockingQueue<Future<MavenProject>> completion = new LinkedBlockingQueue<>();

  private final Comparator<Runnable> taskComparator;

  public ProjectExecutorService(final int degreeOfConcurrency,
      final Comparator<MavenProject> projectComparator) {

    this.taskComparator = (o1, o2) -> projectComparator.compare(((ProjectRunnable) o1).getProject(),
        ((ProjectRunnable) o2).getProject());

    final BlockingQueue<Runnable> executorWorkQueue =
        new PriorityBlockingQueue<>(degreeOfConcurrency, taskComparator);

    executor = new ThreadPoolExecutor(degreeOfConcurrency, // corePoolSize
        degreeOfConcurrency, // maximumPoolSize
        0L, TimeUnit.MILLISECONDS, // keepAliveTime, unit
        executorWorkQueue, // workQueue
        new BuildThreadFactory() // threadFactory
        ) {

          @Override
          protected void beforeExecute(Thread t, Runnable r) {
            ProjectExecutorService.this.beforeExecute(t, r);
          }
        };
  }

  public void submitAll(final Collection<? extends ProjectRunnable> tasks) {
    // when there are available worker threads, tasks are immediately executed, i.e. bypassed the
    // ordered queued. need to sort tasks, such that submission order matches desired execution
    // order
    Optional.ofNullable(tasks).ifPresent(localTasks -> {
      Stream.of(localTasks).flatMap(Collection::stream).sorted(taskComparator).forEach(task -> executor.execute(new ProjectFutureTask(task)));
    });
  }

  /**
   * Returns {@link MavenProject} corresponding to the next completed task, waiting if none are yet
   * present.
   */
  public MavenProject take() throws InterruptedException, ExecutionException {
    return completion.take().get();
  }

  public void shutdown() {
    executor.shutdown();
  }

  // hook to allow pausing executor during unit tests
  protected void beforeExecute(Thread t, Runnable r) {}

  // for testing purposes only
  public void awaitShutdown() throws InterruptedException {
    executor.shutdown();
    while (!executor.awaitTermination(5, TimeUnit.SECONDS));
  }
}
