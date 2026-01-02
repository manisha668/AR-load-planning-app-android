package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;

/**
 * Simple screen-based coordinate converter.
 * Grid layout:
 *   1L | 1R
 *   2L | 2R
 *   3L | 3R
 *   ...
 * 
 * First tap becomes top-left (1L). Grid extends right and down from there.
 */
public class RampCoordinateConverter {

  private final Anchor rampAnchor;
  private final float cellWidth;  // Width per cell (half of total width)
  private final float cellHeight; // Height per row
  private final int totalRows;
  private final float rampWidthMeters;
  private final float rampLengthMeters;

  /**
   * @param rampAnchor Top-left corner anchor (1L position)
   * @param gridWidthMeters Total grid width (for both L and R columns)
   * @param gridHeightMeters Total grid height (for all rows)
   * @param totalRows Total number of rows
   */
  public RampCoordinateConverter(
      Anchor rampAnchor,
      float gridWidthMeters,
      float gridHeightMeters,
      int totalRows) {
    if (rampAnchor == null) {
      throw new IllegalArgumentException("rampAnchor cannot be null");
    }
    this.rampAnchor = rampAnchor;
    this.rampWidthMeters = gridWidthMeters;
    this.rampLengthMeters = gridHeightMeters;
    this.cellWidth = gridWidthMeters / 2.0f;  // Divide by 2 for L and R columns
    this.cellHeight = gridHeightMeters / (float) totalRows;
    this.totalRows = totalRows;
  }

  /**
   * Convert a world pose to ramp-local coordinates using full pose math.
   *
   * localPose = inverse(rampAnchorPose) × worldPose
   *
   * @param worldPose The pose in ARCore world coordinate system
   * @return float[3] containing [localX, localY, localZ] relative to ramp anchor
   */
  public float[] toRampLocal(Pose worldPose) {
    Pose rampPose = rampAnchor.getPose();
    Pose localPose = rampPose.inverse().compose(worldPose);
    float localX = localPose.tx();
    float localY = localPose.ty();
    float localZ = localPose.tz();
    return new float[]{localX, localY, localZ};
  }

  /**
   * Determine which grid cell a tap falls into.
   * Returns the row and column based on distance from origin.
   */
  public RampPosition worldPoseToRampPosition(Pose worldPose) {
    float[] localCoords = toRampLocal(worldPose);
    float localX = localCoords[0];
    float localZ = localCoords[2];

    // Determine column (L or R)
    RampPosition.Side side;
    if (localX < cellWidth) {
      side = RampPosition.Side.L;
    } else {
      side = RampPosition.Side.R;
    }

    // Determine row (1, 2, 3, ...)
    // ARCore forward direction is -Z, so negate localZ when computing row index.
    int row = (int)(-localZ / cellHeight) + 1;
    if (row < 1) row = 1;
    if (row > totalRows) row = totalRows;

    return new RampPosition(row, side);
  }

  /**
   * Normalize local coordinates to [0,1] range.
   */
  public float[] normalize(float[] localCoords) {
    float normalizedX = localCoords[0] / rampWidthMeters;
    // ARCore forward is negative Z; normalize using -Z so 0..1 maps forward correctly.
    float normalizedZ = -localCoords[2] / rampLengthMeters;
    return new float[]{normalizedX, normalizedZ};
  }

  /**
   * Convert a ramp position back to approximate world coordinates (for visualization).
   * Useful for showing suggested placements in AR.
   *
   * @param position The logical ramp position (e.g., "2R")
   * @return Pose in world coordinates representing the center of that ramp slot
   */
  public Pose rampPositionToWorldPose(RampPosition position) {
    int row = position.getRow();
    boolean isLeft = (position.getSide() == RampPosition.Side.L);

    // Calculate local coordinates for this position
    // X: left side = center of left column, right side = center of right column
    float localX = isLeft ? (cellWidth / 2.0f) : (cellWidth + cellWidth / 2.0f);
    
    // Z: center of the row — use negative Z for forward direction in ARCore
    float localZ = -(((float) row - 0.5f) * cellHeight);
    float localY = 0.0f; // On the ramp plane

    // Convert to world coordinates using anchor pose
    Pose rampPose = rampAnchor.getPose();
    Pose localPose = new Pose(new float[]{localX, localY, localZ}, new float[]{0, 0, 0, 1});
    Pose worldPose = rampPose.compose(localPose);

    return worldPose;
  }
}
