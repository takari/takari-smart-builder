package io.takari.maven.builder.smart;

import io.takari.maven.builder.smart.BuildMetrics.Timer;

import java.util.*;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import com.google.common.base.Stopwatch;

class Report {
  private final static int NUMBER_OF_LONGEST_DEPENDENCY_CHAINS_TO_DISPLAY = 10;
  private final ProjectsBuildMetrics projectsBuildMetrics;

  public Report(ProjectsBuildMetrics projectsBuildMetrics) {
    this.projectsBuildMetrics = projectsBuildMetrics;
  }

  protected void displayMetrics(Stopwatch lifecycleWalltimeMs, MavenSession session,
      Collection<MavenProject> rootProjects) {
    displayHighLevelMetrics(lifecycleWalltimeMs, session.getProjects().size());
    displayProjectBuildTimesSortedDesc();
    displayBuildDependencyChainsSortedDescByWallTime(session, rootProjects);
  }

  private void displayHighLevelMetrics(Stopwatch lifecycleWalltimeMs, long projects) {
    System.out.println("LifecycleThreadBuilder runtime performance summary");
    System.out.println(String.format("wall time (ms) = %9d", lifecycleWalltimeMs.elapsedMillis()));
    System.out.println(String.format("projects       = %9d", projects));
  }

  private void displayProjectBuildTimesSortedDesc() {
    System.out.println("Project build wall times (ms)");

    for (MavenProject project : projectsBuildMetrics.getProjectsSortedByWalltime()) {

      System.out.println(projectsBuildMetrics.getBuildMetrics(project)
          + " "
          + String.format("project = %s:%s:%s", project.getGroupId(), project.getArtifactId(),
              project.getVersion()));
    }
  }

  private void displayBuildDependencyChainsSortedDescByWallTime(MavenSession session,
      Collection<MavenProject> rootProjects) {
    List<Report.DependencyChain> chains = new ArrayList<Report.DependencyChain>();

    for (MavenProject root : rootProjects) {
      if (projectsBuildMetrics.getBuildMetrics(root) != null) {
        displayChains(chains, String.format("DependencyChain=%s:%s:%s(w=%dms,q=%dms)", root
            .getGroupId(), root.getArtifactId(), root.getVersion(), projectsBuildMetrics
            .getBuildMetrics(root).getMetricMillis(Timer.WALLTIME_MS), projectsBuildMetrics
            .getBuildMetrics(root).getMetricMillis(Timer.QUEUETIME_MS)), projectsBuildMetrics
            .getBuildMetrics(root).getMetricMillis(Timer.WALLTIME_MS), projectsBuildMetrics
            .getBuildMetrics(root).getMetricMillis(Timer.QUEUETIME_MS), root,
            session.getProjectDependencyGraph());
      }
    }

    System.out.println("Sorted chains by longest wall time (ms)");
    Collections.sort(chains);

    int cnt = NUMBER_OF_LONGEST_DEPENDENCY_CHAINS_TO_DISPLAY;
    for (Report.DependencyChain chain : chains) {
      if (cnt == 0)
        break;

      System.out.println(chain);
      cnt--;
    }
  }

  private void displayChains(List<Report.DependencyChain> chains, String chain, long wallTimeMs,
      long queueTimeMs, MavenProject project, ProjectDependencyGraph graph) {
    if (graph.getDownstreamProjects(project, false).size() == 0) {
      chains.add(new DependencyChain(chain, wallTimeMs, queueTimeMs));
      return;
    }

    for (MavenProject dependentProject : graph.getDownstreamProjects(project, false)) {
      if (projectsBuildMetrics.getBuildMetrics(dependentProject) != null) {
        displayChains(
            chains,
            chain
                + String.format(
                    "<-%s:%s:%s(w=%dms,q=%dms)",
                    dependentProject.getGroupId(),
                    dependentProject.getArtifactId(),
                    dependentProject.getVersion(),
                    projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(
                        Timer.WALLTIME_MS), projectsBuildMetrics.getBuildMetrics(dependentProject)
                        .getMetricMillis(Timer.QUEUETIME_MS)),
            wallTimeMs
                + projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(
                    Timer.WALLTIME_MS),
            queueTimeMs
                + projectsBuildMetrics.getBuildMetrics(dependentProject).getMetricMillis(
                    Timer.QUEUETIME_MS), dependentProject, graph);
      }
    }
  }

  private static class DependencyChain implements Comparable<Report.DependencyChain> {
    private String chain;
    private long wallTimeMs;
    private long queueTimeMs;

    public DependencyChain(String chain, long wallTimeMs, long queueTimeMs) {
      this.chain = chain;
      this.wallTimeMs = wallTimeMs;
      this.queueTimeMs = queueTimeMs;
    }

    @Override
    public int compareTo(Report.DependencyChain o) {
      return (int) (o.wallTimeMs - wallTimeMs);
    }

    @Override
    public String toString() {
      return String.format("wall time (ms) = %9d, queue time (ms) = %9d, %s", wallTimeMs,
          queueTimeMs, chain);
    }
  }
}
