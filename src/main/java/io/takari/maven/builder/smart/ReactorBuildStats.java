package io.takari.maven.builder.smart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import com.google.common.collect.ImmutableMap;

class ReactorBuildStats {

  private long startTime;

  private long stopTime;

  /**
   * Time, in nanoseconds, a worker thread was executing the project build lifecycle. In addition to
   * Maven plugin goals execution includes any "overhead" time Maven spends resolving project
   * dependencies, calculating build time and perform any post-execution cleanup and maintenance.
   */
  private final Map<String, AtomicLong> serviceTimes;

  /**
   * Time, in nanoseconds, when the project was a bottleneck of entire build, i.e. when not all
   * available CPU cores were utilized, presumably because the project build time and dependency
   * structure prevented higher degree of parallelism.
   */
  private final Map<String, AtomicLong> bottleneckTimes;

  private ReactorBuildStats(Map<String, AtomicLong> serviceTimes,
      Map<String, AtomicLong> bottleneckTimes) {
    this.serviceTimes = ImmutableMap.copyOf(serviceTimes);
    this.bottleneckTimes = ImmutableMap.copyOf(bottleneckTimes);
  }

  private static String projectGA(MavenProject project) {
    return project.getGroupId() + ":" + project.getArtifactId();
  }

  public static ReactorBuildStats create(Collection<MavenProject> projects) {
    ImmutableMap.Builder<String, AtomicLong> serviceTimes = ImmutableMap.builder();
    ImmutableMap.Builder<String, AtomicLong> bottleneckTimes = ImmutableMap.builder();
    projects.stream().map(project -> projectGA(project)).forEach(key -> {
      serviceTimes.put(key, new AtomicLong());
      bottleneckTimes.put(key, new AtomicLong());
    });
    return new ReactorBuildStats(serviceTimes.build(), bottleneckTimes.build());
  }

  public void recordStart() {
    this.startTime = System.nanoTime();
  }

  public void recordStop() {
    this.stopTime = System.nanoTime();
  }

  public void recordServiceTime(MavenProject project, long durationNanos) {
    serviceTimes.get(projectGA(project)).addAndGet(durationNanos);
  }

  public void recordBottlenecks(Set<MavenProject> projects, int degreeOfConcurrency,
      long durationNanos) {
    // each project's "share" of wasted time
    long waste = durationNanos * (degreeOfConcurrency - projects.size()) / projects.size();
    projects.forEach(p -> bottleneckTimes.get(projectGA(p)).addAndGet(waste));
  }

  //
  // Reporting
  //

  public long totalServiceTime(TimeUnit unit) {
    long nanos = serviceTimes.values().stream().mapToLong(l -> l.longValue()).sum();
    return unit.convert(nanos, TimeUnit.NANOSECONDS);
  }

  public long walltimeTime(TimeUnit unit) {
    return unit.convert(stopTime - startTime, TimeUnit.NANOSECONDS);
  }

  public String renderCriticalPath(ProjectDependencyGraph graph) {
    return renderCriticalPath(DependencyGraph.fromMaven(graph), p -> projectGA(p));
  }

  public <K> String renderCriticalPath(DependencyGraph<K> graph, Function<K, String> toKey) {
    StringBuilder result = new StringBuilder();
    result.append("Build critical path service times (and bottleneck times)\n");
    Comparator<K> comparator = ProjectComparator.create0(graph, serviceTimes, toKey);
    List<K> rootProjects = new ArrayList<>();
    for (K project : graph.getSortedProjects()) {
      if (graph.getUpstreamProjects(project).isEmpty()) {
        rootProjects.add(project);
      }
    }
    List<K> criticalPath = calculateCriticalPath(rootProjects, comparator, graph);
    long totalServiceTime = 0;
    for (K project : criticalPath) {
      String key = toKey.apply(project);
      final long serviceTime = serviceTimes.get(key).get();
      final long bottleneckTime = bottleneckTimes.get(key).get();
      totalServiceTime += serviceTime;
      result.append(String.format("   %-60s %s", key, formatDuration(serviceTime)));
      if (bottleneckTime > 0) {
        result.append(String.format(" (%s)", formatDuration(bottleneckTime)));
      }
      result.append('\n');
    }

    result.append(String.format("Build critical path total service time %s",
        formatDuration(totalServiceTime)));

    return result.toString();
  }

  private String formatDuration(long nanos) {
    long secs = TimeUnit.NANOSECONDS.toSeconds(nanos);
    return String.format("%5d s", secs);
  }

  private <K> List<K> calculateCriticalPath(Collection<K> rootProjects, Comparator<K> comparator,
      DependencyGraph<K> graph) {
    List<K> criticalPath = new ArrayList<>();
    K project = getCriticalProject(rootProjects, comparator);
    do {
      criticalPath.add(project);
    } while ((project =
        getCriticalProject(graph.getDownstreamProjects(project), comparator)) != null);
    return criticalPath;
  }

  private <K> K getCriticalProject(Collection<K> projects, Comparator<K> comparator) {
    if (projects == null || projects.isEmpty()) {
      return null;
    }
    List<K> sorted = new ArrayList<>(projects);
    Collections.sort(sorted, comparator);
    return sorted.get(0);
  }

}
