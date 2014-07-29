package io.takari.maven.builder.smart;

import java.util.*;

import org.apache.maven.project.MavenProject;
import org.junit.Assert;

abstract class AbstractSmartBuilderTest {
  protected void assertProjects(Collection<MavenProject> actual, MavenProject... expected) {
    Assert.assertEquals(new HashSet<MavenProject>(Arrays.asList(expected)), new HashSet<>(actual));
  }

  protected MavenProject newProject(String artifactId) {
    MavenProject project = new MavenProject();
    project.setGroupId("test");
    project.setArtifactId(artifactId);
    project.setVersion("1");
    return project;
  }

}
