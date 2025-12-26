package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;

/**
 * Converts ARCore world coordinates into ramp-local normalized coordinates.
 *
 * Workflow:
 * 1. World pose (from ARCore) → Ramp-local coordinates (relative to ramp anchor)
 * 2. Ramp-local coordinates → Normalized coordinates (0 to 1 range)
 * 3. Normalized coordinates → Logical ramp position (e.g., "2R")
 *
 * The ramp anchor is the reference point (typically center of detected ramp plane).
 */
public class RampCoordinateConverter {

  private final Anchor rampAnchor;
  private final float rampWidthMeters;
  private final float rampLengthMeters;
  private final int totalRows;

  /**
   * @param rampAnchor The anchor representing the ramp reference point (usually ramp center)
   * @param rampWidthMeters Physical width of the ramp (X axis)
   * @param rampLengthMeters Physical length of the ramp (Z axis)
   * @param totalRows Total number of rows on this ramp
   */
  public RampCoordinateConverter(
      Anchor rampAnchor,
      float rampWidthMeters,
      float rampLengthMeters,
      int totalRows) {
    if (rampAnchor == null) {
      throw new IllegalArgumentException("rampAnchor cannot be null");
    }
    this.rampAnchor = rampAnchor;
    this.rampWidthMeters = rampWidthMeters;
    this.rampLengthMeters = rampLengthMeters;
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
   * Normalize ramp-local coordinates to unbounded normalized coordinates.
   *
   * First tap becomes top-left corner (1L position).
   * - normalizedX: 0 = at first tap, increases to the right
   * - normalizedZ: 0 = at first tap, increases downward (away from camera)
   *
   * Values outside [0,1] indicate the object is physically outside the ramp.
   *
   * @param localCoords [localX, localY, localZ] from toRampLocal()
   * @return float[2] containing [normalizedX, normalizedZ]
   */
  public float[] normalize(float[] localCoords) {
    float localX = localCoords[0];
    float localZ = localCoords[2];

    // First tap is at origin (0, 0, 0) in local coordinates
    // Positive X = right, Negative X = left
    // Positive Z = away from camera (down), Negative Z = toward camera (up)
    
    // X: 0 at origin (left edge), 1 at right edge
    float normalizedX = localX / rampWidthMeters;

    // Z: 0 at origin (top/row 1), 1 at bottom (row N)
    float normalizedZ = localZ / rampLengthMeters;

    return new float[]{normalizedX, normalizedZ};
  }

  /**
   * Full conversion: world pose → ramp position.
   *
   * If the normalized coordinates fall outside [0,1] on either axis, this
   * returns {@code null} to indicate the object is outside the ramp.
   *
   * @param worldPose The container's pose in world coordinates
   * @return RampPosition representing the logical ramp slot, or null if outside ramp
   */
  public RampPosition worldPoseToRampPosition(Pose worldPose) {
    float[] localCoords = toRampLocal(worldPose);
    float[] normalized = normalize(localCoords);
    float nx = normalized[0];
    float nz = normalized[1];

    if (nx < 0f || nx > 1f || nz < 0f || nz > 1f) {
      // Outside ramp bounds in normalized space; ignore for logic.
      return null;
    }

    return RampPosition.fromNormalized(nx, nz, totalRows);
  }

  /**
   * Convert a ramp position back to approximate world coordinates (for visualization).
   * Useful for showing suggested placements in AR.
   *
   * @param position The logical ramp position (e.g., "2R")
   * @return Pose in world coordinates representing the center of that ramp slot
   */
  public Pose rampPositionToWorldPose(RampPosition position) {
    // Reverse the normalization
    int row = position.getRow();
    boolean isLeft = (position.getSide() == RampPosition.Side.L);

    // Calculate normalized coordinates for this position
    // X: left side = 0.25 (1/4 width), right side = 0.75 (3/4 width)
    float normalizedX = isLeft ? 0.25f : 0.75f;

    // Z: distribute rows evenly, centered in each row
    float normalizedZ = ((float) row - 0.5f) / (float) totalRows;

    // Convert back to local coordinates (origin at top-left)
    float localX = normalizedX * rampWidthMeters;
    float localZ = normalizedZ * rampLengthMeters;
    float localY = 0.0f; // On the ramp plane

    // Convert to world coordinates using anchor pose
    Pose rampPose = rampAnchor.getPose();
    Pose localPose = new Pose(new float[]{localX, localY, localZ}, new float[]{0, 0, 0, 1});
    Pose worldPose = rampPose.compose(localPose);

    return worldPose;
  }
}
