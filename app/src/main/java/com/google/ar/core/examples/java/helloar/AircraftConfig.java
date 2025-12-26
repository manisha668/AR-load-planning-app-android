package com.google.ar.core.examples.java.helloar;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for different aircraft types.
 * Each aircraft has:
 *  - Ramp dimensions (width and length in meters)
 *  - Total number of ramp rows
 *  - Weight limits per position (kg)
 */
public class AircraftConfig {

  public enum AircraftType {
    AIRCRAFT_A("Aircraft A"),
    AIRCRAFT_B("Aircraft B"),
    BOEING_737("Boeing 737");

    private final String displayName;

    AircraftType(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  private final AircraftType type;
  private final float rampWidthMeters;
  private final float rampLengthMeters;
  private final int totalRows;
  private final Map<String, Float> weightLimitsPerPosition;

  public AircraftConfig(
      AircraftType type,
      float rampWidthMeters,
      float rampLengthMeters,
      int totalRows,
      Map<String, Float> weightLimitsPerPosition) {
    this.type = type;
    this.rampWidthMeters = rampWidthMeters;
    this.rampLengthMeters = rampLengthMeters;
    this.totalRows = totalRows;
    this.weightLimitsPerPosition = weightLimitsPerPosition;
  }

  public AircraftType getType() {
    return type;
  }

  public float getRampWidthMeters() {
    return rampWidthMeters;
  }

  public float getRampLengthMeters() {
    return rampLengthMeters;
  }

  public int getTotalRows() {
    return totalRows;
  }

  public float getWeightLimitForPosition(String positionCode) {
    Float limit = weightLimitsPerPosition.get(positionCode);
    return limit != null ? limit : Float.MAX_VALUE;
  }

  /**
   * Factory method to create predefined aircraft configurations.
   */
  public static AircraftConfig getConfig(AircraftType type) {
    switch (type) {
      case AIRCRAFT_A:
        return createAircraftA();
      case AIRCRAFT_B:
        return createAircraftB();
      case BOEING_737:
        return createBoeing737();
      default:
        return createAircraftA(); // fallback
    }
  }

  private static AircraftConfig createAircraftA() {
    Map<String, Float> limits = new HashMap<>();
    // 4 rows, 2 sides = 8 positions
    limits.put("1L", 1000f);
    limits.put("1R", 1000f);
    limits.put("2L", 1000f);
    limits.put("2R", 1000f);
    limits.put("3L", 1000f);
    limits.put("3R", 1000f);
    limits.put("4L", 1000f);
    limits.put("4R", 1000f);

    return new AircraftConfig(
        AircraftType.AIRCRAFT_A,
        8.0f,   // width: 8 meters
        20.0f,  // length: 20 meters
        4,      // total rows
        limits
    );
  }

  private static AircraftConfig createAircraftB() {
    Map<String, Float> limits = new HashMap<>();
    // 5 rows, 2 sides = 10 positions
    limits.put("1L", 3500f);
    limits.put("1R", 3500f);
    limits.put("2L", 5500f);
    limits.put("2R", 5500f);
    limits.put("3L", 5000f);
    limits.put("3R", 5000f);
    limits.put("4L", 4000f);
    limits.put("4R", 4000f);
    limits.put("5L", 3000f);
    limits.put("5R", 3000f);

    return new AircraftConfig(
        AircraftType.AIRCRAFT_B,
        10.0f,  // width: 10 meters
        25.0f,  // length: 25 meters
        5,      // total rows
        limits
    );
  }

  private static AircraftConfig createBoeing737() {
    Map<String, Float> limits = new HashMap<>();
    // 3 rows, 2 sides = 6 positions (smaller aircraft)
    limits.put("1L", 2500f);
    limits.put("1R", 2500f);
    limits.put("2L", 4000f);
    limits.put("2R", 4000f);
    limits.put("3L", 3000f);
    limits.put("3R", 3000f);

    return new AircraftConfig(
        AircraftType.BOEING_737,
        6.0f,   // width: 6 meters
        15.0f,  // length: 15 meters
        3,      // total rows
        limits
    );
  }

  /**
   * Apply weight limits from this config to a LoadingPlan.
   */
  public void applyWeightLimitsTo(LoadingPlan plan) {
    for (Map.Entry<String, Float> entry : weightLimitsPerPosition.entrySet()) {
      plan.setMaxWeightForPosition(entry.getKey(), entry.getValue());
    }
  }
}
