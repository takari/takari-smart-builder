package io.takari.maven.builder.smart;

import java.util.*;
import java.util.concurrent.*;

import org.apache.maven.lifecycle.internal.BuildThreadFactory;
import org.apache.maven.project.MavenProject;

/**
 * {@link ThreadPoolExecutor} wrapper.
 * <p>
 * Uses {@link PriorityBlockingQueue} and provided {@link Comparator} to order queue
 * {@link ProjectRunnable} tasks.
 * <p>
 * Maintains a queue of {@link MavenProject} that correspond to completed tasks, see {@link #take()}.
 */
class ProjectExecutorService {

  static interface ProjectRunnable extends Runnable {
    public MavenProject getProject();
  }

  private class ProjectFutureTask<V> extends FutureTask<V> implements ProjectRunnable {
    private ProjectRunnable task;

    public ProjectFutureTask(Runnable task) {
      super(task, null);
      this.task = (ProjectRunnable) task;
    }

    @Override
    protected void done() {
      completion.add(task.getProject());
    }

    @Override
    public MavenProject getProject() {
      return task.getProject();
    }
  };

  private final ExecutorService executor;

  private final BlockingQueue<MavenProject> completion = new LinkedBlockingQueue<>();

  private final Comparator<Runnable> taskComparator;

  public ProjectExecutorService(final int degreeOfConcurrency,
      final Comparator<MavenProject> projectComparator) {

    this.taskComparator = new Comparator<Runnable>() {
      @Override
      public int compare(Runnable o1, Runnable o2) {
        return projectComparator.compare(((ProjectRunnable) o1).getProject(),
            ((ProjectRunnable) o2).getProject());
      }
    };

    final BlockingQueue<Runnable> executorWorkQueue =
        new PriorityBlockingQueue<>(degreeOfConcurrency, taskComparator);

    executor = new ThreadPoolExecutor(degreeOfConcurrency, // corePoolSize
        degreeOfConcurrency, // maximumPoolSize
        0L, TimeUnit.MILLISECONDS, // keepAliveTime, unit
        executorWorkQueue, // workQueue
        new BuildThreadFactory() // threadFactory
        ) {

          @Override
          protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            return new ProjectFutureTask<T>(runnable);
          }

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
    ArrayList<ProjectRunnable> sorted = new ArrayList<>(tasks);
    Collections.sort(sorted, taskComparator);
    for (ProjectRunnable task : sorted) {
      executor.submit(task, null);
    }
  }

  /**
   * Returns {@link MavenProject} corresponding to the next completed task, waiting if none are yet
   * present.
   */
  public MavenProject take() throws InterruptedException {
    return completion.take();
  }

  /**
   * Waits for all running tasks to complete and shuts down the executor and
   */
  public void shutdown() throws InterruptedException {
    executor.shutdown();
    while (!executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  // hook to allow pausing executor during unit tests
  protected void beforeExecute(Thread t, Runnable r) {}
}
