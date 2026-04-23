package com.google.ar.core.examples.java.helloar;

import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts (containerId, expectedPosition) instructions from free-form load-sheet text.
 *
 * Supports loose inputs such as:
 * - "Place ULD-12 at 12R"
 * - "ULD-12 12R"
 * - "ContainerID=AKE123, Position=2R"
 */
public final class LoadSheetInstructionParser {
  private LoadSheetInstructionParser() {}

  private static final String TAG = "LoadSheetParser";

  // Position: 1–3 digits + L/R. Allow optional space/dash between digit and L/R (e.g. "7 R", "7R").
  private static final Pattern POSITION_PATTERN =
      Pattern.compile("\\b(\\d{1,3})\\s*[-]?\\s*([LR])\\b", Pattern.CASE_INSENSITIVE);

  // Handles fused strings like "7RULD1" where the position is immediately followed by "ULD".
  private static final Pattern POSITION_BEFORE_ULD =
      Pattern.compile("(\\d{1,3})\\s*[-]?\\s*([LR])(?=\\s*ULD)", Pattern.CASE_INSENSITIVE);

  // Token that looks like a container id: letters+digits (optionally with '-') with length >= 3.
  private static final Pattern CONTAINER_TOKEN_PATTERN =
      Pattern.compile("\\b([A-Z0-9][A-Z0-9-]{2,})\\b", Pattern.CASE_INSENSITIVE);

