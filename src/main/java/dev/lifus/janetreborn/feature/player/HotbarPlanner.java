package dev.lifus.janetreborn.feature.player;

import dev.lifus.janetreborn.service.inventory.ItemScore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class HotbarPlanner {
  private static final long MATCH_BASE = 2_000_000_000_000_000L;
  private static final long PREFERENCE_SCALE = 1_000_000_000L;
  private static final long QUALITY_SCALE = 1_000_000L;
  private static final long FALLBACK_BASE = 100_000_000L;
  private static final long FALLBACK_STAY_BONUS = 50_000_000L;
  private static final long STAY_BONUS = 10_000L;
  private static final int PREFERENCE_CEILING = 100_000;

  public Plan plan(List<HotbarSlotConfig> slots, List<Candidate> candidates) {
    return plan(slots, candidates, DefaultScorer.INSTANCE);
  }

  Plan plan(List<HotbarSlotConfig> slots, List<Candidate> candidates, Scorer scorer) {
    int targetCount = Math.min(HotbarLayoutCodec.SLOT_COUNT, slots.size());
    candidates =
        candidates.stream()
            .filter(
                candidate ->
                    candidate.currentTarget() < 0
                        || candidate.currentTarget() >= targetCount
                        || slots.get(candidate.currentTarget()).mode() == SlotMode.MANAGED)
            .toList();
    int candidateCount = candidates.size();
    int source = 0;
    int targetStart = 1;
    int candidateStart = targetStart + targetCount;
    int dummyStart = candidateStart + candidateCount;
    int sink = dummyStart + targetCount;
    MinCostFlow flow = new MinCostFlow(sink + 1);

    for (int target = 0; target < targetCount; target++) {
      flow.addEdge(source, targetStart + target, 1, 0);
      flow.addEdge(dummyStart + target, sink, 1, 0);
      flow.addEdge(targetStart + target, dummyStart + target, 1, 0);
    }
    for (int candidate = 0; candidate < candidateCount; candidate++) {
      flow.addEdge(candidateStart + candidate, sink, 1, 0);
    }

    for (int target = 0; target < targetCount; target++) {
      HotbarSlotConfig slot = slots.get(target);
      if (slot.mode() != SlotMode.MANAGED) continue;
      for (int candidate = 0; candidate < candidateCount; candidate++) {
        Candidate item = candidates.get(candidate);
        int preference = firstMatch(slot, item.stack());
        long weight;
        if (preference >= 0) {
          long preferenceBonus =
              (PREFERENCE_CEILING - Math.min(preference, PREFERENCE_CEILING - 1L))
                  * PREFERENCE_SCALE;
          long quality = qualityRank(slot.preferences().get(preference), item, candidates, scorer);
          weight = MATCH_BASE + preferenceBonus + quality * QUALITY_SCALE;
        } else if (slot.fallback() == FallbackPolicy.BEST_AVAILABLE && item.useful()) {
          weight = FALLBACK_BASE + fallbackRank(item, candidates, scorer) * QUALITY_SCALE;
        } else if (slot.fallback() == FallbackPolicy.KEEP_CURRENT
            && item.currentTarget() == target) {
          weight = FALLBACK_BASE;
        } else {
          continue;
        }
        if (item.currentTarget() == target) {
          weight += preference >= 0 ? STAY_BONUS : FALLBACK_STAY_BONUS;
        }
        weight += deterministicBonus(item.sourceSlot());
        flow.addEdge(targetStart + target, candidateStart + candidate, 1, -weight);
      }
    }

    flow.run(source, sink, targetCount);
    List<Assignment> assignments = new ArrayList<>(targetCount);
    for (int target = 0; target < targetCount; target++) {
      HotbarSlotConfig slot = slots.get(target);
      Candidate selected = null;
      for (MinCostFlow.Edge edge : flow.graph(targetStart + target)) {
        if (edge.to >= candidateStart
            && edge.to < candidateStart + candidateCount
            && edge.capacity == 0) {
          selected = candidates.get(edge.to - candidateStart);
          break;
        }
      }
      int preference = selected == null ? -1 : firstMatch(slot, selected.stack());
      AssignmentKind kind =
          selected == null
              ? AssignmentKind.NONE
              : preference >= 0 ? AssignmentKind.PREFERENCE : AssignmentKind.FALLBACK;
      assignments.add(new Assignment(target, slot.mode(), selected, kind, preference));
    }
    return new Plan(assignments);
  }

  private static int firstMatch(HotbarSlotConfig slot, ItemStack stack) {
    for (int index = 0; index < slot.preferences().size(); index++) {
      if (slot.preferences().get(index).matches(stack)) return index;
    }
    return -1;
  }

  private static int qualityRank(
      ItemSelector selector, Candidate selected, List<Candidate> candidates, Scorer scorer) {
    double selectedScore = scorer.selector(selector, selected.stack());
    return 1
        + (int)
            candidates.stream()
                .filter(candidate -> selector.matches(candidate.stack()))
                .filter(candidate -> scorer.selector(selector, candidate.stack()) < selectedScore)
                .count();
  }

  private static int fallbackRank(Candidate selected, List<Candidate> candidates, Scorer scorer) {
    double selectedScore = scorer.fallback(selected.stack());
    return 1
        + (int)
            candidates.stream()
                .filter(Candidate::useful)
                .filter(candidate -> scorer.fallback(candidate.stack()) < selectedScore)
                .count();
  }

  private static int deterministicBonus(int sourceSlot) {
    return Math.max(0, 1_000 - Math.min(sourceSlot, 1_000));
  }

  interface Scorer {
    double selector(ItemSelector selector, ItemStack stack);

    double fallback(ItemStack stack);
  }

  private enum DefaultScorer implements Scorer {
    INSTANCE;

    @Override
    public double selector(ItemSelector selector, ItemStack stack) {
      return selector.category().orElse(null) == HotbarCategory.FOOD
          ? ItemScore.foodScore(stack)
          : ItemScore.score(stack, EquipmentSlot.MAINHAND);
    }

    @Override
    public double fallback(ItemStack stack) {
      return Math.max(ItemScore.score(stack, EquipmentSlot.MAINHAND), ItemScore.foodScore(stack))
          + Math.log1p(stack.getCount()) * 0.01;
    }
  }

  public record Candidate(int sourceSlot, int currentTarget, ItemStack stack, boolean useful) {
    public Candidate {
      if (stack == null || stack.isEmpty())
        throw new IllegalArgumentException("Candidate is empty");
    }
  }

  public enum AssignmentKind {
    PREFERENCE,
    FALLBACK,
    NONE
  }

  public record Assignment(
      int target, SlotMode mode, Candidate candidate, AssignmentKind kind, int preferenceIndex) {}

  public record Plan(List<Assignment> assignments) {
    public Assignment assignment(int target) {
      return assignments.get(target);
    }

    public Set<Integer> reservedSourceSlots() {
      Set<Integer> reserved = new HashSet<>();
      for (Assignment assignment : assignments) {
        if (assignment.candidate() != null) reserved.add(assignment.candidate().sourceSlot());
      }
      return Set.copyOf(reserved);
    }
  }

  private static final class MinCostFlow {
    private static final long INF = Long.MAX_VALUE / 4;
    private final List<List<Edge>> graph;

    private MinCostFlow(int nodes) {
      graph = new ArrayList<>(nodes);
      for (int node = 0; node < nodes; node++) graph.add(new ArrayList<>());
    }

    private List<Edge> graph(int node) {
      return graph.get(node);
    }

    private void addEdge(int from, int to, int capacity, long cost) {
      Edge forward = new Edge(to, graph.get(to).size(), capacity, cost);
      Edge reverse = new Edge(from, graph.get(from).size(), 0, -cost);
      graph.get(from).add(forward);
      graph.get(to).add(reverse);
    }

    private void run(int source, int sink, int amount) {
      int nodes = graph.size();
      for (int sent = 0; sent < amount; sent++) {
        long[] distance = new long[nodes];
        Arrays.fill(distance, INF);
        int[] previousNode = new int[nodes];
        int[] previousEdge = new int[nodes];
        boolean[] queued = new boolean[nodes];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distance[source] = 0;
        queue.add(source);
        queued[source] = true;
        while (!queue.isEmpty()) {
          int node = queue.removeFirst();
          queued[node] = false;
          for (int edgeIndex = 0; edgeIndex < graph.get(node).size(); edgeIndex++) {
            Edge edge = graph.get(node).get(edgeIndex);
            if (edge.capacity <= 0 || distance[node] + edge.cost >= distance[edge.to]) continue;
            distance[edge.to] = distance[node] + edge.cost;
            previousNode[edge.to] = node;
            previousEdge[edge.to] = edgeIndex;
            if (!queued[edge.to]) {
              queue.addLast(edge.to);
              queued[edge.to] = true;
            }
          }
        }
        if (distance[sink] == INF) return;
        for (int node = sink; node != source; node = previousNode[node]) {
          Edge edge = graph.get(previousNode[node]).get(previousEdge[node]);
          edge.capacity--;
          graph.get(node).get(edge.reverse).capacity++;
        }
      }
    }

    private static final class Edge {
      private final int to;
      private final int reverse;
      private int capacity;
      private final long cost;

      private Edge(int to, int reverse, int capacity, long cost) {
        this.to = to;
        this.reverse = reverse;
        this.capacity = capacity;
        this.cost = cost;
      }
    }
  }
}
