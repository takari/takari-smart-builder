package io.takari.maven.builder.smart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import static java.util.Collections.unmodifiableMap;

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
    this.serviceTimes = unmodifiableMap(serviceTimes);
    this.bottleneckTimes = unmodifiableMap(bottleneckTimes);
  }

  private static String projectGA(MavenProject project) {
    return project.getGroupId() + ":" + project.getArtifactId();
  }

  public static ReactorBuildStats create(Collection<MavenProject> projects) {
    Map<String, AtomicLong> serviceTimes = new ConcurrentHashMap<>();
    Map<String, AtomicLong> bottleneckTimes = new ConcurrentHashMap<>();
    projects.stream().map(project -> projectGA(project)).forEach(key -> {
      serviceTimes.put(key, new AtomicLong());
      bottleneckTimes.put(key, new AtomicLong());
    });
    return new ReactorBuildStats(serviceTimes, bottleneckTimes);
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
    // only projects that result in single-threaded builds
    if (projects.size() == 1) {
      projects.forEach(p -> bottleneckTimes.get(projectGA(p)).addAndGet(durationNanos));
    }
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

    // render critical path

    long criticalPathServiceTime = 0;
    result.append("Build critical path service times (and bottleneck** times):");
    for (K project : calculateCriticalPath(graph, toKey)) {
      result.append('\n');
      String key = toKey.apply(project);
      criticalPathServiceTime += serviceTimes.get(key).get();
      appendProjectTimes(result, key);
    }
    result.append(String.format("\nBuild critical path total service time %s",
        formatDuration(criticalPathServiceTime)));

    // render bottleneck projects

    List<String> bottleneckProjects = getBottleneckProjects();
    if (!bottleneckProjects.isEmpty()) {
      long bottleneckTotalTime = 0;
      result.append("\nBuild bottleneck projects service times (and bottleneck** times):");
      for (String bottleneck : bottleneckProjects) {
        result.append('\n');
        bottleneckTotalTime += bottleneckTimes.get(bottleneck).get();
        appendProjectTimes(result, bottleneck);
      }
      result.append(
          String.format("\nBuild bottlenecks total time %s", formatDuration(bottleneckTotalTime)));
    }

    result.append("\n** Bottlenecks are projects that limit build concurrency");
    result.append("\n   removing bottlenecks improves overall build time");
    return result.toString();
  }

  private void appendProjectTimes(StringBuilder result, String project) {
    final long serviceTime = serviceTimes.get(project).get();
    final long bottleneckTime = bottleneckTimes.get(project).get();
    result.append(String.format("   %-60s %s", project, formatDuration(serviceTime)));
    if (bottleneckTime > 0) {
      result.append(String.format(" (%s)", formatDuration(bottleneckTime)));
    }
  }

  private List<String> getBottleneckProjects() {
    Comparator<String> comparator = (a, b) -> {
      long ta = bottleneckTimes.get(a).longValue();
      long tb = bottleneckTimes.get(b).longValue();
      if (tb > ta) {
        return 1;
      } else if (tb < ta) {
        return -1;
      }
      return 0;
    };
    return bottleneckTimes.keySet().stream() //
        .sorted(comparator) //
        .filter(project -> bottleneckTimes.get(project).get() > 0) //
        .collect(Collectors.toList());
  }

  private String formatDuration(long nanos) {
    long secs = TimeUnit.NANOSECONDS.toSeconds(nanos);
    return String.format("%5d s", secs);
  }

  private <K> List<K> calculateCriticalPath(DependencyGraph<K> graph, Function<K, String> toKey) {
    Comparator<K> comparator = ProjectComparator.create0(graph, serviceTimes, toKey);
    List<K> rootProjects = new ArrayList<>();
    for (K project : graph.getSortedProjects()) {
      if (graph.getUpstreamProjects(project).isEmpty()) {
        rootProjects.add(project);
      }
    }
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
