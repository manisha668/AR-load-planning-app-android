package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Parser for loading plan text files.
 * 
 * Expected format (one entry per line):
 * ContainerID=AKE123, Weight=4800, Position=2R
 * ContainerID=AKE456, Weight=3000, Position=1L
 * 
 * Lines starting with # are treated as comments.
 * Empty lines are ignored.
 */
public class LoadingPlanParser {

  private static final String TAG = "LoadingPlanParser";

  /**
   * Parse a loading plan from an input stream.
   * 
   * @param inputStream The input stream containing the plan data
   * @return LoadingPlan object populated with entries
   * @throws IOException if reading fails
   */
  public static LoadingPlan parse(InputStream inputStream) throws IOException {
    LoadingPlan plan = new LoadingPlan();
    
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    String line;
    int lineNumber = 0;
    
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      line = line.trim();
      
      // Skip empty lines and comments
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      
      try {
        LoadingPlan.PlanEntry entry = parseLine(line);
        if (entry != null) {
          plan.putEntry(entry);
        }
      } catch (Exception e) {
        Log.w(TAG, "Failed to parse line " + lineNumber + ": " + line, e);
        // Continue parsing other lines
      }
    }
    
    reader.close();
    return plan;
  }

  /**
   * Parse a loading plan from a file path.
   * 
   * @param filePath Absolute path to the loading plan file
   * @return LoadingPlan object
   * @throws IOException if file cannot be read
   */
  public static LoadingPlan parseFile(String filePath) throws IOException {
    FileInputStream fis = new FileInputStream(filePath);
    try {
      return parse(fis);
    } finally {
      fis.close();
    }
  }

  /**
   * Parse a loading plan from assets (bundled with the app).
   * 
   * @param context Android context
   * @param assetFileName Name of the file in assets/ directory
   * @return LoadingPlan object
   * @throws IOException if asset cannot be read
   */
  public static LoadingPlan parseAsset(Context context, String assetFileName) throws IOException {
    InputStream is = context.getAssets().open(assetFileName);
    try {
      return parse(is);
    } finally {
      is.close();
    }
  }

  /**
   * Parse a single line of the loading plan.
   * 
   * Expected format: ContainerID=X, Weight=Y, Position=Z
   * 
   * @param line The line to parse
   * @return PlanEntry or null if parsing fails
   */
  private static LoadingPlan.PlanEntry parseLine(String line) {
    // Split by comma
    String[] parts = line.split(",");
    if (parts.length != 3) {
      Log.w(TAG, "Invalid line format (expected 3 comma-separated parts): " + line);
      return null;
    }
    
    String containerId = null;
    float weight = 0f;
    String position = null;
    
    for (String part : parts) {
      part = part.trim();
      
      if (part.startsWith("ContainerID=")) {
        containerId = part.substring("ContainerID=".length()).trim();
      } else if (part.startsWith("Weight=")) {
        String weightStr = part.substring("Weight=".length()).trim();
        try {
          weight = Float.parseFloat(weightStr);
        } catch (NumberFormatException e) {
          Log.w(TAG, "Invalid weight value: " + weightStr);
          return null;
        }
      } else if (part.startsWith("Position=")) {
        position = part.substring("Position=".length()).trim();
      }
    }
    
    if (containerId == null || position == null) {
      Log.w(TAG, "Missing required fields in line: " + line);
      return null;
    }
    
    return new LoadingPlan.PlanEntry(containerId, weight, position);
  }

  /**
   * Create a sample loading plan as a string (for testing).
   * 
   * @return Sample loading plan content
   */
  public static String getSamplePlanContent() {
    return "# Sample Loading Plan\n" +
           "# Format: ContainerID=X, Weight=Y, Position=Z\n" +
           "\n" +
           "ContainerID=AKE123, Weight=4800, Position=2R\n" +
           "ContainerID=AKE456, Weight=3000, Position=1L\n" +
           "ContainerID=AKE789, Weight=3500, Position=3L\n" +
           "ContainerID=PMC001, Weight=4200, Position=2L\n";
  }
}
