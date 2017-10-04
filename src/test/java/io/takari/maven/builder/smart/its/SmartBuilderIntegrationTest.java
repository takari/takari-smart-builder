package io.takari.maven.builder.smart.its;

import static io.takari.maven.testing.TestResources.assertFilesNotPresent;
import static io.takari.maven.testing.TestResources.assertFilesPresent;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenExecution;
import io.takari.maven.testing.executor.MavenExecutionResult;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.3.1", "3.3.9"})
public class SmartBuilderIntegrationTest {

  @Rule
  public final TestResources resources = new TestResources();

  public final TestProperties proprties = new TestProperties();

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
  }

  @Test
  public void testAggregatorPlugin() throws Exception {
    verifier.forProject(resources.getBasedir("aggregator-plugin")) //
        .execute("clean", "install") //
        .assertErrorFreeLog();

    File basedir = resources.getBasedir("basic-it");

    MavenExecutionResult result = verifier.forProject(basedir) //
        .withCliOptions("--builder", "smart") //
        .withCliOptions("-e", "-T2") //
        .execute("clean", "package", "io.takari.maven.smartbuilder.tests:aggregator-plugin:0.1-SNAPSHOT:aggregate");

    result.assertErrorFreeLog();

    assertFilesPresent(basedir, "target/aggregator.txt");
    assertFilesNotPresent(basedir, //
        "basic-it-module-a/target/aggregator.txt", //
        "basic-it-module-b/target/aggregator.txt", //
        "basic-it-module-c/target/aggregator.txt");
  }
}
