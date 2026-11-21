package com.brimap.extraction;

import com.brimap.common.Json;
import com.brimap.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads one document. Order: (1) LLM with text layer + original file, cached by file hash;
 * (2) offline regex parser on the text layer (pdftotext or tesseract OCR); (3) empty manual-entry draft.
 * Never throws for bad input: garbage in gives a manual-entry draft with the reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractionService {
    private final LlmExtractor llm;
    private final DocumentTextReader reader;
    private final AppProperties props;

    public record Outcome(ExtractedInvoice invoice, String method, String model, String rawText, String rawJson,
                          String error, boolean fromCache, long durationMs) {
        public boolean manual() { return invoice == null; }
    }

    public Outcome extract(Path file, String mediaType, String sha256) {
        long t0 = System.currentTimeMillis();
        boolean pdf = "application/pdf".equals(mediaType);
        boolean image = mediaType != null && mediaType.startsWith("image/");
        String text = pdf ? reader.pdfText(file) : null;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return new Outcome(null, "MANUAL", null, null, null, "Cannot read file: " + e.getMessage(), false, 0);
        }
        if (!pdf && !image) {
            return new Outcome(null, "MANUAL", null, null, null, "Unsupported file type " + mediaType + "; enter the invoice manually", false, 0);
        }
        String error = null;

        // 1) LLM (cached by content hash + model + prompt version)
        if (llm.enabled()) {
            Path cache = cachePath(sha256);
            try {
                if (cache != null && Files.exists(cache)) {
                    String json = Files.readString(cache);
                    return new Outcome(Json.read(json, ExtractedInvoice.class), pdf ? "PDFTEXT_LLM" : "VISION_LLM", llm.model(),
                            text, json, null, true, System.currentTimeMillis() - t0);
                }
                var res = llm.extract(text, bytes, mediaType);
                if (res.isPresent() && res.get().invoice() != null) {
                    if (cache != null) {
                        Files.createDirectories(cache.getParent());
                        Files.writeString(cache, res.get().rawJson());
                    }
                    return new Outcome(res.get().invoice(), pdf ? "PDFTEXT_LLM" : "VISION_LLM", llm.model(), text, res.get().rawJson(),
                            null, false, System.currentTimeMillis() - t0);
                }
                error = res.map(r -> "LLM stopped: " + r.stopReason()).orElse("LLM unavailable");
            } catch (ExtractionException | IOException e) {
                error = e.getMessage();
                log.warn("LLM extraction failed, falling back: {}", e.getMessage());
            } catch (RuntimeException e) {
                error = "LLM response rejected: " + e.getMessage();
                log.warn("LLM response did not match schema, falling back: {}", e.getMessage());
            }
        } else {
            error = "No LLM configured";
        }

        // 2) Offline: regex over the text layer (OCR for images)
        String method = "HEURISTIC";
        if (text == null && image) {
            text = reader.ocrText(file);
            method = "OCR_HEURISTIC";
        }
        ExtractedInvoice h = HeuristicParser.parse(text);
        if (h != null) {
            return new Outcome(h, method, null, text, Json.write(h), error, false, System.currentTimeMillis() - t0);
        }

        // 3) Manual-entry draft
        String why = (text == null ? "No readable text in the document" : "Could not find an invoice number or total")
                + (error == null ? "" : " (" + error + ")");
        return new Outcome(null, "MANUAL", null, text, null, why, false, System.currentTimeMillis() - t0);
    }

    private Path cachePath(String sha) {
        if (sha == null || props.storageDir() == null) return null;
        String model = llm.model().replaceAll("[^A-Za-z0-9.-]", "_");
        return Path.of(props.storageDir(), "llm-cache", sha + "-" + model + "-" + LlmExtractor.PROMPT_VERSION + ".json");
    }
}
