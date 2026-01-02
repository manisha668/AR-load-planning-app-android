# AR Load Planning - Ramp Position System

## Overview

This AR application combines **ARCore camera coordinates** with **operational ramp positions** to validate container placement on aircraft ramps.

**Key Concept**: Coordinates are the input; ramp positions (like "2R", "4L") are the operational output.

---

## Architecture

### Workflow
```
ARCore World Coordinates 
    ↓
Ramp-Local Coordinates (relative to ramp anchor)
    ↓
Normalized Coordinates (0 to 1 range)
    ↓
Logical Ramp Positions (e.g., "2R", "4L")
    ↓
Load Validation (weight & position checks)
    ↓
AR Visualization (green = OK, red = invalid)
```

---

## Core Components

### 1. **AircraftConfig.java**
Defines aircraft types with their specifications:
- Ramp dimensions (width & length in meters)
- Total number of rows
- Weight limits per position

**Available Aircraft Types:**
- `AIRCRAFT_A`: 8m × 20m, 4 rows
- `AIRCRAFT_B`: 10m × 25m, 5 rows  
- `BOEING_737`: 6m × 15m, 3 rows

### 2. **RampCoordinateConverter.java**
Converts between coordinate systems:
- **World → Ramp-local**: Translates ARCore world coordinates to ramp-relative coordinates
- **Ramp-local → Normalized**: Scales coordinates to 0-1 range
- **Normalized → Ramp Position**: Converts to logical position (e.g., "2R")
- **Ramp Position → World**: Reverse conversion for visualization

**Coordinate Mapping:**
- **X axis**: Left ↔ Right on the ramp
  - Normalized X < 0.5 → Left (L)
  - Normalized X ≥ 0.5 → Right (R)
- **Z axis**: Front ↔ Back (row direction)
  - Normalized Z determines row number (1 to N)
- **Y axis**: Height (not used for position logic)

### 3. **RampPosition.java**
Represents logical ramp positions:
- Format: `[Row][Side]` (e.g., "2R", "4L")
- `fromNormalized()`: Creates position from normalized coordinates
- `asCode()`: Returns position string

### 4. **LoadingPlan.java**
Stores expected container placements:
- Maps container IDs to expected positions and weights
- Stores weight limits per ramp position
- Can be populated from text files or hardcoded

