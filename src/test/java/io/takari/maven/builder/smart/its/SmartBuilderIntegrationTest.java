package io.takari.maven.builder.smart.its;

import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.it.*;

import java.io.File;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class SmartBuilderIntegrationTest {

  @Rule
  public final TestResources resources;

  public final VerifierRuntime verifier;

  public final TestProperties proprties;

  @Parameters(name = "maven-{0}")
  public static Iterable<Object[]> mavenVersions() {
    return Arrays.<Object[]>asList( //
        new Object[] {"3.2.1"} //
        , new Object[] {"3.2.2"} //
        );
  }

  public SmartBuilderIntegrationTest(String mavenVersion) throws Exception {
    this.resources = new TestResources("src/test/projects", "target/it/" + mavenVersion + "/");
    this.proprties = new TestProperties();
    this.verifier = VerifierRuntime.builder(mavenVersion) //
        .withExtension(new File("target/classes").getCanonicalFile()) //
        .build();
  }

  @Test
  public void testBasic() throws Exception {
    File basedir = resources.getBasedir("basic-it");
    Verifier execution = verifier.forProject(basedir) //
        .withCliOptions("--builder", "smart") //
        .withCliOptions("-T", "2") //
        .withCliOption("-Dmaven.profile=true") //
        .withCliOption("-e");
    VerifierResult result = execution.execute("package");
    result.assertErrorFreeLog();

    TestResources.assertFilesPresent(basedir, ".mvn/timing.properties");

    result = execution.execute("package");
    result.assertErrorFreeLog();
  }
}
