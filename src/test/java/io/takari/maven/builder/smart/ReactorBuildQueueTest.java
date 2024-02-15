package io.takari.maven.builder.smart;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.maven.project.MavenProject;
import org.junit.Test;

public class ReactorBuildQueueTest extends AbstractSmartBuilderTest {

    @Test
    public void testBasic() {
        MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
        graph.addDependency(b, a);
        DependencyGraph<MavenProject> dp = DependencyGraph.fromMaven(graph);

        ReactorBuildQueue schl = new ReactorBuildQueue(graph.getSortedProjects(), dp);

        assertProjects(schl.getRootProjects(), a, c);
        assertFalse(schl.isEmpty());

        assertProjects(schl.onProjectFinish(a), b);
        assertTrue(schl.isEmpty());
    }

    @Test
    public void testNoDependencies() {
        MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
        DependencyGraph<MavenProject> dp = DependencyGraph.fromMaven(graph);

        ReactorBuildQueue schl = new ReactorBuildQueue(graph.getSortedProjects(), dp);

        assertProjects(schl.getRootProjects(), a, b, c);
        assertTrue(schl.isEmpty());
    }

    @Test
    public void testMultipleUpstreamDependencies() {
        MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
        graph.addDependency(b, a);
        graph.addDependency(b, c);
        DependencyGraph<MavenProject> dp = DependencyGraph.fromMaven(graph);

        ReactorBuildQueue schl = new ReactorBuildQueue(graph.getSortedProjects(), dp);

        assertProjects(schl.getRootProjects(), a, c);
        assertFalse(schl.isEmpty());

        assertProjects(schl.onProjectFinish(a), new MavenProject[0]);
        assertFalse(schl.isEmpty());

        assertProjects(schl.onProjectFinish(c), b);
        assertTrue(schl.isEmpty());
    }
}
