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

import io.takari.maven.builder.smart.ProjectExecutorService.ProjectRunnable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.*;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;

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
  private final List<TaskSegment> taskSegments;

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
      ReactorContext reactorContext, List<TaskSegment> taskSegments) {
    this.lifecycleModuleBuilder = lifecycleModuleBuilder;
    this.rootSession = session;
    this.reactorContext = reactorContext;
    this.taskSegments = taskSegments;

    final List<MavenProject> projects = session.getProjects();
    final ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();

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

    this.reactorBuildQueue = new ReactorBuildQueue(projectDependencyGraph);
    this.listeners = Collections.unmodifiableList(listeners);
    this.executor = new ProjectExecutorService(degreeOfConcurrency, projectComparator);
  }

  public void build() throws ExecutionException, InterruptedException {

    final Stopwatch buildStopwatch = new Stopwatch().start();

    log("Task segments : " + Joiner.on(",").join(taskSegments));
    log("Build maximum degree of concurrency is " + degreeOfConcurrency);
    log("Root level projects are " + Joiner.on(",").join(reactorBuildQueue.getRootProjects()));

    // this is the main build loop
    submitAll(reactorBuildQueue.getRootProjects());
    while (!reactorBuildQueue.isEmpty()) {
      MavenProject completedProject = executor.take(); // blocks until there is a finished project
      Set<MavenProject> readyProjects = reactorBuildQueue.onProjectFinish(completedProject);
      submitAll(readyProjects);
    }
    executor.shutdown(); // waits for all running tasks to complete

    if (progressReporter != null) {
      progressReporter.terminate();
    }

    try {
      ProjectComparator.writeServiceTimes(rootSession, projectsBuildMetrics);
    } catch (IOException e) {
      throw new ExecutionException(e);
    }

    if (isProfiling()) {
      Report report = new Report(projectsBuildMetrics);
      Stopwatch reportStopwatch = new Stopwatch().start();
      report.displayMetrics(buildStopwatch, rootSession, reactorBuildQueue.getRootProjects());
      log("Report generation wall time (ms) = " + reportStopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

  }

  private void submitAll(Set<MavenProject> readyProjects) {
    printReadyQueue(readyProjects);
    List<ProjectBuildTask> tasks = new ArrayList<>();
    for (MavenProject project : readyProjects) {
      for (Listener listener : listeners) {
        listener.onReady(project);
      }
      tasks.add(new ProjectBuildTask(project));
    }
    executor.submitAll(tasks);
  }

  /* package */void buildProject(MavenProject project) {
    log("Starting " + project.getName());

    Stopwatch projectStopwatch = new Stopwatch().start();

    try {
      MavenSession copiedSession = rootSession.clone();
      for (TaskSegment taskSegment : taskSegments) {
        lifecycleModuleBuilder.buildProject(copiedSession, rootSession, reactorContext, project,
            taskSegment);
      }
    } catch (RuntimeException ex) {
      // preserve the xml stack trace, and the java cause chain
      rootSession.getResult().addException(
          new RuntimeException(project.getName() + ": " + ex.getMessage(), ex));
    } finally {
      // runtime =
      // Ints.checkedCast(TimeUnit.NANOSECONDS.toMillis(executing.stopTask(projectBuild.getId())));

      log("Completed servicing " + project.getName() + " : "
          + projectStopwatch.elapsed(TimeUnit.MILLISECONDS) + " (ms).");
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
