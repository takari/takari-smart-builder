package io.takari.maven.builder.smart;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.takari.maven.builder.smart.BuildMetrics.Timer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.BuildThreadFactory;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.ProjectBuildList;
import org.apache.maven.lifecycle.internal.ReactorBuildStatus;
import org.apache.maven.lifecycle.internal.ReactorContext;
import org.apache.maven.lifecycle.internal.TaskSegment;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

/**
 * 
 * @author Brian Toal
 *
 */
@Singleton
@Named("smart")
@Component(role = Builder.class, hint = "smart")
public class SmartBuilder implements Builder {

  private final Logger logger = LoggerFactory.getLogger(SmartBuilder.class); 
  
  private final LifecycleModuleBuilder lifecycleModuleBuilder;

  @Inject
  public SmartBuilder(LifecycleModuleBuilder lifecycleModuleBuilder) {
    this.lifecycleModuleBuilder = lifecycleModuleBuilder;
  }

  private final ConcurrencyTracker executing = new ConcurrencyTracker();

  private final AtomicInteger blockedProjects = new AtomicInteger(); // count of all ready tasks

  private final AtomicInteger notReady = new AtomicInteger(); // all not ready tasks

  private int degreeOfConcurrency;

  private MavenSession session;

  private ReactorContext reactorContext;

  private ConcurrencyDependencyGraph analyzer;

  private CompletionService<MavenProject> service;

  private final ProjectsBuildMetrics projectsBuildMetrics = new ProjectsBuildMetrics();

