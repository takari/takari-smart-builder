package io.takari.maven.builder.smart;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.ReactorContext;
import org.apache.maven.lifecycle.internal.TaskSegment;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;

import io.takari.maven.builder.smart.BuildMetrics.Timer;
import io.takari.maven.builder.smart.ProjectExecutorService.ProjectRunnable;

/**
 * Maven {@link Builder} implementation that schedules execution of the reactor modules on the build
 * critical path first. Build critical path is estimated based on module build times collected
 * during a previous build, or based on module's downstream dependency trail length, if no prior
 * build time information is available.
 * 
 * @author Brian Toal
 */
class SmartBuilderImpl {

  private final Logger logger = LoggerFactory.getLogger(SmartBuilder.class);

  static interface Listener {
    /**
     * Called when the project becomes ready, i.e. after all project's reactor dependencies were
     * built.
     */
    public void onReady(MavenProject project);

    /**
     * Called when the project build starts
     */
    public void onStart(MavenProject project);

    /**
     * Called when project build finishes.
     */
    public void onFinish(MavenProject project);
  }


  // global components
  private final LifecycleModuleBuilder lifecycleModuleBuilder;

  // session-level components
  private final MavenSession rootSession;
  private final ReactorContext reactorContext;
  private final TaskSegment taskSegment;

  //
  private final List<Listener> listeners;
  private final ReactorBuildQueue reactorBuildQueue;
  private final ProjectExecutorService executor;
  private final BuildProgressReportThread progressReporter;
  private final int degreeOfConcurrency;
  private final ProjectsBuildMetrics projectsBuildMetrics;


  class ProjectBuildTask implements ProjectRunnable {
    private final MavenProject project;

    ProjectBuildTask(MavenProject project) {
      this.project = project;
    }

    @Override
    public void run() {
      for (Listener listener : listeners) {
        listener.onStart(project);
      }
      buildProject(project);
      for (Listener listener : listeners) {
        listener.onFinish(project);
      }
    }

    @Override
    public MavenProject getProject() {
      return project;
    }
  }

  SmartBuilderImpl(LifecycleModuleBuilder lifecycleModuleBuilder, MavenSession session,
      ReactorContext reactorContext, TaskSegment taskSegment, Set<MavenProject> projects) {
    this.lifecycleModuleBuilder = lifecycleModuleBuilder;
    this.rootSession = session;
    this.reactorContext = reactorContext;
    this.taskSegment = taskSegment;

    this.degreeOfConcurrency = Integer.valueOf(session.getRequest().getDegreeOfConcurrency());

    List<Listener> listeners = new ArrayList<>();

    this.projectsBuildMetrics = new ProjectsBuildMetrics(projects);
    listeners.add(projectsBuildMetrics);

    BuildProgressReportThread progressReporter = null;
    if (isProfiling()) {
      progressReporter = new BuildProgressReportThread(projects.size(), degreeOfConcurrency);
      progressReporter.start();
      listeners.add(progressReporter);
    }
    this.progressReporter = progressReporter;

    final Comparator<MavenProject> projectComparator = ProjectComparator.create(session);

    this.reactorBuildQueue = new ReactorBuildQueue(projects, session.getProjectDependencyGraph());
    this.listeners = Collections.unmodifiableList(listeners);
    this.executor = new ProjectExecutorService(degreeOfConcurrency, projectComparator);
  }

  public void build() throws ExecutionException, InterruptedException {

    final long buildStopwatch = System.currentTimeMillis();

    Set<MavenProject> rootProjects = reactorBuildQueue.getRootProjects();

    log("Task segments : " + taskSegment);
    log("Build maximum degree of concurrency is " + degreeOfConcurrency);
    log("Root level projects are " + Joiner.on(",").join(rootProjects));

    // this is the main build loop
    submitAll(rootProjects);
    int submittedCount = rootProjects.size();
    while (submittedCount > 0) {
      submittedCount--;
      try {
        MavenProject completedProject = executor.take();
        logCompleted(completedProject);
        Set<MavenProject> readyProjects = reactorBuildQueue.onProjectFinish(completedProject);
        submitAll(readyProjects);
        submittedCount += readyProjects.size();

        logBuildQueueStatus();
      } catch (ExecutionException e) {
        // we get here when unhandled exception or error occurred on the worker thread
        // this can be low-level system problem, like OOME, or runtime exception in maven code
        // there is no meaningful recovery, so we shutdown and rethrow the exception
        shutdown();
        throw e;
      }
    }
    shutdown();

    final long buildStopwatchEnd = System.currentTimeMillis();


    if (isProfiling()) {
      report(buildStopwatchEnd - buildStopwatch);
    }
  }

  private void logBuildQueueStatus() {
    int blockedCount = reactorBuildQueue.getBlockedCount();
    int finishedCount = reactorBuildQueue.getFinishedCount();
    int readyCount = reactorBuildQueue.getReadyCount();
    String runningProjects = "";
    if (readyCount < degreeOfConcurrency && blockedCount > 0) {
      StringBuffer sb = new StringBuffer();
      sb.append('[');
      for (MavenProject project : reactorBuildQueue.getReadyProjects()) {
        if (sb.length() > 1) {
          sb.append(' ');
        }
        sb.append(projectGA(project));
      }
      sb.append(']');
      runningProjects = sb.toString();
    }
    logger.info("Builder state: blocked={} finished={} ready-or-running={} {}", blockedCount,
        finishedCount, readyCount, runningProjects);
  }