### 5. **LoadingPlanParser.java**
Parses loading plan text files:
- Format: `ContainerID=X, Weight=Y, Position=Z`
- Supports comments (lines starting with #)
- Can load from assets, files, or streams

### 6. **PlacementEvaluator.java**
Validates container placement:
- Checks if position matches expected plan
- Verifies weight is within position limits
- Suggests better positions if invalid
- Tracks ramp occupancy

---

## Usage Flow

### 1. Aircraft Selection (HomeActivity)
User selects aircraft type from dropdown:
```java
// Aircraft type is passed to HelloArActivity
intent.putExtra("AIRCRAFT_TYPE", selectedAircraftType.name());
```

### 2. Configuration Setup (HelloArActivity.onCreate)
```java
// Get aircraft configuration
aircraftConfig = AircraftConfig.getConfig(aircraftType);

// Load and configure loading plan
loadingPlan = LoadingPlan.createDemoPlan();
aircraftConfig.applyWeightLimitsTo(loadingPlan);

// Create placement evaluator
placementEvaluator = new PlacementEvaluator(
    loadingPlan, 
    aircraftConfig.getTotalRows()
);
```

### 3. Ramp Anchor Establishment
When first plane is detected:
```java
rampAnchor = session.createAnchor(plane.getCenterPose());

// Create coordinate converter
coordinateConverter = new RampCoordinateConverter(
    rampAnchor,
    aircraftConfig.getRampWidthMeters(),
    aircraftConfig.getRampLengthMeters(),
    aircraftConfig.getTotalRows()
);
```

### 4. Container Placement (on user tap)
```java
// 1. Get tap position in world coordinates
Pose hitPose = hit.getHitPose();

// 2. Convert to ramp position
RampPosition rampPosition = 
    coordinateConverter.worldPoseToRampPosition(hitPose);

// 3. Evaluate placement
PlacementEvaluator.Result result = 
    placementEvaluator.evaluate(containerId, weight, rampPosition);

// 4. Show visual feedback (green/red)
if (result.overallOk) {
    // Show green indicator
} else {
    // Show red indicator + suggestion
}
```

---

## Loading Plan File Format

**Location**: `app/src/main/assets/sample_loading_plan.txt`

**Format**:
```
# Comments start with #
ContainerID=AKE123, Weight=4800, Position=2R
ContainerID=AKE456, Weight=3000, Position=1L
ContainerID=AKE789, Weight=3500, Position=3L
```

**Fields**:
- `ContainerID`: Unique container identifier
- `Weight`: Container weight in kilograms
- `Position`: Ramp position code (e.g., "2R" = Row 2, Right side)

---

## Validation Logic

### Position Matching
- Compares actual position with expected position from plan
- Result: `positionMatchesPlan` (boolean)

### Weight Validation
- Checks if container weight ≤ position's maximum allowed weight
- Result: `withinWeightLimit` (boolean)

### Placement Suggestion
If placement is invalid:
1. Find all empty ramp positions
2. Filter positions where `maxWeight ≥ containerWeight`
3. Choose nearest valid position (by row distance + side mismatch)
4. Visualize suggestion in AR

---

## AR Visualization

**Color Coding**:
- **Green**: Correct placement (position matches plan + weight OK)
- **Red**: Invalid placement (wrong position or overweight)

**Information Display**:
- Container ID
- Actual ramp position
- Expected position (if different)
- Suggested position (if placement invalid)
- Reason for invalidity (e.g., "Overweight")

---

## Future Enhancements

### ML Integration (Optional)
- Container detection via camera
- Barcode/label reading for container ID
- Weight estimation from visual markers

### Advanced Features
- CSV/database loading plan support
- Dynamic ramp configuration
- Multi-container visualization
- Load balancing calculations
- Real-time weight adjustment

### UI Improvements
- File picker for loading plan upload
- Aircraft configuration editor
- Historical placement tracking
- Export validation reports

---

## Testing

### Manual Testing Steps
1. Select aircraft type in HomeActivity
2. Start AR View
3. Point camera at horizontal surface
4. Wait for plane detection and ramp anchor establishment
5. Tap on surface to place virtual containers
6. Observe:
   - Ramp position calculation (logged)
   - Placement validation (green/red feedback)
   - Suggested positions (if invalid)

### Sample Scenarios
- **Valid Placement**: Place AKE123 at position 2R (should be green)
- **Invalid Position**: Place AKE123 at position 1L (should be red, suggest 2R)
- **Overweight**: Place heavy container at position with low weight limit

---

## Key Files

- `AircraftConfig.java` - Aircraft specifications
- `RampCoordinateConverter.java` - Coordinate transformations
- `RampPosition.java` - Logical position representation
- `LoadingPlan.java` - Expected placements
- `LoadingPlanParser.java` - File parsing
- `PlacementEvaluator.java` - Validation logic
- `HelloArActivity.java` - Main AR activity (integration)
- `HomeActivity.java` - Aircraft selection UI

---

## Troubleshooting

### Issue: Ramp anchor not established
- **Cause**: No planes detected
- **Solution**: Move camera to scan horizontal surfaces

### Issue: Wrong ramp positions detected
- **Cause**: Incorrect aircraft dimensions or ramp anchor position
- **Solution**: Verify aircraft config, ensure anchor is at ramp center

### Issue: All placements show as invalid
- **Cause**: Loading plan doesn't match selected aircraft
- **Solution**: Update loading plan for correct aircraft type

---

## Academic Context

This system demonstrates:
1. **Coordinate System Transformation**: World → Local → Normalized → Logical
2. **Rule-Based Validation**: Position and weight constraints
3. **Spatial Reasoning**: Distance calculations for suggestions
4. **AR Visualization**: Real-time feedback overlay

**No ML required** for core logic - pure deterministic coordinate math and rules.
