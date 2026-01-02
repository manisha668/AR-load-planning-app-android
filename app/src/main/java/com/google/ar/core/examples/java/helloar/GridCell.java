package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Pose;

/**
 * Represents a single cell in the ramp grid (e.g., 1L, 1R, 2L, 2R).
 * Used for visualizing the grid before containers are placed.
 */
public class GridCell {
  private final RampPosition position;
  private Pose worldPose;
  private boolean isOccupied;
  private String containerId;

  public GridCell(RampPosition position, Pose worldPose) {
    this.position = position;
    this.worldPose = worldPose;
    this.isOccupied = false;
    this.containerId = null;
  }

  public RampPosition getPosition() {
    return position;
  }

  public Pose getWorldPose() {
    return worldPose;
  }

  public void setWorldPose(Pose pose) {
    this.worldPose = pose;
  }

  public boolean isOccupied() {
    return isOccupied;
  }

  public void setOccupied(boolean occupied) {
    this.isOccupied = occupied;
  }

  public String getContainerId() {
    return containerId;
  }

  public void setContainerId(String id) {
    this.containerId = id;
    this.isOccupied = (id != null);
  }

  public String getLabel() {
    return position.asCode();
  }
}
