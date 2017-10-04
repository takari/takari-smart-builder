package io.takari.maven.builder.smart;

import com.google.common.collect.ImmutableSet;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reactor build queue manages reactor modules that are waiting for their upstream dependencies
 * build to finish.
 */
class ReactorBuildQueue {

  private final ProjectDependencyGraph dependencyGraph;

  private final Set<MavenProject> rootProjects;

  private final Set<MavenProject> projects;

  /**
   * Projects waiting for other projects to finish
   */
  private final Set<MavenProject> blockedProjects = new HashSet<>();

  private final Set<MavenProject> finishedProjects = new HashSet<>();

  public ReactorBuildQueue(Collection<MavenProject> projects,
      ProjectDependencyGraph dependencyGraph) {
    this.dependencyGraph = dependencyGraph;

    final Set<MavenProject> localRootProjects = new HashSet<>();

    projects.forEach((project) -> {
        if (dependencyGraph.getUpstreamProjects(project, false).isEmpty()) {
            localRootProjects.add(project);
        } else {
            blockedProjects.add(project);
        }
    });

    this.rootProjects = ImmutableSet.copyOf(localRootProjects);
    this.projects = ImmutableSet.copyOf(projects);
  }

  /**
   * Marks specified project as finished building. Returns, possible empty, set of project's
   * downstream dependencies that become ready to build.
   */
  public Set<MavenProject> onProjectFinish(MavenProject project) {
    finishedProjects.add(project);
    Set<MavenProject> downstreamProjects = new HashSet<>();
   
    getDownstreamProjects(project).stream().filter(successor -> blockedProjects.contains(successor) && isProjectReady(successor)).map(successor -> {
        blockedProjects.remove(successor);
          return successor;
      }).forEachOrdered(successor -> {
          downstreamProjects.add(successor);
      });

    return downstreamProjects;
  }

  public List<MavenProject> getDownstreamProjects(MavenProject project) {
    return dependencyGraph.getDownstreamProjects(project, false);
  }

  private boolean isProjectReady(MavenProject project) {
    return dependencyGraph.getUpstreamProjects(project, false).stream().noneMatch(upstream -> !finishedProjects.contains(upstream));
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

  public int getBlockedCount() {
    return blockedProjects.size();
  }

  public int getFinishedCount() {
    return finishedProjects.size();
  }

  public int getReadyCount() {
    return projects.size() - blockedProjects.size() - finishedProjects.size();
  }

  public Set<MavenProject> getReadyProjects() {
    Set<MavenProject> localProjects = new HashSet<>(this.projects);
    localProjects.removeAll(blockedProjects);
    localProjects.removeAll(finishedProjects);
    return localProjects;
  }
}
