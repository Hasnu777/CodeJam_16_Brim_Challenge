package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "flag_dictionary")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class FlagDictionary {
    @Id private String code;
    private String category;
    private String title;
    @Enumerated(EnumType.STRING) private Severity severity;
    @Column(columnDefinition = "text") private String explanation;
}
