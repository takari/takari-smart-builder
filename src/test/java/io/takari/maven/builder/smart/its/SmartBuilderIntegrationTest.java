package io.takari.maven.builder.smart.its;

import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenExecution;
import io.takari.maven.testing.executor.MavenExecutionResult;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.2.3", "3.2.5", "3.3.1"})
public class SmartBuilderIntegrationTest {

  @Rule
  public final TestResources resources = new TestResources();

  public final TestProperties proprties = new TestProperties();;

  public final MavenRuntime verifier;

  public SmartBuilderIntegrationTest(MavenRuntimeBuilder runtimeBuilder) throws Exception {
    this.verifier = runtimeBuilder.withExtension(new File("target/classes").getCanonicalFile()) //
        .build();
  }

  @Test
  public void testBasic() throws Exception {
    File basedir = resources.getBasedir("basic-it");
    MavenExecution execution = verifier.forProject(basedir) //
        .withCliOptions("--builder", "smart") //
        .withCliOptions("-T", "2") //
        .withCliOption("-Dmaven.profile=true") //
        .withCliOption("-e");
    MavenExecutionResult result = execution.execute("package");
    result.assertErrorFreeLog();

    TestResources.assertFilesPresent(basedir, ".mvn/timing.properties");

    result = execution.execute("package");
    result.assertErrorFreeLog();
  }

  @Test
  public void testTimingProperty() throws Exception {
    File basedir = resources.getBasedir("basic-it");
    String myFolder = basedir + ".mvn/";
    String myFile = "timing.properties.0";
    MavenExecution execution = verifier.forProject(basedir) //
        .withCliOptions("--builder", "smart") //
        .withCliOption("-Dtiming.properties.folder=" + myFolder)
        .withCliOption("-Dtiming.properties.file=" + myFile); //
    MavenExecutionResult result = execution.execute("package");
    result.assertErrorFreeLog();

    TestResources.assertFilesPresent(new File(myFolder), myFile);

    result = execution.execute("package");
    result.assertErrorFreeLog();
  }

  @Test
  public void testTimingPropertyWithFolderOnly() throws Exception {
    File basedir = resources.getBasedir("basic-it");
    String myFolder = basedir + ".mvn/";
    MavenExecution execution = verifier.forProject(basedir) //
        .withCliOptions("--builder", "smart") //
        .withCliOption("-Dtiming.properties.folder=" + myFolder);
    MavenExecutionResult result = execution.execute("package");
    result.assertErrorFreeLog();

    TestResources.assertFilesPresent(new File(myFolder), "timing.properties");

    result = execution.execute("package");
    result.assertErrorFreeLog();
  }
}
