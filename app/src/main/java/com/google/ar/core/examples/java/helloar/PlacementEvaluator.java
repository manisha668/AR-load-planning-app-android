package com.google.ar.core.examples.java.helloar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic for validating a container's placement and suggesting better positions.
 *
 * This class does not know anything about ARCore. It only works with:
 *  - container id and weight
 *  - logical ramp position (e.g. 2R, 4L)
 *  - loading plan and position limits
 */
public class PlacementEvaluator {

  public static class Result {
    public final boolean positionMatchesPlan;
    public final boolean withinWeightLimit;
    public final boolean overallOk;
    public final RampPosition actualPosition;
    public final RampPosition expectedPosition;   // may be null
    public final RampPosition suggestedPosition;  // may be null

    public Result(
        boolean positionMatchesPlan,
        boolean withinWeightLimit,
        RampPosition actualPosition,
        RampPosition expectedPosition,
        RampPosition suggestedPosition) {
      this.positionMatchesPlan = positionMatchesPlan;
      this.withinWeightLimit = withinWeightLimit;
      this.overallOk = positionMatchesPlan && withinWeightLimit;
      this.actualPosition = actualPosition;
      this.expectedPosition = expectedPosition;
      this.suggestedPosition = suggestedPosition;
    }
  }

  private final LoadingPlan loadingPlan;
  private final int totalRows;

  // Current occupancy: position code -> container id
  private final Map<String, String> positionToContainer = new HashMap<>();
  private final Map<String, String> containerToPosition = new HashMap<>();

  public PlacementEvaluator(LoadingPlan loadingPlan, int totalRows) {
    this.loadingPlan = loadingPlan;
    this.totalRows = totalRows;
  }

  public Result evaluate(String containerId, float weightKg, RampPosition actualPosition) {
    String actualCode = actualPosition.asCode();
    LoadingPlan.PlanEntry planEntry = loadingPlan.getEntry(containerId);

    RampPosition expectedPos = null;
    boolean positionMatches = false;
    if (planEntry != null && planEntry.expectedPositionCode != null) {
      positionMatches = planEntry.expectedPositionCode.equalsIgnoreCase(actualCode);
      expectedPos = parsePositionCode(planEntry.expectedPositionCode);
    }

    float allowedMax = loadingPlan.getMaxWeightForPosition(actualCode);
    boolean withinLimit = weightKg <= allowedMax;

    // Register occupancy of the actual position by this container.
    registerOccupancy(containerId, actualCode);

    // If not OK, compute a better suggestion.
    RampPosition suggestion = null;
    if (!(positionMatches && withinLimit)) {
      suggestion = findNearestValidPosition(weightKg, actualPosition);
    }

    return new Result(positionMatches, withinLimit, actualPosition, expectedPos, suggestion);
  }

  private void registerOccupancy(String containerId, String positionCode) {
    // Remove old position if any.
    String oldPos = containerToPosition.get(containerId);
    if (oldPos != null) {
      positionToContainer.remove(oldPos);
    }

    containerToPosition.put(containerId, positionCode);
    positionToContainer.put(positionCode, containerId);
  }

  private RampPosition parsePositionCode(String code) {
    if (code == null || code.length() < 2) return null;
    try {
      int row = Integer.parseInt(code.substring(0, code.length() - 1));
      char sideChar = Character.toUpperCase(code.charAt(code.length() - 1));
      RampPosition.Side side =
          (sideChar == 'L') ? RampPosition.Side.L : RampPosition.Side.R;
      return new RampPosition(row, side);
    } catch (Exception e) {
      return null;
    }
  }

  private RampPosition findNearestValidPosition(float weightKg, RampPosition fromPosition) {
    String fromCode = fromPosition.asCode();

    // Build list of all possible positions for this aircraft (rows x 2 sides).
    Set<RampPosition> candidates = new HashSet<>();
    for (int row = 1; row <= totalRows; row++) {
      candidates.add(new RampPosition(row, RampPosition.Side.L));
      candidates.add(new RampPosition(row, RampPosition.Side.R));
    }

    RampPosition best = null;
    int bestDistance = Integer.MAX_VALUE;

    for (RampPosition candidate : candidates) {
      String code = candidate.asCode();

      // Skip occupied positions.
      if (positionToContainer.containsKey(code)) {
        continue;
      }

      // Skip positions that cannot carry the weight.
      float max = loadingPlan.getMaxWeightForPosition(code);
      if (weightKg > max) {
        continue;
      }

      // "Distance" in discrete ramp space: difference in row + side mismatch.
      int distance = Math.abs(candidate.getRow() - fromPosition.getRow());
      if (candidate.getSide() != fromPosition.getSide()) {
        distance += 1;
      }

      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }

    return best;
  }
}


