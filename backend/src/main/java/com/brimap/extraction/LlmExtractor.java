package com.brimap.extraction;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.*;
import com.brimap.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The only place the app talks to an LLM, and it only READS documents. Uses the Anthropic Java SDK with
 * structured outputs: the response must validate against the JSON schema derived from {@link ExtractedInvoice}.
 * Everything downstream (normalization, arithmetic, matching, rules) is deterministic Java.
 */
@Component
@Slf4j
public class LlmExtractor {
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** Bump when the prompt or schema changes, so cached responses are not reused across versions. */
    public static final String PROMPT_VERSION = "v3";

    static final String SYSTEM = """
            You read accounts-payable documents (supplier invoices and credit notes) for Harborline Logistics, a Canadian company,
            and transcribe them into the given JSON schema.
            Rules:
            - Transcribe, do not compute. Copy every amount, date and number exactly as printed, including currency symbols,
              minus signs, parentheses, spaces and French decimal commas (e.g. "1 234,56 $"). Never fix arithmetic you think is wrong.
            - The supplier is the party that ISSUED the document (letterhead), never the bill-to customer (Harborline Logistics).
              Harborline's own GST number 100000001RT0001 is NOT the supplier's number.
            - Invoice number: the supplier's document number only (e.g. "FA-002399", "000809", "CN-FA-000413"), not a PO or customer number.
            - Lines: one entry per billable row (description, quantity, unit price, amount). Do not include subtotal/tax/total rows.
            - Taxes: one entry per tax row (GST/TPS, HST/TVH, QST/TVQ, PST). Leave the list empty if no tax is charged.
            - Remit-to: the bank institution / transit / account printed under "Remit to", "Virement" or similar.
            - Use an empty string for anything not on the document. If the document is not an invoice or credit note
              (e.g. a statement, a quote, an unrelated image), say so in documentType and fill what you can.
            - Confidence: below 0.6 for anything partially illegible, ambiguous, or that you had to infer.
            """;

    private final AppProperties props;
    private volatile AnthropicClient client;
    private volatile boolean authFailed;

    public LlmExtractor(AppProperties props) { this.props = props; }

    public boolean enabled() {
        return !authFailed && props.llmApiKey() != null && !props.llmApiKey().isBlank();
    }

    public String model() {
        return props.llmModel() == null || props.llmModel().isBlank() ? DEFAULT_MODEL : props.llmModel();
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    var b = AnthropicOkHttpClient.builder().apiKey(props.llmApiKey()).timeout(Duration.ofMinutes(3)).maxRetries(3);
                    if (props.llmBaseUrl() != null && !props.llmBaseUrl().isBlank()) b.baseUrl(props.llmBaseUrl());
                    client = b.build();
                }
            }
        }
        return client;
    }

    public record Result(ExtractedInvoice invoice, String rawJson, String stopReason, long inputTokens, long outputTokens) {}

    public record EmailClass(
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("One of: invoice, no_attachment, payment_inquiry, statement, not_invoice")
            String category,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("One short sentence explaining the choice")
            String reason) {}

    /** Tiebreaker for emails no keyword rule could classify. Returns empty if the LLM is off or fails. */
    public Optional<EmailClass> classifyEmail(String from, String subject, String body, boolean hasAttachment) {
        if (!enabled()) return Optional.empty();
        String prompt = "Classify this email received by an accounts-payable inbox.\nFrom: " + from + "\nSubject: " + subject
                + "\nHas attachment: " + hasAttachment + "\n<body>\n" + body + "\n</body>";
        StructuredMessageCreateParams<EmailClass> params = MessageCreateParams.builder()
                .model(model()).maxTokens(2000L)
                .system("You triage an accounts-payable mailbox. invoice = a bill is attached; no_attachment = says a bill is attached but none is; "
                        + "payment_inquiry = supplier asks about payment; statement = statement of account; not_invoice = anything else.")
                .outputConfig(EmailClass.class)
                .addUserMessage(prompt)
                .build();
        try {
            return client().messages().create(params).content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst();
        } catch (RuntimeException e) {
            log.warn("LLM email classification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @param text      text layer from pdftotext (may be null for images)
     * @param fileBytes original file, sent as a PDF document block or image block so the model also sees the layout
     * @param mediaType application/pdf, image/png, image/jpeg...
     */
    public Optional<Result> extract(String text, byte[] fileBytes, String mediaType) {
        if (!enabled()) return Optional.empty();
        List<ContentBlockParam> content = new ArrayList<>();
        if (fileBytes != null && fileBytes.length > 0) {
            String b64 = Base64.getEncoder().encodeToString(fileBytes);
            if ("application/pdf".equals(mediaType)) {
                content.add(ContentBlockParam.ofDocument(Base64PdfSource.builder().data(b64).build()));
            } else if (mediaType != null && mediaType.startsWith("image/")) {
                content.add(ContentBlockParam.ofImage(Base64ImageSource.builder().data(b64)
                        .mediaType(Base64ImageSource.MediaType.of(mediaType)).build()));
            }
        }
        StringBuilder prompt = new StringBuilder("Transcribe this document into the schema.");
        if (text != null && !text.isBlank()) {
            prompt.append("\n\nIts extracted text layer (exact characters, reading order) is below; use it to copy numbers exactly, "
                    + "and the document itself for layout:\n<text_layer>\n").append(text).append("\n</text_layer>");
        }
        content.add(ContentBlockParam.ofText(prompt.toString()));

        StructuredMessageCreateParams<ExtractedInvoice> params = MessageCreateParams.builder()
                .model(model())
                .maxTokens(16000L)
                .system(SYSTEM)
                .outputConfig(ExtractedInvoice.class)
                .addUserMessageOfContentBlockParams(content)
                // Server-side refusal fallback (routes a refused request to a model that can answer it).
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        try {
            StructuredMessage<ExtractedInvoice> msg = client().messages().create(params);
            String stop = msg.stopReason().map(Object::toString).orElse("unknown");
            if (!"end_turn".equals(stop)) {
                log.warn("LLM extraction stopped with {}", stop);
                if ("refusal".equals(stop) || "max_tokens".equals(stop)) return Optional.of(new Result(null, null, stop, 0, 0));
            }
            ExtractedInvoice inv = msg.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst().orElse(null);
            String raw = inv == null ? null : com.brimap.common.Json.write(inv);
            return Optional.of(new Result(inv, raw, stop, msg.usage().inputTokens(), msg.usage().outputTokens()));
        } catch (UnauthorizedException e) {
            authFailed = true; // wrong/absent key: stop calling, fall back to offline parsing for the rest of the run
            log.error("LLM key rejected (401); falling back to offline extraction");
            throw new ExtractionException("LLM API key rejected (401)", e);
        } catch (AnthropicServiceException e) {
            throw new ExtractionException("LLM API error " + e.statusCode() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ExtractionException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
