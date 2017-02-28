package io.takari.maven.builder.smart;

import io.takari.maven.builder.smart.BuildMetrics.Timer;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import com.google.common.collect.ImmutableMap;

/**
 * Project comparator (factory) that uses project build time to establish build order.
 * <p>
 * Internally, each project is assigned a weight, which is calculated as sum of project build time
 * and maximum weight of any of the project's downstream dependencies. The project weights are
 * calculated by recursively traversing project dependency graph starting from build root projects,
 * i.e. projects that do not have any upstream dependencies.
 * <p>
 * Project build times are estimated based on values persisted during a previous build. Average
 * build time is used for projects that do not have persisted build time.
 * <p>
 * If there are no persisted build times, all projects build times are assumed the same (arbitrary)
 * value of 1. This means that the project with the longest downstream dependency trail will be
 * built first.
 * <p>
 * Currently, historical build times are stored in
 * <code>${session.request/baseDirectory}/.mvn/timing.properties</code> file. The timings file is
 * written only if <code>${session.request/baseDirectory}/.mvn</code> directory is already present.
 */
class ProjectComparator {

  public static Comparator<MavenProject> create(MavenSession session) {
    final ProjectDependencyGraph dependencyGraph = session.getProjectDependencyGraph();
    return create(dependencyGraph, ImmutableMap.of());
  }

  // public for unit testing
  public static Comparator<MavenProject> create(final ProjectDependencyGraph dependencyGraph,
      final Map<String, Long> historicalServiceTimes) {
    final long defaultServiceTime = average(historicalServiceTimes.values());

    final Map<MavenProject, Long> serviceTimes = new HashMap<>();

    final Set<MavenProject> rootProjects = new HashSet<MavenProject>();
    for (MavenProject project : dependencyGraph.getSortedProjects()) {
      Long serviceTime = getServiceTime(historicalServiceTimes, project, defaultServiceTime);
      serviceTimes.put(project, serviceTime);
      if (dependencyGraph.getUpstreamProjects(project, false).isEmpty()) {
        rootProjects.add(project);
      }
    }

    final Map<MavenProject, Long> projectWeights =
        calculateWeights(dependencyGraph, serviceTimes, rootProjects);

    return new Comparator<MavenProject>() {
      @Override
      public int compare(MavenProject o1, MavenProject o2) {
        long delta = projectWeights.get(o2) - projectWeights.get(o1);
        if (delta > 0) {
          return 1;
        } else if (delta < 0) {
          return -1;
        }
        // id comparison guarantees stable ordering during unit tests
        return id(o2).compareTo(id(o1));
      }
    };
  }

  private static long average(Collection<Long> values) {
    long count = 0, sum = 0;
    for (Long value : values) {
      if (value != null) {
        sum += value.longValue();
        count++;
      }
    }
    long average = 0;
    if (count > 0) {
      average = sum / count;
    }
    if (average == 0) {
      average = 1; // arbitrary number
    }
    return average;
  }

  private static Long getServiceTime(Map<String, Long> serviceTimes, MavenProject project,
      long defaultServiceTime) {
    Long serviceTime = serviceTimes.get(id(project));
    return serviceTime != null ? serviceTime.longValue() : defaultServiceTime;
  }

  private static Map<MavenProject, Long> calculateWeights(ProjectDependencyGraph dependencyGraph,
      Map<MavenProject, Long> serviceTimes, Collection<MavenProject> rootProjects) {
    Map<MavenProject, Long> weights = new HashMap<MavenProject, Long>();
    for (MavenProject rootProject : rootProjects) {
      calculateWeights(dependencyGraph, serviceTimes, rootProject, weights);
    }
    return weights;
  }

  /**
   * Returns the maximum sum of build time along a path from the project to an exit project. An
   * "exit project" is a project without downstream dependencies.
   */
  private static long calculateWeights(ProjectDependencyGraph dependencyGraph,
      Map<MavenProject, Long> serviceTimes, MavenProject project, Map<MavenProject, Long> weights) {
    long weight = serviceTimes.get(project);
    for (MavenProject successor : dependencyGraph.getDownstreamProjects(project, false)) {
      long successorWeight;
      if (weights.containsKey(successor)) {
        successorWeight = weights.get(successor);
      } else {
        successorWeight = calculateWeights(dependencyGraph, serviceTimes, successor, weights);
      }
      weight = Math.max(weight, serviceTimes.get(project) + successorWeight);
    }
    weights.put(project, weight);
    return weight;
  }

  public static String id(MavenProject project) {
    StringBuilder sb = new StringBuilder();
    sb.append(project.getGroupId());
    sb.append(':');
    sb.append(project.getArtifactId());
    sb.append(':');
    sb.append(project.getVersion());
    return sb.toString();
  }

  public static Comparator<MavenProject> create(ProjectDependencyGraph projectDependencyGraph,
      ProjectsBuildMetrics projectsBuildMetrics) {
    Map<String, Long> serviceTimes = new HashMap<>();
    for (Map.Entry<MavenProject, Long> entry : projectsBuildMetrics.asMap(Timer.SERVICETIME_MS).entrySet()) {
      serviceTimes.put(id(entry.getKey()), entry.getValue());
    }
    return create(projectDependencyGraph, serviceTimes);
  }

}
