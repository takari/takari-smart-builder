/*
 * Copyright (c) 2014-2024 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache Software License v2.0
 * which accompanies this distribution, and is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package io.takari.maven.builder.smart;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.apache.maven.project.MavenProject;

/**
 * Project comparator (factory) that uses project build time to establish build order.
 * <p>
 * Internally, each project is assigned a weight, which is calculated as sum of project build time
 * and maximum weight of any of the project's downstream dependencies. The project weights are
 * calculated by recursively traversing project dependency graph starting from build root projects,
 * i.e. projects that do not have any upstream dependencies.
 * <p>
 * Project build times are estimated based on values persisted during a previous build. Average
 * build time is used for projects that do not have persisted build time.
 * <p>
 * If there are no persisted build times, all projects build times are assumed the same (arbitrary)
 * value of 1. This means that the project with the longest downstream dependency trail will be
 * built first.
 * <p>
 * Currently, historical build times are stored in
 * <code>${session.request/baseDirectory}/.mvn/timing.properties</code> file. The timings file is
 * written only if <code>${session.request/baseDirectory}/.mvn</code> directory is already present.
 */
class ProjectComparator {

    public static Comparator<MavenProject> create(DependencyGraph<MavenProject> graph) {
        return create0(graph, Collections.emptyMap(), ProjectComparator::id);
    }

    static <K> Comparator<K> create0(
            DependencyGraph<K> dependencyGraph,
            Map<String, AtomicLong> historicalServiceTimes,
            Function<K, String> toKey) {
        final long defaultServiceTime = average(historicalServiceTimes.values());

        final Map<K, Long> serviceTimes = new HashMap<>();

        final Set<K> rootProjects = new HashSet<>();
        dependencyGraph.getProjects().forEach(project -> {
            long serviceTime = getServiceTime(historicalServiceTimes, project, defaultServiceTime, toKey);
            serviceTimes.put(project, serviceTime);
            if (dependencyGraph.isRoot(project)) {
                rootProjects.add(project);
            }
        });

        final Map<K, Long> projectWeights = calculateWeights(dependencyGraph, serviceTimes, rootProjects);

        return Comparator.comparingLong((ToLongFunction<K>) projectWeights::get)
                .thenComparing(toKey, String::compareTo)
                .reversed();
    }

    private static long average(Collection<AtomicLong> values) {
        return (long)
                (values.stream().mapToLong(AtomicLong::longValue).average().orElse(1.0d));
    }

    private static <K> long getServiceTime(
            Map<String, AtomicLong> serviceTimes, K project, long defaultServiceTime, Function<K, String> toKey) {
        AtomicLong serviceTime = serviceTimes.get(toKey.apply(project));
        return serviceTime != null ? serviceTime.longValue() : defaultServiceTime;
    }

    private static <K> Map<K, Long> calculateWeights(
            DependencyGraph<K> dependencyGraph, Map<K, Long> serviceTimes, Collection<K> rootProjects) {
        Map<K, Long> weights = new HashMap<>();
        for (K rootProject : rootProjects) {
            calculateWeights(dependencyGraph, serviceTimes, rootProject, weights);
        }
        return weights;
    }

    /**
     * Returns the maximum sum of build time along a path from the project to an exit project. An
     * "exit project" is a project without downstream dependencies.
     */
    private static <K> long calculateWeights(
            DependencyGraph<K> dependencyGraph, Map<K, Long> serviceTimes, K project, Map<K, Long> weights) {
        long weight = serviceTimes.get(project)
                + dependencyGraph
                        .getDownstreamProjects(project)
                        .mapToLong(successor -> {
                            long successorWeight;
                            if (weights.containsKey(successor)) {
                                successorWeight = weights.get(successor);
                            } else {
                                successorWeight = calculateWeights(dependencyGraph, serviceTimes, successor, weights);
                            }
                            return successorWeight;
                        })
                        .max()
                        .orElse(0);
        weights.put(project, weight);
        return weight;
    }

    static String id(MavenProject project) {
        return project.getGroupId() + ':' + project.getArtifactId() + ':' + project.getVersion();
    }
}
