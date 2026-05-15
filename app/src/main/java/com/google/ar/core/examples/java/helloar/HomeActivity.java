package com.google.ar.core.examples.java.helloar;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> openLoadSheetLauncher;
    private TextRecognizer textRecognizer;
    private TextView tvLoadSheetStatus;

    private final ArrayList<String> loadedContainerIds = new ArrayList<>();
    private final ArrayList<String> loadedExpectedPositions = new ArrayList<>();
    /** Last document opened for load sheet (shown again in AR screen). */
    private Uri lastLoadSheetDocumentUri;
    private String lastLoadSheetDocumentMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        Button btnUpload = findViewById(R.id.btnUpload);
        Button btnARView = findViewById(R.id.btnARView);
        tvLoadSheetStatus = findViewById(R.id.tvLoadSheetStatus);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        openLoadSheetLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.OpenDocument(),
                        uri -> {
                            if (uri == null) return;
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                );
                            } catch (SecurityException ignored) {
                                // Some providers don't allow persistable permission; we can still read now.
                            }
                            importLoadSheet(uri);
                        });

        btnUpload.setOnClickListener(v -> {
            // PDF / image / text file.
            openLoadSheetLauncher.launch(new String[] {"application/pdf", "image/*", "text/plain", "text/*"});
        });

        btnARView.setOnClickListener(v -> {
            if (loadedContainerIds.isEmpty() || loadedExpectedPositions.isEmpty()) {
                Toast.makeText(this, "Upload a load sheet first", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, BoardScanActivity.class);
            intent.putStringArrayListExtra("LOAD_CONTAINERS", loadedContainerIds);
            intent.putStringArrayListExtra("LOAD_POSITIONS", loadedExpectedPositions);
            if (lastLoadSheetDocumentUri != null) {
                intent.putExtra(BoardScanActivity.EXTRA_LOAD_SHEET_DOCUMENT_URI, lastLoadSheetDocumentUri.toString());
                intent.setClipData(ClipData.newRawUri("load_sheet", lastLoadSheetDocumentUri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (lastLoadSheetDocumentMime != null) {
                intent.putExtra(BoardScanActivity.EXTRA_LOAD_SHEET_DOCUMENT_MIME, lastLoadSheetDocumentMime);
            }
            startActivity(intent);
        });
    }

    private void importLoadSheet(Uri uri) {
        lastLoadSheetDocumentUri = uri;
        lastLoadSheetDocumentMime = getContentResolver().getType(uri);

        Toast.makeText(this, "Parsing load sheet…", Toast.LENGTH_SHORT).show();

        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";

        if (mime.startsWith("text/") || "text/plain".equalsIgnoreCase(mime)) {
            try {
                String text = readAllTextFromUri(uri);
                applyParsedInstructions(LoadSheetInstructionParser.parse(text));
            } catch (IOException e) {
                Log.w("HOME", "Failed to read text load sheet", e);
                Toast.makeText(this, "Failed to read load sheet", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if ("application/pdf".equalsIgnoreCase(mime)) {
            ocrPdfAndParse(uri);
            return;
        }

        if (mime.startsWith("image/")) {
            Bitmap bitmap = decodeBitmapFromUri(uri);
            if (bitmap == null) {
                Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show();
                return;
            }
            ocrBitmapAndParse(bitmap);
            return;
        }

        // Fallback: try reading as text.
        try {
            String text = readAllTextFromUri(uri);
            applyParsedInstructions(LoadSheetInstructionParser.parse(text));
        } catch (IOException e) {
            Log.w("HOME", "Unsupported load sheet type: " + mime, e);
            Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show();
        }
    }

    private void ocrBitmapAndParse(Bitmap bitmap) {
        // Many load-sheet screenshots are low-resolution; OCR accuracy drops unless the user zooms.
        // Scale up moderately before running ML Kit to improve recognition.
        Bitmap ocrBitmap = maybeScaleForOcr(bitmap);
        InputImage image = InputImage.fromBitmap(ocrBitmap, 0);
        textRecognizer
                .process(image)
                .addOnSuccessListener(result -> {
                    applyParsedInstructions(LoadSheetInstructionParser.parse(result));
                })
                .addOnFailureListener(e -> {
                    Log.w("HOME", "OCR failed", e);
                    Toast.makeText(this, "OCR failed", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Upscales small/medium images to improve OCR (mimics "zooming" without user action).
     * Keeps aspect ratio and avoids excessive memory use.
     */
    private Bitmap maybeScaleForOcr(Bitmap bitmap) {
        if (bitmap == null) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return bitmap;

        // Target the long edge to around 2200–2800 px for better text recognition.
        int longEdge = Math.max(w, h);
        int targetLongEdge = 2600;
        if (longEdge >= targetLongEdge) return bitmap;

        float scale = targetLongEdge / (float) longEdge;
        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));

        try {
            return Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        } catch (OutOfMemoryError oom) {
            // If the device is memory-constrained, fall back to original.
            return bitmap;
        }
    }

    private void applyParsedInstructions(List<LoadSheetInstructionParser.Instruction> instructions) {
        loadedContainerIds.clear();
        loadedExpectedPositions.clear();

        if (instructions == null || instructions.isEmpty()) {
            Toast.makeText(this, "No instructions found in load sheet", Toast.LENGTH_LONG).show();
            if (tvLoadSheetStatus != null) {
                tvLoadSheetStatus.setText("Load sheet: no instructions found");
            }
            return;
        }

        for (LoadSheetInstructionParser.Instruction i : instructions) {
            loadedContainerIds.add(i.containerId);
            loadedExpectedPositions.add(i.expectedPosition);
        }

        if (tvLoadSheetStatus != null) {
            tvLoadSheetStatus.setText("Load sheet: "
                    + loadedContainerIds.size()
                    + " instructions loaded");
        }

        Toast.makeText(
                this,
                "Loaded " + loadedContainerIds.size() + " instructions",
                Toast.LENGTH_SHORT
        ).show();

        // show a dialog listing all container-position pairs
        showInstructionsDialog(loadedContainerIds, loadedExpectedPositions, null);
    }
    private void showInstructionsDialog(List<String> containers,
                                        List<String> positions,
                                        Runnable onDismiss) {
        if (containers == null || containers.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < containers.size(); i++) {
            sb.append(containers.get(i))
              .append('-')
              .append(positions.get(i));
            if (i < containers.size() - 1) {
                sb.append("\n");
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("Loaded instructions")
            .setMessage(sb.toString())
            .setPositiveButton("OK", (d, w) -> {
                if (onDismiss != null) onDismiss.run();
            })
            .setOnDismissListener(d -> {
                if (onDismiss != null) onDismiss.run();
            })
            .show();
    }

    private String readAllTextFromUri(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("openInputStream returned null");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private Bitmap decodeBitmapFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Log.w("HOME", "Failed to decode image", e);
            return null;
        }
    }

    private void ocrPdfAndParse(Uri uri) {
        final ParcelFileDescriptor pfd;
        final PdfRenderer renderer;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                Toast.makeText(this, "Failed to open PDF", Toast.LENGTH_SHORT).show();
                return;
            }
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            Log.w("HOME", "Failed to open PDF", e);
            Toast.makeText(this, "Failed to open PDF", Toast.LENGTH_SHORT).show();
            return;
        }

        int pageCount = renderer.getPageCount();
        if (pageCount <= 0) {
            try { renderer.close(); } catch (Exception ignored) {}
            try { pfd.close(); } catch (Exception ignored) {}
            Toast.makeText(this, "Empty PDF", Toast.LENGTH_SHORT).show();
            return;
        }

        // Safety cap to avoid huge PDFs freezing the UI.
        int maxPages = Math.min(pageCount, 10);
        new PdfOcrSession(pfd, renderer, maxPages).processPage(0);
    }

    private final class PdfOcrSession {
        private final ParcelFileDescriptor pfd;
        private final PdfRenderer renderer;
        private final int maxPages;
        private final StringBuilder allText = new StringBuilder();
        private boolean closed = false;

        PdfOcrSession(ParcelFileDescriptor pfd, PdfRenderer renderer, int maxPages) {
            this.pfd = pfd;
            this.renderer = renderer;
            this.maxPages = maxPages;
        }

        void processPage(int pageIndex) {
            if (closed) return;
            if (pageIndex >= maxPages) {
                finish();
                return;
            }

            Bitmap bitmap = null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(pageIndex);

                int width = Math.max(1, page.getWidth() * 2);
                int height = Math.max(1, page.getHeight() * 2);
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (Exception e) {
                Log.w("HOME", "Failed to render PDF page " + pageIndex, e);
                // Continue to next page.
                safeClosePage(page);
                processPage(pageIndex + 1);
                return;
            } finally {
                safeClosePage(page);
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            textRecognizer
                    .process(image)
                    .addOnSuccessListener(result -> {
                        // Prefer structured parsing (bounding boxes), fall back to text as needed.
                        List<LoadSheetInstructionParser.Instruction> parsed =
                                LoadSheetInstructionParser.parse(result);
                        if (parsed != null && !parsed.isEmpty()) {
                            applyParsedInstructions(parsed);
                            close();
                            return;
                        }
                        String pageText = result != null ? result.getText() : "";
                        if (!pageText.isEmpty()) {
                            allText.append(pageText).append('\n');
                        }
                        processPage(pageIndex + 1);
                    })
                    .addOnFailureListener(e -> {
                        Log.w("HOME", "OCR failed on PDF page " + pageIndex, e);
                        processPage(pageIndex + 1);
                    });
        }

        private void finish() {
            if (closed) return;
            applyParsedInstructions(LoadSheetInstructionParser.parse(allText.toString()));
            close();
        }

        private void close() {
            if (closed) return;
            closed = true;
            try { renderer.close(); } catch (Exception ignored) {}
            try { pfd.close(); } catch (Exception ignored) {}
        }

        private void safeClosePage(PdfRenderer.Page page) {
            try {
                if (page != null) page.close();
            } catch (Exception ignored) {}
        }
    }
}