  // "ULD 1", "ULD-2", "ULD2" etc. so each line gets a distinct container (ULD1, ULD2).
  private static final Pattern ULD_WITH_NUMBER =
      Pattern.compile("\\bULD\\s*[-]?\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);

  // Fused "position+ULD" like "7RULD1" used for container extraction in text parsing.
  private static final Pattern POSITION_ULD_FUSED =
      Pattern.compile("(\\d{1,3})\\s*[-]?\\s*([LR])\\s*ULD\\s*[-]?\\s*(\\d{1,4})",
          Pattern.CASE_INSENSITIVE);

  // "AKE 017373", "AKE B1", "AKE-017373", "AKEB1" etc. (string/OCR line).
  private static final Pattern AKE_WITH_SUFFIX =
      Pattern.compile("\\bAKE\\s*[-]?\\s*([A-Z0-9]{1,8})\\b", Pattern.CASE_INSENSITIVE);

  // AKE chart style: "AKE B1" / "AKE A1" etc (often split into two OCR elements).
  private static final Pattern AKE_PREFIX = Pattern.compile("^AKE$", Pattern.CASE_INSENSITIVE);
  // Also support chart-style "9S*5" / "9S5" / "9S*" (often split by OCR).
  // Examples we want to normalize into: AKE9S5, AKE9S4, AKE9S6
  private static final Pattern AKE_SUFFIX =
      Pattern.compile("^([A-Z]\\d{1,2}|\\d{1,2}[A-Z]\\*?\\d{0,2}|\\d{1,2}[A-Z]\\*?)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern AKE_CHART_PART1 =
      Pattern.compile("^\\d{1,2}[A-Z]\\*?$", Pattern.CASE_INSENSITIVE); // e.g. "9S*" or "9S"
  private static final Pattern AKE_CHART_PART2 =
      Pattern.compile("^\\d{1,2}$", Pattern.CASE_INSENSITIVE); // e.g. "5"

  // ULD chart style: "ULD 12" etc (often split into two OCR elements).
  private static final Pattern ULD_PREFIX = Pattern.compile("^ULD$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ULD_SUFFIX = Pattern.compile("^\\d{1,4}$", Pattern.CASE_INSENSITIVE);

  // Avoid selecting "1PCS/2PCS/5PCS" as container IDs.
  private static final Pattern PCS_TOKEN = Pattern.compile("^\\d{1,3}PCS$", Pattern.CASE_INSENSITIVE);

  public static final class Instruction {
    public final String containerId;
    public final String expectedPosition;

    public Instruction(String containerId, String expectedPosition) {
      this.containerId = containerId;
      this.expectedPosition = expectedPosition;
    }
  }

  /**
   * Preferred parsing path for image/PDF OCR: uses ML Kit element bounding boxes to match
   * container IDs (e.g. AKE B1) to nearby position codes (e.g. 12L).
   */
  public static List<Instruction> parse(@Nullable Text mlkitText) {
    if (mlkitText == null) return new ArrayList<>();

    // Build container candidates from OCR lines.
    List<Token> positionTokens = new ArrayList<>();
    List<Token> containerTokens = new ArrayList<>();
    // Single small numeric tokens (e.g. the trailing digit in "9S*5") often get split onto their own line.
    List<Token> smallNumberTokens = new ArrayList<>();

    for (Text.TextBlock block : mlkitText.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        List<Text.Element> els = line.getElements();
        if (els == null || els.isEmpty()) continue;

        // Collect position tokens from elements (e.g. "44L", "5L") and from full line (e.g. "7R").
        Set<String> positionsFromElements = new HashSet<>();
        for (Text.Element el : els) {
          String pos = normalizePosition(el.getText());
          if (pos != null) {
            Rect bb = el.getBoundingBox();
            if (bb != null) {
              positionTokens.add(new Token(pos, new RectF(bb)));
              positionsFromElements.add(pos);
            }
          }
        }
        // Also find all positions in the line text so we get "7R" when OCR split "7"/"R" or merged lines.
        List<PositionMatch> allPositions = findAllPositionsInLine(line.getText());
        Rect lineBb = line.getBoundingBox();
        if (lineBb != null && !allPositions.isEmpty()) {
          RectF lineRect = new RectF(lineBb);
          String lineText = line.getText();
          int lineLen = (lineText == null ? 0 : lineText.length());
          String normalized = lineText == null ? "" : lineText.trim().replaceAll("(?i)(\\d)\\s*[-]?\\s*([LR])\\b", "$1$2");
          if (lineLen < 1) lineLen = 1;
          int normLen = Math.max(1, normalized.length());
          for (PositionMatch pm : allPositions) {
            if (positionsFromElements.contains(pm.position)) continue;
            RectF sub = subRectByCharOffset(lineRect, normLen, pm.startIndex, pm.length);
            positionTokens.add(new Token(pm.position, sub));
          }
        }

        // Build container tokens from sequences in the line.
        for (int i = 0; i < els.size(); i++) {
          String a = normalizeWord(els.get(i).getText());
          Rect ra = els.get(i).getBoundingBox();
          if (a == null || ra == null) continue;

          // Capture single-digit tokens (e.g. "5", "4", "6") for AKE chart-style suffix reconstruction.
          // Important: only single digits here; otherwise tokens like "95" would be incorrectly appended.
          if (a.matches("^\\d$")) {
            smallNumberTokens.add(new Token(a.toUpperCase(Locale.US), new RectF(ra)));
          }
          // Pattern: "AKE" + "B1" => "AKEB1"
          if (AKE_PREFIX.matcher(a).matches() && i + 1 < els.size()) {
            String b = normalizeWord(els.get(i + 1).getText());
            Rect rb = els.get(i + 1).getBoundingBox();
            if (b != null && rb != null) {
              // Case 0: "AKE" + "95" (numeric-only suffix like AKE 95)
              if (b.matches("^\\d{1,3}$")) {
                String id = ("AKE" + b).toUpperCase(Locale.US); // e.g. AKE95
                containerTokens.add(new Token(id, union(new RectF(ra), new RectF(rb))));
                continue;
              }

              // Case 1: OCR splits "AKE 9S*5" into 3 elements: "AKE" + "9S*" + "5"
              if (AKE_CHART_PART1.matcher(b).matches() && i + 2 < els.size()) {
                String c = normalizeWord(els.get(i + 2).getText());
                Rect rc = els.get(i + 2).getBoundingBox();
                if (c != null && rc != null && AKE_CHART_PART2.matcher(c).matches()) {
                  String suffix = normalizeAkeSuffix(b + c);
                  if (suffix != null) {
                    String id = ("AKE" + suffix).toUpperCase(Locale.US);
                    containerTokens.add(new Token(id, union(union(new RectF(ra), new RectF(rb)), new RectF(rc))));
                    continue;
                  }
                }
              }
              // Case 2: "AKE" + "9S5" (or "B1") in a single OCR element.
              if (AKE_SUFFIX.matcher(b).matches()) {
                String suffix = normalizeAkeSuffix(b);
                if (suffix != null) {
                  String id = ("AKE" + suffix).toUpperCase(Locale.US);
                  containerTokens.add(new Token(id, union(new RectF(ra), new RectF(rb))));
                  continue;
                }
              }
            }
          }

          // Pattern: "ULD" + "12" => "ULD12" (normalize without hyphen so we match ULD 1 / ULD-1 / ULD1)
          if (ULD_PREFIX.matcher(a).matches() && i + 1 < els.size()) {
            String b = normalizeWord(els.get(i + 1).getText());
            Rect rb = els.get(i + 1).getBoundingBox();
            if (b != null && rb != null && ULD_SUFFIX.matcher(b).matches()) {
              String id = ("ULD" + b).toUpperCase(Locale.US);
              containerTokens.add(new Token(id, union(new RectF(ra), new RectF(rb))));
              continue;
            }
          }

          // Single token container id like "AKEB1" or "ULD-12" or "AKE123".
          String single = normalizeContainerToken(a);
          if (single != null) {
            containerTokens.add(new Token(single, new RectF(ra)));
          }
        }
      }
    }

    // Post-process AKE chart-style tokens:
    // 1) If OCR produced "AKE9S" but split the trailing digit ("5"/"4"/"6") elsewhere, attach the nearest digit.
    // 2) If OCR produced "AKE95" on a page that otherwise contains AKE9S*, treat "95" as a likely "9S" confusion,
    //    then attach the nearest digit token if available.
    boolean pageHasAke9SFamily = false;
    for (Token c : containerTokens) {
      if (c == null || c.text == null) continue;
      if (c.text.startsWith("AKE9S")) {
        pageHasAke9SFamily = true;
        break;
      }
    }
    if (!smallNumberTokens.isEmpty()) {
      for (int idx = 0; idx < containerTokens.size(); idx++) {
        Token c = containerTokens.get(idx);
        if (c == null || c.rect == null || c.text == null) continue;

        String t = c.text.toUpperCase(Locale.US);

        // Fix OCR confusion "AKE95" -> "AKE9S" when page also has AKE9S family.
        if (pageHasAke9SFamily && t.equals("AKE95")) {
          t = "AKE9S";
        }

        // If it's exactly "AKE9S" (missing final digit), attach nearest small digit in same column band.
        if (t.equals("AKE9S")) {
          Token nearestDigit = findNearestSmallDigit(c, smallNumberTokens);
          if (nearestDigit != null && nearestDigit.text != null) {
            String combined = "AKE9S" + nearestDigit.text;
            containerTokens.set(idx, new Token(combined, union(c.rect, nearestDigit.rect)));
          } else {
            // Keep as-is if we can't find a digit.
            if (!t.equals(c.text)) {
              containerTokens.set(idx, new Token(t, c.rect));
            }
          }
        } else if (!t.equals(c.text)) {
          // Apply corrected token text if we changed it.
          containerTokens.set(idx, new Token(t, c.rect));
        }
      }
    } else if (pageHasAke9SFamily) {
      // Even without digit tokens, still apply AKE95->AKE9S correction when appropriate.
      for (int idx = 0; idx < containerTokens.size(); idx++) {
        Token c = containerTokens.get(idx);
        if (c == null || c.text == null) continue;
        if ("AKE95".equalsIgnoreCase(c.text)) {
          containerTokens.set(idx, new Token("AKE9S", c.rect));
        }
      }
    }

    // Match each container to nearest position in screen space.
    Map<String, Instruction> byContainer =
        matchByColumns(containerTokens, positionTokens);

    // Fallback: if column-based matching failed (e.g., non-grid layout),
    // use simple nearest-neighbour matching. Allow multiple instructions per containerId.
    if (byContainer.isEmpty() && !containerTokens.isEmpty()) {
      byContainer = new LinkedHashMap<>();
      for (Token container : containerTokens) {
        if (container == null || container.rect == null) continue;
        Token nearestPos = findNearest(container, positionTokens);
        if (nearestPos == null) {
          Log.d(TAG, "OCR match - container " + container.text + " had no nearby position");
          continue;
        }
        Log.d(
            TAG,
            "OCR match - container "
                + container.text
                + " matched to position "
                + nearestPos.text);
        String key = container.text + "@" + nearestPos.text + "@" + byContainer.size();
        byContainer.put(key, new Instruction(container.text, nearestPos.text));
      }
    }

    List<Instruction> result = new ArrayList<>(byContainer.values());

    // If OCR-based matching found nothing, or missed some, merge with text-only parsing
    // based on the concatenated OCR text.
    String rawText = mlkitText.getText();
    Log.d(TAG, "OCR raw text: " + rawText);
    // If OCR already produced any AKE instructions, do not merge AKE instructions from text-only parsing.
    // The text-only parser lacks geometry and can incorrectly associate AKE tokens to nearby positions.
    boolean ocrHasAnyAkeInstruction = false;
    for (Instruction i : result) {
      if (i != null
          && i.containerId != null
          && i.containerId.toUpperCase(Locale.US).startsWith("AKE")) {
        ocrHasAnyAkeInstruction = true;
        break;
      }
    }

    if (rawText != null && !rawText.trim().isEmpty()) {
      List<Instruction> textParsed = parse(rawText);
      for (Instruction ins : textParsed) {
        if (ins == null || ins.containerId == null) continue;
        // Ignore low-quality container ids that have no digit at all (e.g., "AKEB" near 13R),
        // since real ULD-style ids like AKEC1/AKEB1/AKEA1 always contain at least one number.
        if (!containsDigit(ins.containerId)) continue;
        if (ocrHasAnyAkeInstruction
            && ins.containerId.toUpperCase(Locale.US).startsWith("AKE")) {
          continue;
        }
        boolean alreadyPresent = false;
        for (Instruction existing : result) {
          if (existing != null
              && existing.containerId != null
              && existing.containerId.equals(ins.containerId)) {
            alreadyPresent = true;
            break;
          }
        }
        if (!alreadyPresent) {
          result.add(ins);
        }
      }
    }

    logTokens(positionTokens, containerTokens);
    logInstructions("OCR/merged parse", result);
    return result;
  }

  /**
   * Finds the nearest single-digit token to an AKE chart token.
   * We require approximate column alignment (small dx) and prefer the digit to be below the AKE token,
   * because the sheet often prints "AKE 9S" then the digit on the next line.
   */
  @Nullable
  private static Token findNearestSmallDigit(Token akeToken, List<Token> digits) {
    if (akeToken == null || akeToken.rect == null || digits == null || digits.isEmpty()) return null;

    float ax = akeToken.rect.centerX();
    float ay = akeToken.rect.centerY();

    Token best = null;
    double bestScore = Double.MAX_VALUE;

    for (Token d : digits) {
      if (d == null || d.rect == null || d.text == null) continue;
      // Only a single digit.
      if (!d.text.matches("^\\d$")) continue;
      float dx = Math.abs(d.rect.centerX() - ax);
      float dy = d.rect.centerY() - ay;
      // Must be roughly in the same column.
      if (dx > 90f) continue;
      // Must be reasonably close vertically (digit is usually on the next line).
      if (dy < -60f || dy > 260f) continue;
      // Prefer digits below (dy >= 0), but allow slightly above if needed.
      double penalty = (dy >= -40f ? 0.0 : 5000.0);
      // Score: strong weight on dx + distance; small preference for below.
      double score = penalty + dx * dx + (dy * dy);
      if (score < bestScore) {
        bestScore = score;
        best = d;
      }
    }
    return best;
  }

  public static List<Instruction> parse(String rawText) {
    if (rawText == null) return new ArrayList<>();
    String text = rawText.replace('\r', '\n');

    // Keep insertion order; de-dupe by containerId.
    Map<String, Instruction> byContainer = new LinkedHashMap<>();

    String[] lines = text.split("\n");
    for (String line : lines) {
      if (line == null) continue;
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.startsWith("#")) continue;

      // One line can have multiple instructions (e.g. "5L ULD 1 7R ULD 2"). Find all positions.
      List<PositionMatch> allPositions = findAllPositionsInLine(trimmed);
      if (allPositions.isEmpty()) continue;

      // Normalize for index mapping: "7 R" -> "7R" so indices match trimmed.
      String normalized = trimmed.replaceAll("(?i)(\\d)\\s*[-]?\\s*([LR])\\b", "$1$2");

      for (int i = 0; i < allPositions.size(); i++) {
        PositionMatch pm = allPositions.get(i);
        int windowStart = pm.startIndex;
        int windowEnd = (i + 1 < allPositions.size())
            ? allPositions.get(i + 1).startIndex
            : normalized.length();
        String window = normalized.substring(windowStart, windowEnd).trim();
        if (window.isEmpty()) window = normalized.substring(windowStart, Math.min(normalized.length(), windowStart + 30));
        String container = findContainerId(window, pm.position);
        if (container == null) continue;
        byContainer.put(container, new Instruction(container, pm.position));
      }
    }

    // Fallback for OCR/PDF text where line breaks are unreliable.
    if (byContainer.isEmpty()) {
      addByProximityFallback(text, byContainer);
    }

    List<Instruction> result = new ArrayList<>(byContainer.values());
    logInstructions("Text parse", result);
    return result;
  }

  private static void logInstructions(String source, List<Instruction> instructions) {
    if (instructions == null || instructions.isEmpty()) {
      Log.d(TAG, source + " - no instructions parsed");
      return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append(source).append(" - parsed instructions: ");
    for (Instruction ins : instructions) {
      if (ins == null) continue;
      sb.append('[')
          .append(ins.containerId)
          .append(" -> ")
          .append(ins.expectedPosition)
          .append("] ");
    }
    Log.d(TAG, sb.toString());
  }

  private static void logTokens(List<Token> positionTokens, List<Token> containerTokens) {
    StringBuilder positions = new StringBuilder("OCR positions: ");
    for (Token t : positionTokens) {
      if (t == null || t.rect == null) continue;
      positions
          .append('[')
          .append(t.text)
          .append(" x=")
          .append(t.rect.centerX())
          .append(" y=")
          .append(t.rect.centerY())
          .append("] ");
    }
    Log.d(TAG, positions.toString());

    StringBuilder containers = new StringBuilder("OCR containers: ");
    for (Token t : containerTokens) {
      if (t == null || t.rect == null) continue;
      containers
          .append('[')
          .append(t.text)
          .append(" x=")
          .append(t.rect.centerX())
          .append(" y=")
          .append(t.rect.centerY())
          .append("] ");
    }
    Log.d(TAG, containers.toString());
  }

  private static final class Token {
    final String text;
    final RectF rect;

    Token(String text, RectF rect) {
      this.text = text;
      this.rect = rect;
    }
  }

  /** Groups tokens into vertical "columns" and matches containers to positions by vertical order. */
  private static Map<String, Instruction> matchByColumns(
      List<Token> containerTokens, List<Token> positionTokens) {
    Map<String, Instruction> out = new LinkedHashMap<>();
    if (containerTokens == null || containerTokens.isEmpty()
        || positionTokens == null || positionTokens.isEmpty()) {
      return out;
    }

    // Build columns from positions based on x coordinate proximity.
    class Column {
      float xCenter;
      final List<Token> positions = new ArrayList<>();
      final List<Token> containers = new ArrayList<>();
    }

    List<Column> columns = new ArrayList<>();
    final float COLUMN_X_THRESHOLD = 80f; // pixels; adjust if needed

    for (Token p : positionTokens) {
      if (p == null || p.rect == null) continue;
      float px = p.rect.centerX();
      Column best = null;
      float bestDx = Float.MAX_VALUE;
      for (Column col : columns) {
        float dx = Math.abs(px - col.xCenter);
        if (dx < bestDx) {
          bestDx = dx;
          best = col;
        }
      }
      if (best == null || bestDx > COLUMN_X_THRESHOLD) {
        Column col = new Column();
        col.xCenter = px;
        col.positions.add(p);
        columns.add(col);
      } else {
        best.positions.add(p);
        // Update center slightly toward new point.
        best.xCenter = (best.xCenter * (best.positions.size() - 1) + px) / best.positions.size();
      }
    }

    // Assign containers to nearest column.
    for (Token c : containerTokens) {
      if (c == null || c.rect == null) continue;
      float cx = c.rect.centerX();
      Column best = null;
      float bestDx = Float.MAX_VALUE;
      for (Column col : columns) {
        float dx = Math.abs(cx - col.xCenter);
        if (dx < bestDx) {
          bestDx = dx;
          best = col;
        }
      }
      if (best != null && bestDx <= COLUMN_X_THRESHOLD * 1.5f) {
        best.containers.add(c);
      }
    }

    // Within each column, sort by vertical position and, for each container, pick the *closest*
    // unused position. This avoids forcing stacked ULDs in the same physical slot (e.g. 44L)
    // to be arbitrarily paired with 44R when that bay is actually empty.
    for (Column col : columns) {
      if (col == null) continue;
      if (col.positions.isEmpty() || col.containers.isEmpty()) continue;

      col.positions.sort(Comparator.comparingDouble(t -> t.rect.centerY()));
      col.containers.sort(Comparator.comparingDouble(t -> t.rect.centerY()));

      Set<Token> usedPositions = new HashSet<>();
      for (Token c : col.containers) {
        if (c == null || c.rect == null || c.text == null) continue;

        Token bestPos = null;
        double bestDist = Double.MAX_VALUE;
        float cx = c.rect.centerX();
        float cy = c.rect.centerY();

        for (Token p : col.positions) {
          if (p == null || p.rect == null || p.text == null) continue;
          if (usedPositions.contains(p)) continue;
          double d = dist2(cx, cy, p.rect.centerX(), p.rect.centerY());
          if (d < bestDist) {
            bestDist = d;
            bestPos = p;
          }
        }

        if (bestPos != null) {
          usedPositions.add(bestPos);
          String key = c.text + "@" + bestPos.text + "@" + usedPositions.size();
          out.put(key, new Instruction(c.text, bestPos.text));
        }
      }
    }

    // Log matches for debugging.
    for (Map.Entry<String, Instruction> e : out.entrySet()) {
      Instruction ins = e.getValue();
      if (ins != null) {
        Log.d(
            TAG,
            "OCR match (columns) - container "
                + ins.containerId
                + " matched to position "
                + ins.expectedPosition);
      }
    }

    return out;
  }

  private static Token findNearest(Token container, List<Token> positions) {
    if (positions == null || positions.isEmpty()) return null;

    // Prefer positions that horizontally overlap the container (same column).
    List<Token> sameColumn = new ArrayList<>();
    for (Token p : positions) {
      if (p == null) continue;
      if (overlapRatioX(container.rect, p.rect) >= 0.25f) {
        sameColumn.add(p);
      }
    }
    List<Token> baseCandidates = sameColumn.isEmpty() ? positions : sameColumn;

    float cx = container.rect.centerX();
    float cy = container.rect.centerY();

    // Row-aware refinement:
    // On load charts, position labels (11L/11R/12L/...) are typically above or roughly aligned
    // vertically with their associated ULD ID. When multiple positions share a column, prefer
    // those that are not significantly below the container to avoid matching 11L->12L, etc.
    List<Token> rowPreferred = new ArrayList<>();
    for (Token p : baseCandidates) {
      if (p == null || p.rect == null) continue;
      float py = p.rect.centerY();
      // Allow a small margin below the container center but heavily bias to labels above/same row.
      if (py <= cy * 1.02f) {
        rowPreferred.add(p);
      }
    }

    List<Token> candidates = rowPreferred.isEmpty() ? baseCandidates : rowPreferred;

    return candidates.stream()
        .filter(Objects::nonNull)
        .min(Comparator.comparingDouble(p -> dist2(cx, cy, p.rect.centerX(), p.rect.centerY())))
        .orElse(null);
  }

  private static double dist2(float ax, float ay, float bx, float by) {
    double dx = (ax - bx);
    double dy = (ay - by);
    return dx * dx + dy * dy;
  }

  private static float overlapRatioX(RectF a, RectF b) {
    float left = Math.max(a.left, b.left);
    float right = Math.min(a.right, b.right);
    float overlap = Math.max(0f, right - left);
    float minWidth = Math.min(Math.max(1f, a.width()), Math.max(1f, b.width()));
    return overlap / minWidth;
  }

  private static RectF union(RectF a, RectF b) {
    return new RectF(
        Math.min(a.left, b.left),
        Math.min(a.top, b.top),
        Math.max(a.right, b.right),
        Math.max(a.bottom, b.bottom)
    );
  }

  private static String normalizePosition(String raw) {
    if (raw == null) return null;
    String t = raw.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    if (!POSITION_PATTERN.matcher(t).matches()) return null;
    return t;
  }

  private static String normalizeWord(String raw) {
    if (raw == null) return null;
    String t = raw.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]", "");
    return t.isEmpty() ? null : t;
  }

  private static String normalizeContainerToken(String token) {
    if (token == null) return null;

    String upper = token.toUpperCase(Locale.US).trim();

    // Reject position codes (44L, 12R, etc.)
    if (POSITION_PATTERN.matcher(upper).matches()) return null;

    // Reject weights, temperatures, misc
    if (upper.endsWith("KG")) return null;
    if (upper.endsWith("KGS")) return null;
    if (upper.endsWith("C")) return null;
    if (upper.contains("MAX")) return null;
    if (upper.contains("TEMP")) return null;
    if (upper.contains("VENT")) return null;
    // Reject layout noise
    if (upper.equals("N") || upper.equals("DOOR") || upper.equals("LHR")) return null;
    // Reject PCS
    if (PCS_TOKEN.matcher(upper).matches()) return null;

    // ONLY allow real container prefixes
    if (upper.startsWith("AKE") || upper.startsWith("ULD")) {
      // Normalize spacing and OCR punctuation: "AKE 9S*5" → "AKE9S5"
      // Keep only A-Z / 0-9 / '-' then remove spaces.
      String cleaned = upper.replaceAll("\\s+", "");
      cleaned = cleaned.replaceAll("[^A-Z0-9-]", "");
      return cleaned;
    }

    return null;
  }

  /**
   * Normalizes AKE suffix variants (e.g. "9S*5", "9S5", "9S*") into alphanumeric form without '*'.
   * Returns null if suffix doesn't look valid after cleanup.
   */
  @Nullable
  private static String normalizeAkeSuffix(String rawSuffix) {
    if (rawSuffix == null) return null;
    String s = rawSuffix.toUpperCase(Locale.US).trim();
    // Drop common OCR separators/punct (keep only letters+digits).
    s = s.replaceAll("[^A-Z0-9]", "");
    if (s.isEmpty()) return null;
    // Require at least one digit and one letter for chart-style (e.g. 9S5) or allow B1/A1 style.
    boolean hasLetter = containsLetter(s);
    boolean hasDigit = containsDigit(s);
    if (!hasLetter || !hasDigit) return null;
    return s;
  }


  private static void addByProximityFallback(String text, Map<String, Instruction> out) {
    if (text == null) return;
    Matcher posMatcher = POSITION_PATTERN.matcher(text);

    while (posMatcher.find()) {
      String row = posMatcher.group(1);
      String side = posMatcher.group(2);
      if (row == null || side == null) continue;
      String pos = (row + side).toUpperCase(Locale.US);

      int matchStart = posMatcher.start();
      int windowStart = Math.max(0, matchStart - 60);
      int windowEnd = Math.min(text.length(), posMatcher.end() + 10);
      String window = text.substring(windowStart, windowEnd);

      String container = findContainerId(window, pos);
      if (container == null) continue;
      if (!out.containsKey(container)) {
        out.put(container, new Instruction(container, pos));
      }
    }
  }

  private static String findPosition(String line) {
    List<PositionMatch> all = findAllPositionsInLine(line);
    return all.isEmpty() ? null : all.get(0).position;
  }

  /** One position found in a line, with its start index and length in the normalized line. */
  private static final class PositionMatch {
    final String position;
    final int startIndex;
    final int length;

    PositionMatch(String position, int startIndex, int length) {
      this.position = position;
      this.startIndex = startIndex;
      this.length = length;
    }
  }

  /** Finds all position codes in a line (e.g. "5L" and "7R" in "5L ULD 1 7R ULD 2"). */
  private static List<PositionMatch> findAllPositionsInLine(String line) {
    List<PositionMatch> out = new ArrayList<>();
    if (line == null) return out;
    String normalized = line.trim().replaceAll("(?i)(\\d)\\s*[-]?\\s*([LR])\\b", "$1$2");
    Matcher m = POSITION_PATTERN.matcher(normalized);
    boolean any = false;
    while (m.find()) {
      String row = m.group(1);
      String side = m.group(2);
      if (row != null && side != null) {
        String pos = (row + side).toUpperCase(Locale.US);
        out.add(new PositionMatch(pos, m.start(), m.end() - m.start()));
        any = true;
      }
    }
    // Fallback for fused "7RULD1" style where the word-boundary pattern misses the position.
    if (!any) {
      Matcher fusedMatcher = POSITION_BEFORE_ULD.matcher(normalized);
      while (fusedMatcher.find()) {
        String row = fusedMatcher.group(1);
        String side = fusedMatcher.group(2);
        if (row != null && side != null) {
          String pos = (row + side).toUpperCase(Locale.US);
          out.add(new PositionMatch(pos, fusedMatcher.start(), fusedMatcher.end() - fusedMatcher.start()));
        }
      }
    }
    return out;
  }

  /** Splits lineRect horizontally by character offset so each position gets a distinct bbox. */
  private static RectF subRectByCharOffset(RectF lineRect, int lineLen, int start, int len) {
    float w = lineRect.width();
    float left = lineRect.left + (start * w / lineLen);
    float right = lineRect.left + ((start + len) * w / lineLen);
    return new RectF(left, lineRect.top, right, lineRect.bottom);
  }

  private static String findContainerId(String line, String position) {
    if (line == null) return null;
    String normalizedPos = position == null ? null : position.toUpperCase(Locale.US);

    // Handle fused "position+ULD" strings like "7RULD1" by extracting ULD1 explicitly.
    Matcher fused = POSITION_ULD_FUSED.matcher(line);
    if (fused.find()) {
      String num = fused.group(3);
      if (num != null) {
        String id = "ULD" + num.trim();
        return id.toUpperCase(Locale.US);
      }
    }

    // Prefer "ULD 1" / "ULD-2" -> ULD1, ULD2 so each line gets a distinct container.
    Matcher uldNum = ULD_WITH_NUMBER.matcher(line);
    if (uldNum.find()) {
      String num = uldNum.group(1);
      if (num != null) {
        String id = "ULD" + num.trim();
        return id.toUpperCase(Locale.US);
      }
    }

    // Prefer "AKE 017373" / "AKE B1" / "AKE-017373" -> AKE017373, AKEB1 (so AKE instructions load).
    Matcher akeMatcher = AKE_WITH_SUFFIX.matcher(line);
    if (akeMatcher.find()) {
      String suffix = akeMatcher.group(1);
      if (suffix != null && !suffix.isEmpty()) {
        String id = "AKE" + suffix.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (id.length() >= 4) return id; // e.g. AKE1, AKEB1, AKE017373
      }
    }

    Matcher m = CONTAINER_TOKEN_PATTERN.matcher(line);
    String best = null;
    int bestScore = -1;
    while (m.find()) {
      String token = m.group(1);
      if (token == null) continue;
      token = token.trim();
      if (token.isEmpty()) continue;

      String upperRaw = token.toUpperCase(Locale.US);
      // Reuse normalizeContainerToken so we don't accidentally treat weights/temps/etc as IDs.
      String normalizedToken = normalizeContainerToken(upperRaw);
      if (normalizedToken == null) {
        continue;
      }
      String upper = normalizedToken;
      if (normalizedPos != null && upper.equals(normalizedPos)) {
        continue; // Don't treat 12R as a container id.
      }

      int score = 0;
      if (upper.contains("-")) score += 3; // e.g., ULD-12
      if (containsLetter(upper) && containsDigit(upper)) score += 2; // e.g., AKE123
      if (upper.length() >= 5) score += 1;

      if (score > bestScore) {
        bestScore = score;
        best = upper;
      }
    }

    return best;
  }

  private static boolean containsLetter(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 'A' && c <= 'Z') return true;
    }
    return false;
  }

  private static boolean containsDigit(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') return true;
    }
    return false;
  }
}