package com.google.ar.core.examples.java.helloar;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the expected loading plan provided by the supervisor.
 *
 * Example line format:
 *   ContainerID=AKE123, Weight=4800, Position=2R
 *
 * For now this class also contains a simple per-position max-weight model
 * that can be replaced by aircraft‑specific data later.
 */
public class LoadingPlan {

  public static class PlanEntry {
    public final String containerId;
    public final float expectedWeightKg;
    public final String expectedPositionCode;

    public PlanEntry(String containerId, float expectedWeightKg, String expectedPositionCode) {
      this.containerId = containerId;
      this.expectedWeightKg = expectedWeightKg;
      this.expectedPositionCode = expectedPositionCode;
    }
  }

  private final Map<String, PlanEntry> entriesByContainerId = new HashMap<>();

  // Simple max weight limits per logical position (e.g. "2R" -> 5000 kg).
  private final Map<String, Float> maxWeightPerPosition = new HashMap<>();

  public void putEntry(PlanEntry entry) {
    entriesByContainerId.put(entry.containerId, entry);
  }

  public PlanEntry getEntry(String containerId) {
    return entriesByContainerId.get(containerId);
  }

  public void setMaxWeightForPosition(String positionCode, float maxWeightKg) {
    maxWeightPerPosition.put(positionCode, maxWeightKg);
  }

  public float getMaxWeightForPosition(String positionCode) {
    Float value = maxWeightPerPosition.get(positionCode);
    return value != null ? value : Float.MAX_VALUE;
  }

  /**
   * Very small helper that can be used while no external TXT file is wired yet.
   * Creates a hard-coded demo plan for a 4-row aircraft.
   */
  public static LoadingPlan createDemoPlan() {
    LoadingPlan plan = new LoadingPlan();

    // Example expected placements.
    plan.putEntry(new PlanEntry("AKE123", 4800f, "2R"));
    plan.putEntry(new PlanEntry("AKE456", 3000f, "1L"));

    // Example structural limits (kg) – these are placeholders.
    String[] positions = {"1L", "1R", "2L", "2R", "3L", "3R", "4L", "4R"};
    for (String pos : positions) {
      plan.setMaxWeightForPosition(pos, 5000f);
    }

    return plan;
  }
}


