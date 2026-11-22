package com.brimap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dataDir     folder holding data/, inbox/, incoming_invoices/ (the dataset as shipped)
 * @param storageDir  where uploaded documents and the LLM response cache live
 * @param seedMode    if-empty (default), always, never
 * @param llmApiKey   LLM key; provider is inferred from its prefix
 * @param llmModel    model id override
 * @param pdftotext   path to the pdftotext binary
 * @param tesseract   path to the tesseract binary (optional OCR fallback)
 * @param businessDate optional fixed "today" (YYYY-MM-DD) for reproducible demos
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String dataDir, String storageDir, String seedMode, String llmApiKey, String llmModel,
                            String llmBaseUrl, String pdftotext, String tesseract, String businessDate, String corsOrigins) {
}
