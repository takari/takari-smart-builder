package io.takari.maven.builder.smart;

import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.*;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.*;
import org.apache.maven.lifecycle.internal.builder.Builder;

/**
 * Trivial Maven {@link Builder} implementation. All interesting stuff happens in
 * {@link SmartBuilderImpl} .
 */
@Singleton
@Named("smart")
public class SmartBuilder implements Builder {

  private final LifecycleModuleBuilder lifecycleModuleBuilder;

  @Inject
  public SmartBuilder(LifecycleModuleBuilder lifecycleModuleBuilder) {
    this.lifecycleModuleBuilder = lifecycleModuleBuilder;
  }

  @Override
  public void build(final MavenSession session, final ReactorContext reactorContext,
      ProjectBuildList projectBuilds, final List<TaskSegment> taskSegments,
      ReactorBuildStatus reactorBuildStatus) throws ExecutionException, InterruptedException {
    new SmartBuilderImpl(lifecycleModuleBuilder, session, reactorContext, taskSegments).build();
  }

}
