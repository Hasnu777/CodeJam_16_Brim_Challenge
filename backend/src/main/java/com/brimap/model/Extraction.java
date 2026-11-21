package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** The raw result of reading a document, kept so we can show what the model said vs what the clerk corrected. */
@Entity @Table(name = "extractions")
@Getter @Setter @NoArgsConstructor
public class Extraction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long documentId;
    private String invoiceId;
    /** PDFTEXT_LLM, VISION_LLM, OCR_LLM, HEURISTIC, MANUAL. */
    private String method;
    private String model;
    @Column(columnDefinition = "text") private String rawText;
    @Column(columnDefinition = "text") private String rawJson;
    /** Per-field confidence 0..1 as JSON. */
    @Column(columnDefinition = "text") private String confidenceJson;
    @Column(columnDefinition = "text") private String error;
    private boolean fromCache;
    private long durationMs;
    private Instant createdAt;
}
