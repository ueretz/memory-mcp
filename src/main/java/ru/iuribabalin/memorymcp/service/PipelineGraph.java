package ru.iuribabalin.memorymcp.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure graph helpers shared by pipeline validation (which walks {@code StepRequest} indexes) and the
 * run engine (which walks {@code PipelineStep} order indexes). Both build their own list of route
 * targets per step and hand it here, so the one thing that must not be reimplemented twice - the
 * implicit {@code LOOP_END -> LOOP} return edge, which exists in the graph without the author ever
 * drawing it - lives in a single place.
 */
final class PipelineGraph {

    private PipelineGraph() {
    }

    /**
     * Route targets plus the implicit returns. {@code implicitReturnTargets.get(i)} is the step a
     * LOOP_END at index {@code i} hands control back to, or null for every other kind. A return edge
     * is part of the graph, not a special case the run engine remembers at the last moment: without
     * it, the steps between a LOOP and its LOOP_END are not in a cycle, and nothing resets them for
     * the next iteration.
     */
    static List<List<Integer>> adjacency(List<List<Integer>> routeTargets, List<Integer> implicitReturnTargets) {
        List<List<Integer>> adjacency = new ArrayList<>(routeTargets.size());
        for (int i = 0; i < routeTargets.size(); i++) {
            List<Integer> targets = new ArrayList<>(routeTargets.get(i));
            Integer implicitReturn = implicitReturnTargets.get(i);
            if (implicitReturn != null && !targets.contains(implicitReturn)) {
                targets.add(implicitReturn);
            }
            adjacency.add(targets);
        }
        return adjacency;
    }

    /**
     * Steps reachable from {@code start} without entering any step in {@code stopAt}. Excludes
     * {@code start} itself unless something leads back to it - the same shape the fan-out check has
     * always had, where the walk stops at the JOIN that merges the branches.
     */
    static Set<Integer> reachableUntil(int start, List<List<Integer>> adjacency, Set<Integer> stopAt) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int next : adjacency.get(current)) {
                if (stopAt.contains(next) || !seen.add(next)) {
                    continue;
                }
                stack.push(next);
            }
        }
        return seen;
    }

    /**
     * The body of a loop: its body route's first step and everything reachable from there without
     * passing through the loop itself. Used both to validate that a LOOP_END closes the loop it
     * claims to and to decide which steps a new iteration must reset.
     */
    static Set<Integer> loopBody(int loopIndex, Integer bodyTarget, List<List<Integer>> adjacency) {
        if (bodyTarget == null) {
            return Set.of();
        }
        Set<Integer> body = new HashSet<>(reachableUntil(bodyTarget, adjacency, Set.of(loopIndex)));
        body.add(bodyTarget);
        body.remove(loopIndex);
        return body;
    }

    /**
     * The steps an ORCHESTRATOR delegates: everything reachable from it without entering the
     * ORCHESTRATOR_END that closes its region. The region start and end are not themselves part of
     * it - they are markers, not work.
     */
    static Set<Integer> orchestratedRegion(int orchestratorIndex, int endIndex, List<List<Integer>> adjacency) {
        Set<Integer> region = new HashSet<>(reachableUntil(orchestratorIndex, adjacency, Set.of(endIndex)));
        region.remove(orchestratorIndex);
        return region;
    }
}
