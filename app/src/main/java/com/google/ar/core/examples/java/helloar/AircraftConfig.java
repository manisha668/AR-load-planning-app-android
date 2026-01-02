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
    BOEING_737("Boeing 737"),
    AIRCRAFT_40("Aircraft 40");

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
      case AIRCRAFT_40:
        return createAircraft40();
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

    // Width and length are the modeled ramp dimensions in meters.
    // totalRows is the number of discrete rows (each row contains L and R slots).
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

    // Values chosen for demo purposes — replace with real aircraft specs as needed.
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

  private static AircraftConfig createAircraft40() {
    Map<String, Float> limits = new HashMap<>();
    // 20 rows, 2 sides = 40 positions
    // Row weights decrease slightly toward the rear for demo purposes.
    limits.put("1L", 6000f);
    limits.put("1R", 6000f);
    limits.put("2L", 5800f);
    limits.put("2R", 5800f);
    limits.put("3L", 5600f);
    limits.put("3R", 5600f);
    limits.put("4L", 5400f);
    limits.put("4R", 5400f);
    limits.put("5L", 5200f);
    limits.put("5R", 5200f);
    limits.put("6L", 5000f);
    limits.put("6R", 5000f);
    limits.put("7L", 4800f);
    limits.put("7R", 4800f);
    limits.put("8L", 4600f);
    limits.put("8R", 4600f);
    limits.put("9L", 4400f);
    limits.put("9R", 4400f);
    limits.put("10L", 4200f);
    limits.put("10R", 4200f);
    limits.put("11L", 4000f);
    limits.put("11R", 4000f);
    limits.put("12L", 3800f);
    limits.put("12R", 3800f);
    limits.put("13L", 3600f);
    limits.put("13R", 3600f);
    limits.put("14L", 3400f);
    limits.put("14R", 3400f);
    limits.put("15L", 3200f);
    limits.put("15R", 3200f);
    limits.put("16L", 3000f);
    limits.put("16R", 3000f);
    limits.put("17L", 2800f);
    limits.put("17R", 2800f);
    limits.put("18L", 2600f);
    limits.put("18R", 2600f);
    limits.put("19L", 2400f);
    limits.put("19R", 2400f);
    limits.put("20L", 2200f);
    limits.put("20R", 2200f);

    // Demo dimensions for a larger ramp
    return new AircraftConfig(
      AircraftType.AIRCRAFT_40,
      30.0f,  // width: 30 meters
      80.0f,  // length: 80 meters
      20,     // total rows
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