  private void logCompleted(MavenProject project) {
    BuildSummary buildSummary = rootSession.getResult().getBuildSummary(project);
    String message = "SKIPPED";
    if (buildSummary instanceof BuildSuccess) {
      message = "SUCCESS";
    } else if (buildSummary instanceof BuildFailure) {
      message = "FAILURE";
    } else if (buildSummary != null) {
      logger.warn("Unexpected project build summary class {}", buildSummary.getClass());
      message = "UNKNOWN";
    }
    logger.info("{} build of project {}", message, projectGA(project));
  }

  private String projectGA(MavenProject project) {
    return project.getGroupId() + ":" + project.getArtifactId();
  }

  private void shutdown() {
    executor.shutdown();
    if (progressReporter != null) {
      progressReporter.terminate();
    }
  }

  private void report(final long wallTime) {
    // total number of projects
    // wall-clock time
    // critical path build time
    // scheduling efficiency (critical-path vs wall-clock times ratio)

    final List<MavenProject> projects = rootSession.getProjects();
    final int projectCount = projects.size();
    final List<MavenProject> criticalPath = calculateCriticalPath();
    final long criticalPathTime = totalTime(criticalPath);

    final float schedullingEfficiency = ((float) criticalPathTime) / ((float) wallTime);

    logger.info(
        "Smart builder : projects={}, wallTime={} ms, criticalPathTime={} ms, schedullingEfficiency={} %",
        projectCount, wallTime, criticalPathTime,
        String.format("%3.2f", schedullingEfficiency * 100));

    StringBuilder sb = new StringBuilder();
    sb.append(String.format(
        "Smart builder : critical path projects %d time %d ms (project serviceTime ms queueTime ms):",
        criticalPath.size(), criticalPathTime));
    appendBuildMetrics(sb, criticalPath);
    logger.info(sb.toString());

    sb = new StringBuilder();
    sb.append(String.format(
        "Smart builder : all projects %d (project serviceTime ms queueTime ms):", projectCount));
    appendBuildMetrics(sb, projects);
    logger.info(sb.toString());
  }

  private void appendBuildMetrics(StringBuilder result, List<MavenProject> projects) {
    for (MavenProject project : projects) {
      if (result.length() > 0) {
        result.append("\n   ");
      }
      final BuildMetrics metrics = projectsBuildMetrics.getBuildMetrics(project);
      final long serviceTime = metrics.getMetricMillis(Timer.SERVICETIME_MS);
      final long queueTime = metrics.getMetricMillis(Timer.QUEUETIME_MS);
      result.append(project.getGroupId()).append(':').append(project.getArtifactId());
      result.append(' ').append(serviceTime).append(' ').append(queueTime);
    }
  }

  private long totalTime(List<MavenProject> projects) {
    long total = 0;
    for (MavenProject project : projects) {
      total += projectsBuildMetrics.getBuildMetrics(project).getMetricMillis(Timer.SERVICETIME_MS);
    }
    return total;
  }

  private List<MavenProject> calculateCriticalPath() {
    List<MavenProject> criticalPath = new ArrayList<>();
    Comparator<MavenProject> comparator =
        ProjectComparator.create(rootSession.getProjectDependencyGraph(), projectsBuildMetrics);
    MavenProject project = getCriticalProject(reactorBuildQueue.getRootProjects(), comparator);
    do {
      criticalPath.add(project);
    } while ((project =
        getCriticalProject(reactorBuildQueue.getDownstreamProjects(project), comparator)) != null);
    return criticalPath;
  }

  private MavenProject getCriticalProject(Collection<MavenProject> projects,
      Comparator<MavenProject> comparator) {
    if (projects == null || projects.isEmpty()) {
      return null;
    }
    List<MavenProject> sorted = new ArrayList<>(projects);
    Collections.sort(sorted, comparator);
    return sorted.get(0);
  }

  private void submitAll(Set<MavenProject> readyProjects) {
    printReadyQueue(readyProjects);
    List<ProjectBuildTask> tasks = new ArrayList<>();
    for (MavenProject project : readyProjects) {
      for (Listener listener : listeners) {
        listener.onReady(project);
      }
      tasks.add(new ProjectBuildTask(project));
      logger.debug("Ready {}", projectGA(project));
    }
    executor.submitAll(tasks);
  }

  /* package */void buildProject(MavenProject project) {
    logger.info("STARTED build of project {}", projectGA(project));

    final long projectStopwatch = System.currentTimeMillis();

    try {
      MavenSession copiedSession = rootSession.clone();
      lifecycleModuleBuilder.buildProject(copiedSession, rootSession, reactorContext, project,
          taskSegment);
    } catch (RuntimeException ex) {
      // preserve the xml stack trace, and the java cause chain
      rootSession.getResult()
          .addException(new RuntimeException(project.getName() + ": " + ex.getMessage(), ex));
    } finally {
      // runtime =
      // Ints.checkedCast(TimeUnit.NANOSECONDS.toMillis(executing.stopTask(projectBuild.getId())));

      log("Completed servicing " + project.getName() + " : "
          + (System.currentTimeMillis() - projectStopwatch) + " (ms).");
    }
  }

  private boolean isProfiling() {
    return System.getProperty("maven.profile") != null;
  }

  private void printReadyQueue(Collection<MavenProject> readyQueue) {
    if (isProfiling() && !readyQueue.isEmpty()) {
      log("================================");
      for (MavenProject project : readyQueue) {
        log(project.getName());
      }
      log("--------------------------------");
    }
  }

  public void log(final String s) {
    if (isProfiling()) {
      logger.info("Smart Builder : " + s);
    }
  }

  static void checkState(boolean b, String string) {
    if (!b) {
      throw new RuntimeException(string);
    }
  }

}
