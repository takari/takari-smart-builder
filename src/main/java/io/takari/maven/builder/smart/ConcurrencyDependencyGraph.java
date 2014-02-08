package io.takari.maven.builder.smart;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Presents a view of the Dependency Graph that is suited for concurrent building for the Smart builder.
 * 
 * @author Brian Toal
 */
public class ConcurrencyDependencyGraph {

  private final ProjectDependencyGraph projectDependencyGraph;

  private final HashSet<MavenProject> finishedProjects = new HashSet<MavenProject>();

  private final List<MavenProject> projects;

  public ConcurrencyDependencyGraph(ProjectDependencyGraph projectDependencyGraph) {
    this.projectDependencyGraph = projectDependencyGraph;
    this.projects = projectDependencyGraph.getSortedProjects();
  }

  public int getNumberOfBuilds() {
    return projects.size();
  }

  /**
   * Gets all the builds that have no reactor-dependencies
   *
   * @return A list of all the initial builds
   */

  public List<MavenProject> getRootSchedulableBuilds() {
    List<MavenProject> result = new ArrayList<MavenProject>();
    for (MavenProject project : projectDependencyGraph.getSortedProjects()) {
      if (projectDependencyGraph.getUpstreamProjects(project, false).size() == 0) {
        result.add(project);
      }
    }
    return result;
  }

  /**
   * Marks the provided project as finished. Returns a list of
   *
   * @param mavenProject The project
   * @return The list of builds that are eligible for starting now that the provided project is done
   */
  public List<MavenProject> markAsFinished(MavenProject mavenProject) {
    finishedProjects.add(mavenProject);
    return getSchedulableNewProcesses(mavenProject);
  }

  private List<MavenProject> getSchedulableNewProcesses(MavenProject finishedProject) {
    List<MavenProject> result = new ArrayList<MavenProject>();
    // schedule dependent projects, if all of their requirements are met
    for (MavenProject dependentProject : projectDependencyGraph.getDownstreamProjects(finishedProject, false)) {
      final List<MavenProject> upstreamProjects = projectDependencyGraph.getUpstreamProjects(dependentProject, false);
      if (finishedProjects.containsAll(upstreamProjects)) {
        result.add(dependentProject);
      }
    }
    return result;
  }

  /**
   * @return set of projects that have yet to be processed successfully by the build.
   */
  public Set<MavenProject> getUnfinishedProjects() {
    Set<MavenProject> unfinished = new HashSet<MavenProject>(projects);
    unfinished.remove(finishedProjects);
    return unfinished;
  }

  /**
   * @return set of projects that have been successfully processed by the build.
   */
  protected Set<MavenProject> getFinishedProjects() {
    return finishedProjects;
  }

  /**
   * For the given {@link MavenProject} {@code p}, return all of {@code p}'s dependencies.
   * 
   * @param p
   * @return List of prerequisite projects
   */
  protected List<MavenProject> getDependencies(MavenProject p) {
    return projectDependencyGraph.getUpstreamProjects(p, false);
  }

  /**
   * For the given {@link MavenProject} {@code p} return {@code p}'s uncompleted dependencies.
   * 
   * @param p
   * @return List of uncompleted prerequisite projects
   */
  public List<MavenProject> getActiveDependencies(MavenProject p) {
    List<MavenProject> activeDependencies = projectDependencyGraph.getUpstreamProjects(p, false);
    activeDependencies.removeAll(finishedProjects);
    return activeDependencies;
  }
}