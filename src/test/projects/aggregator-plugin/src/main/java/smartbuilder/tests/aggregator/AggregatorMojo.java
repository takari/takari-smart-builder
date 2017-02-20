package smartbuilder.tests.aggregator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mojo(name = "aggregate", aggregator = true)
public class AggregatorMojo extends AbstractMojo {

  private final Logger log = LoggerFactory.getLogger(getClass());

  @Parameter(defaultValue = "${project.build.directory}/aggregator.txt")
  private File file;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      file.getParentFile().mkdirs();
      new FileOutputStream(file).close();
      log.info("aggregated");
    } catch (IOException e) {
      throw new MojoExecutionException("Could not aggregate", e);
    }
  }

}