  public void build(MavenSession session, ReactorContext reactorContext, ProjectBuildList projectBuilds, List<TaskSegment> taskSegments, ReactorBuildStatus reactorBuildStatus)
      throws ExecutionException, InterruptedException {

    ExecutorService executor = Executors.newFixedThreadPool(Math.min(session.getRequest().getDegreeOfConcurrency(), session.getProjects().size()), new BuildThreadFactory());
    CompletionService<MavenProject> service = new ExecutorCompletionService<MavenProject>(executor);
    ConcurrencyDependencyGraph analyzer = new ConcurrencyDependencyGraph(session.getProjectDependencyGraph());

    this.degreeOfConcurrency = Integer.valueOf(session.getRequest().getDegreeOfConcurrency());
    this.session = session;
    this.reactorContext = reactorContext;
    this.analyzer = analyzer;
    this.service = service;

    try {
      log("Task segments : " + Joiner.on(",").join(taskSegments));
      buildProjects(taskSegments);
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

  }

  private void buildProjects(List<TaskSegment> taskSegments) {
    Stopwatch timer = new Stopwatch();
    timer.start();

    Poller poller = new Poller();
    poller.start();

    // Eventually for smart scheduling we'll add a comparator to order tasks based on their runtime (longest first)
    PriorityQueue<MavenProject> readyQueue = new PriorityQueue<MavenProject>(10, new Comparator<MavenProject>() {
      public int compare(MavenProject o1, MavenProject o2) {
        return o1.hashCode() - o2.hashCode();
      }
    });

    log("Build maximum degree of concurrency is " + degreeOfConcurrency);
    log("Root level projects are " + Joiner.on(",").join(analyzer.getRootSchedulableBuilds()));

    addProjectsToReadyQueue(analyzer.getRootSchedulableBuilds(), readyQueue);

    int executing = 0;
    try {
      // for each finished project
      for (int i = 0; i < analyzer.getNumberOfBuilds(); i++) {
        printReadyQueue(readyQueue);

        // Schedule build when resources are available
        executing = scheduleReadyToRunProjects(taskSegments, readyQueue, executing);
        blockedProjects.set(readyQueue.size());

        // TODO Circular references are detected when the POM's are read and circularity check occurs.
        // TODO This is impossible to happen.
        if (executing <= 0) {
          loopInDependencyGraphFound(analyzer);
        }

        // Wait until the next project build finishes
        MavenProject finished = service.take().get();
        projectsBuildMetrics.stop(finished, Timer.WALLTIME_MS);
        log("Completed executing project " + finished.getName() + ", " + projectsBuildMetrics.getBuildMetrics(finished));

        executing--;

        if (reactorContext.getReactorBuildStatus().isHalted()) {
          // TODO is this the right thing to do?
          break;
        }

        addProjectsToReadyQueue(getUnblockedDownstreamProjects(finished), readyQueue);
      }
    } catch (InterruptedException e) {
      session.getResult().addException(e);
    } catch (ExecutionException e) {
      session.getResult().addException(e);
    } finally {
      poller.terminate();
      timer.stop();

      Stopwatch stopwatch = new Stopwatch();
      stopwatch.start();

      if (System.getProperty("maven.profile") != null) {
        Report report = new Report(projectsBuildMetrics);
        report.displayMetrics(timer, session, analyzer);
      }
      log("Report generation wall time (ms) = " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }
    // TODO : add timing metrics
    // TODO : write timing file
  }

  private void printReadyQueue(PriorityQueue<MavenProject> readyQueue) {
    if (!readyQueue.isEmpty()) {
      log("================================");
      for (MavenProject project : readyQueue) {
        log(project.getName());
      }
      log("--------------------------------");

    }
  }

  private void loopInDependencyGraphFound(ConcurrencyDependencyGraph analyzer) {
    for (MavenProject p : analyzer.getUnfinishedProjects()) {
      StringBuffer sb = new StringBuffer();
      sb.append("Remaining project " + p.getName() + " (" + analyzer.getActiveDependencies(p).size() + ")");

      for (MavenProject a : analyzer.getActiveDependencies(p)) {
        sb.append(" ").append(a.getName());
      }
      log(sb.toString());
    }
    throw new RuntimeException("Maven should have caught the circular dependencies when reading in the POM.  No more modules to execute, potentially a loop in the graph.");
  }

  private void addProjectsToReadyQueue(final List<MavenProject> projects, final PriorityQueue<MavenProject> readyQueue) {
    for (MavenProject project : projects) {
      log("adding " + project.getName() + " to ready queue.");

      projectsBuildMetrics.newProject(project);
      projectsBuildMetrics.start(project, Timer.WALLTIME_MS);
      projectsBuildMetrics.start(project, Timer.QUEUETIME_MS);

      readyQueue.add(project);
    }
  }

  private List<MavenProject> getUnblockedDownstreamProjects(MavenProject finished) {
    return analyzer.markAsFinished(finished);
  }

  private int scheduleReadyToRunProjects(List<TaskSegment> taskSegments, PriorityQueue<MavenProject> readyQueue, int executing) {
    while (!readyQueue.isEmpty() && (executing < degreeOfConcurrency)) {
      MavenProject next = readyQueue.remove();

      projectsBuildMetrics.stop(next, Timer.QUEUETIME_MS);

      log("Scheduling " + next.getName() + " for execution");
      service.submit(createBuildCallable(next, taskSegments));
      executing++;
    }
    return executing;
  }

  private Callable<MavenProject> createBuildCallable(final MavenProject project, final List<TaskSegment> taskSegments) {
    return new Callable<MavenProject>() {
      public MavenProject call() {
        projectsBuildMetrics.start(project, Timer.SERVICETIME_MS);
        // TODO add CPU usage tracking

        try {
          log("Starting " + project.getName());
          executing.startTask(project.getId());

          MavenSession copiedSession = session.clone();
          for (TaskSegment taskSegment : taskSegments) {

            // TODO Clone the session (it's possible for projects to
            // store state into the session)
            lifecycleModuleBuilder.buildProject(copiedSession, session, reactorContext, project, taskSegment);

          }
        } catch (RuntimeException ex) {
          // preserve the xml stack trace, and the java cause
          // chain
          throw new RuntimeException(project.getName() + ": " + ex.getMessage(), ex);
        } finally {
          // runtime =
          // Ints.checkedCast(TimeUnit.NANOSECONDS.toMillis(executing.stopTask(projectBuild.getId())));

          executing.stopTask(project.getId());
          log("Completed servicing " + project.getName() + " : " + projectsBuildMetrics.stop(project, Timer.SERVICETIME_MS) + " (ms).");
        }

        return project;
      }
    };
  }

  private String asPercent(TimeAveraged avg) {
    return asPercent(avg, 0);
  }

  private String asPercent(TimeAveraged avg, int precision) {
    return String.format("%." + precision + "f", 100 * avg.averagedValue() / degreeOfConcurrency);
  }

  private void log(final String s) {
    if (System.getProperty("maven.profile") != null) {
      logger.info("Smart Builder : " + s);
    }
  }

  private static void checkState(boolean b, String string) {
    if (!b) {
      throw new RuntimeException(string);
    }
  }

  private class Poller extends Thread {
    private static final int POLLER_SLEEP_MS = 500;

    // private final boolean CSV_OUTPUT =
    // Boolean.parseBoolean(System.getProperty("pimg.csv.output", "false"));
    // private final Once csvHeaderOnce = new Once();

    volatile boolean stop;

    private long startTimeMs;

    public Poller() {
      super(Poller.class.getName());
      setDaemon(true);
      startTimeMs = System.currentTimeMillis();
    }

    @Override
    public void run() {
      String lastKeyString = ""; // track the set of executing tasks to
                                 // avoid many redundant log messages
      long backoffLeft = 1, backoffCount = 1;
      while (!stop) {
        int execCount = executing.executingCount();
        TimeAveraged delta = executing.getDelta();
        TimeAveraged overall = executing.getOverall();
        String elapsedMs = String.format("%6s (ms) : ", System.currentTimeMillis() - startTimeMs);

        String message = elapsedMs + "Executing=" + execCount + ", blocked=" + blockedProjects.get() + ", not ready=" + notReady.get() + ", delta conc=" + asPercent(delta) + "%, avg conc="
            + asPercent(overall) + "%" + "\nLTB : " + elapsedMs + "Targets: [" + execString(executing.getTrackers()) + "]";

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

        String keyString = executing.getTrackers().keySet().toString();
        if (!lastKeyString.equals(keyString)) {
          backoffLeft = backoffCount = 1;
          log(message);
          delta.start();
          lastKeyString = keyString;
        } else {
          if (backoffLeft-- <= 0) {
            log(message);
            delta.start();
            backoffLeft = (backoffCount *= 2);
          }
        }

        try {
          Thread.sleep(POLLER_SLEEP_MS);
        } catch (InterruptedException e) {
          checkState(stop, "IE recieved when not stopped");
        }
      }
    }

    private String execString(ConcurrentMap<String, TimeAveraged> executing) {
      long now = System.nanoTime();
      List<String> output = Lists.newArrayList();
      for (Entry<String, TimeAveraged> entry : executing.entrySet()) {
        TimeAveraged avg = entry.getValue();
        output.add(entry.getKey() + " (exec=" + TimeUnit.NANOSECONDS.toMillis(now - avg.getStartNanos()) + "ms, conc=" + asPercent(avg) + "%)");
      }
      return Joiner.on(", ").join(output);
    }

    public void terminate() {
      stop = true;
      this.interrupt();
    }

  }

  private static class ConcurrencyTracker {

    private TimeAveraged overall, delta;

    private final ConcurrentMap<String, TimeAveraged> trackers = new ConcurrentHashMap<String, TimeAveraged>();

    public void startTask(String name) {
      checkState(!trackers.containsKey(name), "duplicate task name: " + name);
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
        checkState(removed != null, String.format("task isn't being tracked: %s", name));
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

  private static class ProjectsBuildMetrics {
    private Map<MavenProject, BuildMetrics> projectsBuildMetrics = new HashMap<MavenProject, BuildMetrics>();

    protected synchronized void newProject(final MavenProject project) {
      projectsBuildMetrics.put(project, new BuildMetrics());
    }

    public synchronized BuildMetrics getBuildMetrics(final MavenProject project) {
      return projectsBuildMetrics.get(project);
    }

    protected synchronized void start(final MavenProject project, final Timer timer) {
      projectsBuildMetrics.get(project).start(timer);
    }

    protected synchronized long stop(final MavenProject project, final Timer timer) {
      projectsBuildMetrics.get(project).stop(timer);
      return projectsBuildMetrics.get(project).getMetricElapsedTime(timer, TimeUnit.MILLISECONDS);
    }

    public synchronized List<MavenProject> getProjectsSortedByWalltime() {
      return Ordering.natural().onResultOf(Functions.forMap(projectsBuildMetrics)).sortedCopy(projectsBuildMetrics.keySet());
    }
  }

  private static class Report {
    private final static int NUMBER_OF_LONGEST_DEPENDENCY_CHAINS_TO_DISPLAY = 10;
    private ProjectsBuildMetrics projectsBuildMetrics;

    protected Report(final ProjectsBuildMetrics projectsBuildMetrics) {
      this.projectsBuildMetrics = projectsBuildMetrics;
    }

    protected void displayMetrics(Stopwatch lifecycleWalltimeMs, MavenSession session, ConcurrencyDependencyGraph analyzer) {
      displayHighLevelMetrics(lifecycleWalltimeMs, session.getProjects().size());
      displayProjectBuildTimesSortedDesc();
      displayBuildDependencyChainsSortedDescByWallTime(session, analyzer);
    }

    private void displayHighLevelMetrics(Stopwatch lifecycleWalltimeMs, long projects) {
      System.out.println("LifecycleThreadBuilder runtime performance summary");
      System.out.println(String.format("wall time (ms) = %9d", lifecycleWalltimeMs.elapsedMillis()));
      System.out.println(String.format("projects       = %9d", projects));
    }

    private void displayProjectBuildTimesSortedDesc() {
      System.out.println("Project build wall times (ms)");

      for (MavenProject project : projectsBuildMetrics.getProjectsSortedByWalltime()) {

        System.out.println(projectsBuildMetrics.getBuildMetrics(project) + " " + String.format("project = %s:%s:%s", project.getGroupId(), project.getArtifactId(), project.getVersion()));
      }
    }

    private void displayBuildDependencyChainsSortedDescByWallTime(MavenSession session, ConcurrencyDependencyGraph analyzer) {
      List<DependencyChain> chains = new ArrayList<DependencyChain>();

      for (MavenProject root : analyzer.getRootSchedulableBuilds()) {
        if (projectsBuildMetrics.getBuildMetrics(root) != null) {
          displayChains(chains, String.format("DependencyChain=%s:%s:%s(w=%dms,q=%dms)", root.getGroupId(), root.getArtifactId(), root.getVersion(), projectsBuildMetrics.getBuildMetrics(root)
              .getMetricMillis(Timer.WALLTIME_MS), projectsBuildMetrics.getBuildMetrics(root).getMetricMillis(Timer.QUEUETIME_MS)),
              projectsBuildMetrics.getBuildMetrics(root).getMetricMillis(Timer.WALLTIME_MS), projectsBuildMetrics.getBuildMetrics(root).getMetricMillis(Timer.QUEUETIME_MS), root,
              session.getProjectDependencyGraph());
        }
      }

      System.out.println("Sorted chains by longest wall time (ms)");
      Collections.sort(chains);

      int cnt = NUMBER_OF_LONGEST_DEPENDENCY_CHAINS_TO_DISPLAY;
      for (DependencyChain chain : chains) {
        if (cnt == 0)
          break;

        System.out.println(chain);
        cnt--;
      }
    }

    private void displayChains(List<DependencyChain> chains, String chain, long wallTimeMs, long queueTimeMs, MavenProject project, ProjectDependencyGraph graph) {
      if (graph.getDownstreamProjects(project, false).size() == 0) {
        chains.add(new DependencyChain(chain, wallTimeMs, queueTimeMs));
        return;
      }

      for (MavenProject dependentProject : graph.getDownstreamProjects(project, false)) {
        if (projectsBuildMetrics.getBuildMetrics(dependentProject) != null) {
          displayChains(
              chains,
              chain
                  + String.format("<-%s:%s:%s(w=%dms,q=%dms)", dependentProject.getGroupId(), dependentProject.getArtifactId(), dependentProject.getVersion(),
                      projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(Timer.WALLTIME_MS),
                      projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(Timer.QUEUETIME_MS)),
              wallTimeMs + projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(Timer.WALLTIME_MS), queueTimeMs
                  + projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(Timer.QUEUETIME_MS), dependentProject, graph);
        }
      }
    }

    private static class DependencyChain implements Comparable<DependencyChain> {
      private String chain;
      private long wallTimeMs;
      private long queueTimeMs;

      public DependencyChain(String chain, long wallTimeMs, long queueTimeMs) {
        this.chain = chain;
        this.wallTimeMs = wallTimeMs;
        this.queueTimeMs = queueTimeMs;
      }

      public int compareTo(DependencyChain o) {
        return (int) (o.wallTimeMs - wallTimeMs);
      }

      @Override
      public String toString() {
        return String.format("wall time (ms) = %9d, queue time (ms) = %9d, %s", wallTimeMs, queueTimeMs, chain);
      }
    }
  }
}