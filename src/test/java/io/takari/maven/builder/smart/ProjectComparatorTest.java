package io.takari.maven.builder.smart;

import static io.takari.maven.builder.smart.ProjectComparator.id;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Test;

public class ProjectComparatorTest extends AbstractSmartBuilderTest {

  @Test
  public void testPriorityQueueOrder() {
    MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
    TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
    graph.addDependency(b, a);

    Comparator<MavenProject> cmp = ProjectComparator.create0(graph, new HashMap<>(), ProjectComparator::id);

    Queue<MavenProject> queue = new PriorityQueue<>(3, cmp);
    queue.add(a);
    queue.add(b);
    queue.add(c);

    Assert.assertEquals(a, queue.poll());
    Assert.assertEquals(c, queue.poll());
    Assert.assertEquals(b, queue.poll());
  }

  @Test
  public void testPriorityQueueOrder_historicalServiceTimes() {
    MavenProject a = newProject("a"), b = newProject("b"), c = newProject("c");
    TestProjectDependencyGraph graph = new TestProjectDependencyGraph(a, b, c);
    graph.addDependency(b, a);

    HashMap<String, AtomicLong> serviceTimes = new HashMap<>();
    serviceTimes.put(id(a), new AtomicLong(1L));
    serviceTimes.put(id(b), new AtomicLong(1L));
    serviceTimes.put(id(c), new AtomicLong(3L));

    Comparator<MavenProject> cmp = ProjectComparator.create0(graph, serviceTimes, ProjectComparator::id);

    Queue<MavenProject> queue = new PriorityQueue<>(3, cmp);
    queue.add(a);
    queue.add(b);
    queue.add(c);

    Assert.assertEquals(c, queue.poll());
    Assert.assertEquals(a, queue.poll());
    Assert.assertEquals(b, queue.poll());
  }

}
