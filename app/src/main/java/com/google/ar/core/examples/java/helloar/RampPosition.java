package com.google.ar.core.examples.java.helloar;

/**
 * Logical ramp position like "2R" or "4L", derived from ramp-local coordinates.
 *
 * X axis: left/right, Z axis: front/back.
 */
public class RampPosition {

  public enum Side {
    L, R
  }

  private final int row;     // 1..N
  private final Side side;   // L or R

  public RampPosition(int row, Side side) {
    this.row = row;
    this.side = side;
  }

  public int getRow() {
    return row;
  }

  public Side getSide() {
    return side;
  }

  public String asCode() {
    return row + side.name();
  }

  /**
   * Convert normalized coordinates (0..1) into a discrete ramp position.
   *
   * normalizedX: 0 -> left edge, 1 -> right edge
   * normalizedZ: 0 -> front (row 1), 1 -> back (row N)
   *
   * Callers are expected to validate that values are within [0,1]. This method
   * assumes the object is on the ramp and does not perform out-of-range checks.
   */
  public static RampPosition fromNormalized(float normalizedX, float normalizedZ, int totalRows) {
    if (totalRows <= 0) {
      throw new IllegalArgumentException("totalRows must be > 0");
    }

    float nx = normalizedX;
    float nz = normalizedZ;

    Side side = (nx < 0.5f) ? Side.L : Side.R;

    int row = (int) Math.floor(nz * totalRows) + 1;
    if (row < 1) row = 1;
    if (row > totalRows) row = totalRows;

    return new RampPosition(row, side);
  }

  @Override
  public String toString() {
    return asCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    RampPosition other = (RampPosition) obj;
    return row == other.row && side == other.side;
  }

  @Override
  public int hashCode() {
    int result = row;
    result = 31 * result + (side == null ? 0 : side.hashCode());
    return result;
  }
}


