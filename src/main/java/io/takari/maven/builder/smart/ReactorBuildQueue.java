package io.takari.maven.builder.smart;

import java.util.*;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

/**
 * Reactor build queue manages reactor modules that are waiting for their upstream dependencies
 * build to finish.
 */
class ReactorBuildQueue {

  private final ProjectDependencyGraph dependencyGraph;

  private final Set<MavenProject> rootProjects;

  /**
   * Projects waiting for other projects to finish
   */
  private final Set<MavenProject> blockedProjects = new HashSet<MavenProject>();

  private final Set<MavenProject> finishedProjects = new HashSet<MavenProject>();

  public ReactorBuildQueue(ProjectDependencyGraph dependencyGraph) {
    this.dependencyGraph = dependencyGraph;

    final List<MavenProject> projects = dependencyGraph.getSortedProjects();
    final Set<MavenProject> rootProjects = new HashSet<MavenProject>();

    for (MavenProject project : projects) {
      if (dependencyGraph.getUpstreamProjects(project, false).isEmpty()) {
        rootProjects.add(project);
      } else {
        blockedProjects.add(project);
      }
    }

    this.rootProjects = Collections.unmodifiableSet(rootProjects);
  }

  /**
   * Marks specified project as finished building. Returns, possible empty, set of project's
   * downstream dependencies that become ready to build.
   */
  public Set<MavenProject> onProjectFinish(MavenProject project) {
    finishedProjects.add(project);
    Set<MavenProject> downstreamProjects = new HashSet<MavenProject>();
    for (MavenProject successor : dependencyGraph.getDownstreamProjects(project, false)) {
      if (blockedProjects.contains(successor) && isProjectReady(successor)) {
        blockedProjects.remove(successor);
        downstreamProjects.add(successor);
      }
    }
    return downstreamProjects;
  }

  private boolean isProjectReady(MavenProject project) {
    for (MavenProject upstream : dependencyGraph.getUpstreamProjects(project, false)) {
      if (!finishedProjects.contains(upstream)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@code true} when no more projects are left to schedule.
   */
  public boolean isEmpty() {
    return blockedProjects.isEmpty();
  }

  /**
   * Returns reactor build root projects, that is, projects that do not have upstream dependencies.
   */
  public Set<MavenProject> getRootProjects() {
    return rootProjects;
  }
}
