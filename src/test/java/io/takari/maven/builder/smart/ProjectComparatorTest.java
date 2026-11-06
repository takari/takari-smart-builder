package io.takari.maven.builder.smart;

import static io.takari.maven.builder.smart.ProjectComparator.id;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

public class ProjectComparatorTest extends AbstractSmartBuilderTest {

    @Test
    public void testPriorityQueueOrder() {
        MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
        graph.addDependency(b, a);
        DependencyGraph<MavenProject> dp = DependencyGraph.fromMaven(graph);

        Comparator<MavenProject> cmp = ProjectComparator.create0(dp, new HashMap<>(), ProjectComparator::id);

        Queue<MavenProject> queue = new PriorityQueue<>(3, cmp);
        queue.add(a);
        queue.add(b);
        queue.add(c);

        assertEquals(a, queue.poll());
        assertEquals(c, queue.poll());
        assertEquals(b, queue.poll());
    }

    @Test
    public void testPriorityQueueOrder_historicalServiceTimes() {
        MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
        graph.addDependency(b, a);
        DependencyGraph<MavenProject> dp = DependencyGraph.fromMaven(graph);

        HashMap<String, AtomicLong> serviceTimes = new HashMap<>();
        serviceTimes.put(id(a), new AtomicLong(1L));
        serviceTimes.put(id(b), new AtomicLong(1L));
        serviceTimes.put(id(c), new AtomicLong(3L));

        Comparator<MavenProject> cmp = ProjectComparator.create0(dp, serviceTimes, ProjectComparator::id);

        Queue<MavenProject> queue = new PriorityQueue<>(3, cmp);
        queue.add(a);
        queue.add(b);
        queue.add(c);

        assertEquals(c, queue.poll());
        assertEquals(a, queue.poll());
        assertEquals(b, queue.poll());
    }

    @Test
    public void testTieBreakByTransitiveDownstreamCount() {
        // same weight (chain length 2); "a" unblocks two projects, "z" only one
        MavenProject a = newProject("a"), a1 = newProject("a1"), a2 = newProject("a2");
        MavenProject z = newProject("z"), z1 = newProject("z1");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, a1, a2, z, z1);
        graph.addDependency(a1, a);
        graph.addDependency(a2, a);
        graph.addDependency(z1, z);
        Comparator<MavenProject> cmp = ProjectComparator.create(DependencyGraph.fromMaven(graph));

        assertTrue(cmp.compare(a, z) < 0);
    }

    @Test
    public void testPomPackagingIsCheap() {
        // z(pom) <- z1 vs. r <- r1: without packaging cost both chains weigh 2 and "z" wins by key
        MavenProject z = newProject("z"), z1 = newProject("z1");
        MavenProject r = newProject("r"), r1 = newProject("r1");
        z.setPackaging("pom");
        TestProjectDependencyGraph graph = new TestProjectDependencyGraph(z, z1, r, r1);
        graph.addDependency(z1, z);
        graph.addDependency(r1, r);
        Comparator<MavenProject> cmp = ProjectComparator.create(DependencyGraph.fromMaven(graph));

        assertTrue(cmp.compare(r, z) < 0);
    }
}
