package io.takari.maven.builder.smart;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import java.util.List;

interface DependencyGraph<K> {
  List<K> getDownstreamProjects(K project);

  List<K> getSortedProjects();

  List<K> getUpstreamProjects(K project);

  static DependencyGraph<MavenProject> fromMaven(ProjectDependencyGraph graph) {
    return new DependencyGraph<MavenProject>() {
      @Override
      public List<MavenProject> getDownstreamProjects(MavenProject project) {
        return graph.getDownstreamProjects(project, false);
      }

      @Override
      public List<MavenProject> getSortedProjects() {
        return graph.getSortedProjects();
      }

      @Override
      public List<MavenProject> getUpstreamProjects(MavenProject project) {
        return graph.getUpstreamProjects(project, false);
      }
    };
  }

}
