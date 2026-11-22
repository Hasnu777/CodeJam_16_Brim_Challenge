package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "documents", indexes = @Index(columnList = "sha256"))
@Getter @Setter @NoArgsConstructor
public class StoredDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String filename;
    private String contentType;
    private String sha256;
    private long sizeBytes;
    private String storagePath;
    private String emailId;
    private String uploadedBy;
    private Instant createdAt;
}
