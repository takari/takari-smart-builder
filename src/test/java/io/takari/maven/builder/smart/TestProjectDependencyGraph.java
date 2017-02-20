package io.takari.maven.builder.smart;

import java.util.*;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public class TestProjectDependencyGraph implements ProjectDependencyGraph, DependencyGraph<MavenProject> {

  private final List<MavenProject> projects = new ArrayList<MavenProject>();

  private final ListMultimap<MavenProject, MavenProject> downstream = ArrayListMultimap.create();

  private final ListMultimap<MavenProject, MavenProject> upstream = ArrayListMultimap.create();

  public TestProjectDependencyGraph(MavenProject... projects) {
    if (projects != null) {
      this.projects.addAll(Arrays.asList(projects));
    }
  }

  @Override
  public List<MavenProject> getSortedProjects() {
    return projects;
  }

  @Override
  public List<MavenProject> getDownstreamProjects(MavenProject project, boolean transitive) {
    Assert.assertFalse("not implemented", transitive);
    return downstream.get(project);
  }

  @Override
  public List<MavenProject> getDownstreamProjects(MavenProject project) {
    return downstream.get(project);
  }

  @Override
  public List<MavenProject> getUpstreamProjects(MavenProject project, boolean transitive) {
    Assert.assertFalse("not implemented", transitive);
    return upstream.get(project);
  }

  @Override
  public List<MavenProject> getUpstreamProjects(MavenProject project) {
    return upstream.get(project);
  }

  public void addProject(MavenProject project) {
    projects.add(project);
  }

  public void addDependency(MavenProject from, MavenProject to) {
    // 'from' depends on 'to'
    // 'from' is a downstream dependency of 'to'
    // 'to' is upstream dependency of 'from'
    this.upstream.put(from, to);
    this.downstream.put(to, from);
  }
}
