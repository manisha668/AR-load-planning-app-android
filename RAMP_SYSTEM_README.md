# AR Load Planning - Board Scan Validation

## Overview

This app validates container placement using a **physical whiteboard/chart** (with handwritten position labels like `1L`, `12R`, `13R`) and a **load sheet** uploaded by the user.

**No aircraft grid and no predefined layout are used.** Only the position labels visible in the camera view become tappable slots.

---

## Required User Flow (Implemented)

### 1) User prepares the scene
- Place a **whiteboard/chart paper** in front of the camera.
- Write clear position labels (examples: `1L`, `1R`, `12R`, `13R`).

### 2) Upload load sheet (FIRST step)
- In `HomeActivity`, the user uploads a load sheet as **PDF / image / text**.
- The app extracts load-sheet text (OCR for PDF/image) and parses dynamic instructions:
  - **Container ID** (e.g. `ULD-12`, `AKE123`)
  - **Expected Position** (e.g. `12R`)

### 3) Camera scans the board continuously
- In `BoardScanActivity`, live camera frames are processed with ML Kit text recognition.
- Detected tokens are validated as position codes (format: `\\d{1,3}[LR]`).

### 4) Grey boxes appear on detected positions
- For each detected position label, a **grey box** is drawn at the detected label location.
- The box displays the detected label text (e.g. `12R`).

### 5) User taps a grey box
- The user taps directly on a grey box.
- The app identifies which position code was tapped.

### 6) Validation happens
- The tapped position is compared with the **current instruction** from the uploaded load sheet.
- If correct:
  - tapped box turns **green**
- If wrong:
  - tapped box turns **red**
  - the **expected position** is shown in the on-screen feedback

### 7) Feedback + advance
- The app shows **Correct/Wrong** + **Expected Position**.
- The instruction advances to the next container until all are processed.

---

## Key Files

- `app/src/main/java/.../helloar/HomeActivity.java`
  - File picker upload for PDF/image/text
  - OCR + parsing into dynamic instructions
- `app/src/main/java/.../helloar/LoadSheetInstructionParser.java`
  - Extracts `(containerId, expectedPosition)` from load-sheet text
- `app/src/main/java/.../helloar/BoardScanActivity.java`
  - Live OCR scanning of the board + instruction validation
- `app/src/main/java/.../helloar/PositionOverlayView.java`
  - Draws grey/green/red tappable position boxes
- `app/src/main/res/layout/activity_main.xml`
  - Hosts camera surface + overlay view + feedback banner

---

## Notes / Limits

- PDF OCR processes up to the first 10 pages (stops early when instructions are found).
- Position code format supported: `1L`, `12R`, `103L` (1–3 digits + `L`/`R`).

