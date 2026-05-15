# AR Load Planning (Hello AR Java)

Android app for **ramp load-sheet validation** against a **physical board**: the camera shows the chart, ML Kit reads **position labels** (e.g. `12L`, `3R`) and **ULD-style IDs** on labels or matchboxes, and the UI highlights each load-sheet slot in **grey** (no ULD read yet), **green** (detected ULD matches the sheet), or **red** (mismatch).

There is **no fixed aircraft grid**—only positions that appear on the uploaded load sheet are highlighted when their labels are visible in the scene.

---

## Requirements

- **Android** 7.0+ (`minSdkVersion` 24 in this project)
- **ARCore**-capable device (`android.hardware.camera.ar`)
- **Camera** permission at runtime

---

## Tech stack

| Piece | Role |
|--------|------|
| [ARCore](https://developers.google.com/ar) | Camera session + `Frame` for `IMAGE_PIXELS` → view transforms |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) | Live OCR on camera frames; OCR on PDF/image load sheets |
| `GLSurfaceView` + `BackgroundRenderer` | Camera preview under the 2D overlay |

---

## User flow

1. **Home** — Tap **Upload Load Sheet** and pick **PDF**, **image**, or **plain text**.
2. The app parses **(container ID, expected position)** pairs (e.g. `AKE017373` → `12R`).
3. Tap **Start Scan** to open the **board scan** screen.
4. Point the camera at the board. Grey boxes appear on **recognized position codes** that exist on the sheet.
5. When a **ULD ID** is read near a slot, that slot turns **green** or **red** vs the expected container for that position (with short persistence so colors do not flicker every frame).
6. **Instructions & sheet** — Dialog with parsed list + **preview** of the uploaded file (image, first PDF page, or text excerpt) when the document URI was passed from Home.

---

## Project layout (main app code)

| Path | Purpose |
|------|---------|
| `app/.../helloar/HomeActivity.java` | Upload, OCR (image/PDF), parse instructions, launch scan with intent extras |
| `app/.../helloar/BoardScanActivity.java` | ARCore session, throttled live OCR, overlay update, ULD vs sheet matching, load-sheet dialog |
| `app/.../helloar/LoadSheetInstructionParser.java` | Parse free-form text + structured ML Kit `Text` into instructions |
| `app/.../helloar/PositionOverlayView.java` | Draws slot rectangles and states |
| `app/src/main/res/layout/activity_home.xml` | Home UI |
| `app/src/main/res/layout/activity_main.xml` | Camera + overlay + instructions button |
| `app/src/main/res/layout/dialog_load_sheet_instructions.xml` | Instructions + file preview dialog |
| `app/src/main/assets/sample_loading_plan.txt` | Example text load sheet for testing |

Shared ARCore / rendering helpers live under `app/.../common/` (e.g. `BackgroundRenderer`, `DisplayRotationHelper`).

---

## Build

```bash
./gradlew :app:assembleDebug
```

On Windows use `gradlew.bat`. Install the generated APK on a supported device with ARCore installed.

---

## Intent extras (Home → Board scan)

| Extra | Type | Meaning |
|-------|------|---------|
| `LOAD_CONTAINERS` | `ArrayList<String>` | Container / ULD ids from the sheet |
| `LOAD_POSITIONS` | `ArrayList<String>` | Expected position per row (same order) |
| `LOAD_SHEET_DOCUMENT_URI` | `String` | `Uri.toString()` of the last opened document (optional) |
| `LOAD_SHEET_DOCUMENT_MIME` | `String` | MIME type for preview routing (optional) |

Constants on `BoardScanActivity`: `EXTRA_LOAD_SHEET_DOCUMENT_URI`, `EXTRA_LOAD_SHEET_DOCUMENT_MIME`.

---

## Limits & behavior notes

- **PDF import**: OCR walks pages (capped in code); processing stops once structured parse yields instructions where applicable.
- **Position format** on the board: `1`–`999` + `L` or `R` (with OCR repair for common digit/L mistakes).
- **ULD pattern** for live OCR: letters+digits token (see `BoardScanActivity`); pairing uses **nearest** position label in image space with a **distance** gate.
- **Overlay** is non-clickable so touches are not captured for a separate tap workflow; identification is **camera-driven**.

---

## License

This project includes Google ARCore sample code and assets; see file headers (e.g. Apache-2.0) in the repository.
