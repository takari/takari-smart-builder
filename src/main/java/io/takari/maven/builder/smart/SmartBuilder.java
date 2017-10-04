package io.takari.maven.builder.smart;

import com.google.common.base.Joiner;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.internal.*;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Trivial Maven {@link Builder} implementation. All interesting stuff happens in
 * {@link SmartBuilderImpl} .
 */
@Singleton
@Named("smart")
public class SmartBuilder implements Builder {
  
  public static final String PROP_PROFILING = "smartbuilder.profiling";
  
  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final LifecycleModuleBuilder moduleBuilder;

  @Inject
  public SmartBuilder(LifecycleModuleBuilder moduleBuilder) {
    this.moduleBuilder = moduleBuilder;
  }

  @Override
  public void build(final MavenSession session, final ReactorContext reactorContext,
      ProjectBuildList projectBuilds, final List<TaskSegment> taskSegments,
      ReactorBuildStatus reactorBuildStatus) throws ExecutionException, InterruptedException {

    ProjectDependencyGraph graph = session.getProjectDependencyGraph();

    // log overall build info
    final int degreeOfConcurrency = session.getRequest().getDegreeOfConcurrency();
    logger.info("Task segments : " + Joiner.on(" ").join(taskSegments));
    logger.info("Build maximum degree of concurrency is " + degreeOfConcurrency);
    logger.info("Total number of projects is " + graph.getSortedProjects().size());

    // the actual build execution
    List<Map.Entry<TaskSegment, ReactorBuildStats>> allStats = new ArrayList<>();

    for (TaskSegment taskSegment : taskSegments) {
      Set<MavenProject> projects = projectBuilds.getByTaskSegment(taskSegment).getProjects();

      ReactorBuildStats stats =
          new SmartBuilderImpl(moduleBuilder, session, reactorContext, taskSegment, projects)
              .build();
      allStats.add(new AbstractMap.SimpleEntry<>(taskSegment, stats));
    }

    if (session.getResult().hasExceptions()) {
      // don't report stats of failed builds
      return;
    }

      // log stats of each task segment
      allStats.forEach((entry) -> {
          TaskSegment taskSegment = entry.getKey();
          ReactorBuildStats stats = entry.getValue();
          Set<MavenProject> projects = projectBuilds.getByTaskSegment(taskSegment).getProjects();
          logger.info("Task segment {}, number of projects {}", taskSegment, projects.size());
          final long walltimeReactor = stats.walltimeTime(TimeUnit.NANOSECONDS);
          final long walltimeService = stats.totalServiceTime(TimeUnit.NANOSECONDS);
          final String effectiveConcurrency =
                  String.format("%2.2f", ((double) walltimeService) / walltimeReactor);
          logger.info(
                  "Segment walltime {} s, segment projects service time {} s, effective/maximum degree of concurrency {}/{}",
                  TimeUnit.NANOSECONDS.toSeconds(walltimeReactor),
                  TimeUnit.NANOSECONDS.toSeconds(walltimeService), effectiveConcurrency,
                  degreeOfConcurrency);
          if (projects.size() > 1 && isProfiling(session)) {
              logger.info(stats.renderCriticalPath(graph));
          }
      });
  }

  private boolean isProfiling(MavenSession session) {
    String value = session.getUserProperties().getProperty(PROP_PROFILING);

    if (value == null) {
      value = session.getSystemProperties().getProperty(PROP_PROFILING);
    }

    return Boolean.parseBoolean(value);
  }
}
