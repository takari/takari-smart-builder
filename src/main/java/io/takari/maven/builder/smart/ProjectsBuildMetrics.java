package io.takari.maven.builder.smart;

import io.takari.maven.builder.smart.BuildMetrics.Timer;

import java.util.*;

import org.apache.maven.project.MavenProject;

import com.google.common.base.Functions;
import com.google.common.collect.Ordering;

class ProjectsBuildMetrics implements SmartBuilderImpl.Listener {
  private final Map<MavenProject, BuildMetrics> projectsBuildMetrics;

  ProjectsBuildMetrics(Collection<MavenProject> projects) {
    Map<MavenProject, BuildMetrics> projectsBuildMetrics = new HashMap<>();
    for (MavenProject project : projects) {
      projectsBuildMetrics.put(project, new BuildMetrics());
    }
    this.projectsBuildMetrics = Collections.unmodifiableMap(projectsBuildMetrics);
  }

  public BuildMetrics getBuildMetrics(final MavenProject project) {
    return projectsBuildMetrics.get(project);
  }

  private void start(final MavenProject project, final Timer timer) {
    projectsBuildMetrics.get(project).start(timer);
  }

  private void stop(final MavenProject project, final Timer timer) {
    projectsBuildMetrics.get(project).stop(timer);
  }

  public List<MavenProject> getProjects() {
    return new ArrayList<>(projectsBuildMetrics.keySet());
  }

  public List<MavenProject> getProjectsSortedByWalltime() {
    return Ordering.natural().onResultOf(Functions.forMap(projectsBuildMetrics))
        .sortedCopy(projectsBuildMetrics.keySet());
  }

  @Override
  public void onReady(MavenProject project) {
    start(project, Timer.QUEUETIME_MS);
  }

  @Override
  public void onStart(MavenProject project) {
    stop(project, Timer.QUEUETIME_MS);
    start(project, Timer.SERVICETIME_MS);
    // TODO add CPU usage tracking
  }

  @Override
  public void onFinish(MavenProject project) {
    stop(project, Timer.SERVICETIME_MS);
  }
}
