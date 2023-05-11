package io.takari.maven.builder.smart;

import java.text.NumberFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.ProjectBuildList;
import org.apache.maven.lifecycle.internal.ReactorBuildStatus;
import org.apache.maven.lifecycle.internal.ReactorContext;
import org.apache.maven.lifecycle.internal.TaskSegment;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private volatile SmartBuilderImpl builder;
  private volatile boolean canceled;

  private static SmartBuilder INSTANCE;

  public static SmartBuilder cancel() {
    SmartBuilder builder = INSTANCE;
    if (builder != null) {
      builder.doCancel();
    }
    return builder;
  }

  @Inject
  public SmartBuilder(LifecycleModuleBuilder moduleBuilder) {
    this.moduleBuilder = moduleBuilder;
    INSTANCE = this;
  }

  void doCancel() {
    canceled = true;
    SmartBuilderImpl b = builder;
    if (b != null) {
      b.cancel();
    }
  }

  public void doneCancel() {
    canceled = false;
  }

  @Override
  public synchronized void build(final MavenSession session, final ReactorContext reactorContext,
      ProjectBuildList projectBuilds, final List<TaskSegment> taskSegments,
      ReactorBuildStatus reactorBuildStatus) throws ExecutionException, InterruptedException {

    session.getRepositorySession().getData().set(ReactorBuildStatus.class, reactorBuildStatus);

    DependencyGraph<MavenProject> graph = DependencyGraph.fromMaven(session);

    // log overall build info
    final int degreeOfConcurrency = session.getRequest().getDegreeOfConcurrency();

    if (logger.isDebugEnabled()) {
      logger.debug("Task segments : {}", taskSegments);
      logger.debug("Build maximum degree of concurrency is {}", degreeOfConcurrency);
      logger.debug("Total number of projects is {}", graph.getProjects().count());
    }

    // the actual build execution
    List<Map.Entry<TaskSegment, ReactorBuildStats>> allstats = new ArrayList<>();
    for (TaskSegment taskSegment : taskSegments) {
      Set<MavenProject> projects = projectBuilds.getByTaskSegment(taskSegment).getProjects();
      if (canceled) {
        return;
      }
      builder = new SmartBuilderImpl(moduleBuilder, session, reactorContext, taskSegment, projects, graph);
      try {
        ReactorBuildStats stats = builder.build();
        allstats.add(new AbstractMap.SimpleEntry<>(taskSegment, stats));
      } finally {
        builder = null;
      }
    }

    if (session.getResult().hasExceptions()) {
      // don't report stats of failed builds
      return;
    }

    // log stats of each task segment
    boolean profiling = isProfiling(session);
    for (Map.Entry<TaskSegment, ReactorBuildStats> entry : allstats) {
      TaskSegment taskSegment = entry.getKey();
      ReactorBuildStats stats = entry.getValue();
      Set<MavenProject> projects = projectBuilds.getByTaskSegment(taskSegment).getProjects();

      if (profiling || logger.isDebugEnabled()) {
          logger.info("Task segment {}, number of projects {}", taskSegment, projects.size());
      }

      final long walltimeReactor = stats.walltimeTime(TimeUnit.NANOSECONDS);
      final long walltimeService = stats.totalServiceTime(TimeUnit.NANOSECONDS);
      double effective = ((double) walltimeService) / walltimeReactor;
      final String effectiveConcurrency = String.format("%2.2f", effective);
      if (profiling || logger.isDebugEnabled()) {
        logger.info(
            "Segment walltime {} s, segment projects service time {} s, effective/maximum degree of concurrency {}/{}",
            TimeUnit.NANOSECONDS.toSeconds(walltimeReactor),
            TimeUnit.NANOSECONDS.toSeconds(walltimeService), effectiveConcurrency,
            degreeOfConcurrency);
        if (projects.size() > 1) {
          logger.info(stats.renderCriticalPath(graph));
        }
      } else {
        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(2);
        logger.info("------------------------------------------------------------------------");
        String averageWallTime = String.format("%2.2f",
            TimeUnit.NANOSECONDS.toSeconds(walltimeReactor) / (double) projects.size());
        logger.info("Average project wall time: {}s", averageWallTime);
        double percentage = effective / degreeOfConcurrency;
        logger.info("Total concurrency: {}",
            NumberFormat.getPercentInstance().format(percentage));
        if (percentage < 0.8) {
          List<String> bottleneckProjects = stats.getBottleneckProjects();
          if (bottleneckProjects.size() > 0) {
            logger.info(
                "Bottleneck projects that decrease concurrency: (run build with -D{}=true for further details)",
                PROP_PROFILING);
            for (String project : bottleneckProjects) {
              logger.info("\t- {}", project);
            }
          }
        }
      }
    }
  }

  private boolean isProfiling(MavenSession session) {
    String value = session.getUserProperties().getProperty(PROP_PROFILING);
    if (value == null) {
      value = session.getSystemProperties().getProperty(PROP_PROFILING);
    }
    return Boolean.parseBoolean(value);
  }

}
