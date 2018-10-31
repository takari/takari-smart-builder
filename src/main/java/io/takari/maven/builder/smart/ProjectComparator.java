package io.takari.maven.builder.smart;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

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
    return create0(DependencyGraph.fromMaven(dependencyGraph), Collections.emptyMap(), p -> id(p));
  }

  static <K> Comparator<K> create0(final DependencyGraph<K> dependencyGraph,
      final Map<String, AtomicLong> historicalServiceTimes, Function<K, String> toKey) {
    final long defaultServiceTime = average(historicalServiceTimes.values());

    final Map<K, Long> serviceTimes = new HashMap<>();

    final Set<K> rootProjects = new HashSet<>();
    for (K project : dependencyGraph.getSortedProjects()) {
      Long serviceTime = getServiceTime(historicalServiceTimes, project, defaultServiceTime, toKey);
      serviceTimes.put(project, serviceTime);
      if (dependencyGraph.getUpstreamProjects(project).isEmpty()) {
        rootProjects.add(project);
      }
    }

    final Map<K, Long> projectWeights =
        calculateWeights(dependencyGraph, serviceTimes, rootProjects);

    return new Comparator<K>() {
      @Override
      public int compare(K o1, K o2) {
        long delta = projectWeights.get(o2) - projectWeights.get(o1);
        if (delta > 0) {
          return 1;
        } else if (delta < 0) {
          return -1;
        }
        // id comparison guarantees stable ordering during unit tests
        return toKey.apply(o2).compareTo(toKey.apply(o1));
      }
    };
  }

  private static long average(Collection<AtomicLong> values) {
    long count = 0, sum = 0;
    for (AtomicLong value : values) {
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

  private static <K> Long getServiceTime(Map<String, AtomicLong> serviceTimes, K project,
      long defaultServiceTime, Function<K, String> toKey) {
    AtomicLong serviceTime = serviceTimes.get(toKey.apply(project));
    return serviceTime != null ? serviceTime.longValue() : defaultServiceTime;
  }

  private static <K> Map<K, Long> calculateWeights(DependencyGraph<K> dependencyGraph,
      Map<K, Long> serviceTimes, Collection<K> rootProjects) {
    Map<K, Long> weights = new HashMap<>();
    for (K rootProject : rootProjects) {
      calculateWeights(dependencyGraph, serviceTimes, rootProject, weights);
    }
    return weights;
  }

  /**
   * Returns the maximum sum of build time along a path from the project to an exit project. An
   * "exit project" is a project without downstream dependencies.
   */
  private static <K> long calculateWeights(DependencyGraph<K> dependencyGraph,
      Map<K, Long> serviceTimes, K project, Map<K, Long> weights) {
    long weight = serviceTimes.get(project);
    for (K successor : dependencyGraph.getDownstreamProjects(project)) {
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

  static String id(MavenProject project) {
    StringBuilder sb = new StringBuilder();
    sb.append(project.getGroupId());
    sb.append(':');
    sb.append(project.getArtifactId());
    sb.append(':');
    sb.append(project.getVersion());
    return sb.toString();
  }

}
