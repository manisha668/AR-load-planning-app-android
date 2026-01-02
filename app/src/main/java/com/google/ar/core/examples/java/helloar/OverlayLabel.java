package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Pose;
import android.view.View;
import android.widget.TextView;

/**
 * Represents a 2D overlay label positioned in world space.
 * The label is rendered as an Android View positioned dynamically based on world-to-screen projection.
 */
public class OverlayLabel {
  private Pose worldPose;
  private String text;
  private int backgroundColor; // ARGB color
  private int textColor; // ARGB color
  private TextView view;
  private boolean isVisible;
  private boolean isPersistent = false;

  public OverlayLabel(Pose worldPose, String text, int backgroundColor, int textColor) {
    this.worldPose = worldPose;
    this.text = text;
    this.backgroundColor = backgroundColor;
    this.textColor = textColor;
    this.isVisible = true;
  }

  public Pose getWorldPose() {
    return worldPose;
  }

  public void setWorldPose(Pose pose) {
    this.worldPose = pose;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
    if (view != null) {
      view.setText(text);
    }
  }

  public int getBackgroundColor() {
    return backgroundColor;
  }

  public void setBackgroundColor(int color) {
    this.backgroundColor = color;
    if (view != null) {
      view.setBackgroundColor(color);
    }
  }

  public int getTextColor() {
    return textColor;
  }

  public void setTextColor(int color) {
    this.textColor = color;
    if (view != null) {
      view.setTextColor(color);
    }
  }

  public TextView getView() {
    return view;
  }

  public void setView(TextView view) {
    this.view = view;
    if (view != null) {
      view.setText(text);
      view.setBackgroundColor(backgroundColor);
      view.setTextColor(textColor);
      view.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }
  }

  public boolean isVisible() {
    return isVisible;
  }

  public void setVisible(boolean visible) {
    this.isVisible = visible;
    if (view != null) {
      view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
  }

  public boolean isPersistent() {
    return isPersistent;
  }

  public void setPersistent(boolean persistent) {
    this.isPersistent = persistent;
  }
  public enum Type {
    GRID,
    DYNAMIC
  }

  private Type type = Type.DYNAMIC;

  public void setType(Type type) {
    this.type = type;
  }

  public Type getType() {
    return type;
  }

}


