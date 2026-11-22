package com.brimap.extraction;

import com.brimap.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Text layers: pdftotext for PDFs (raw reading order, UTF-8), tesseract for images when available. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentTextReader {
    private final AppProperties props;

    public String pdfText(Path pdf) {
        return run(List.of(props.pdftotext(), "-raw", "-enc", "UTF-8", pdf.toString(), "-"));
    }

    /** OCR fallback for images (offline). Returns null if tesseract is not installed. */
    public String ocrText(Path image) {
        String bin = props.tesseract();
        if ("tesseract".equals(bin) && java.nio.file.Files.exists(Path.of("C:/Program Files/Tesseract-OCR/tesseract.exe"))) {
            bin = "C:/Program Files/Tesseract-OCR/tesseract.exe"; // default Windows install location
        }
        String text = run(List.of(bin, image.toString(), "stdout", "-l", "eng+fra"));
        return text != null ? text : run(List.of(bin, image.toString(), "stdout", "-l", "eng")); // French data not installed
    }

    private String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("{} timed out", cmd.get(0));
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("{} exited {}", cmd.get(0), p.exitValue());
                return null;
            }
            String s = new String(out, StandardCharsets.UTF_8).replace("\f", "\n").strip();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            log.info("{} not available: {}", cmd.get(0), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
