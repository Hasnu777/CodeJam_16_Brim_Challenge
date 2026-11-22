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
    /** Admin switch (Policies > Validation Rules). Null on rows seeded before the column existed = on (D-108). */
    private Boolean enabled;

    public boolean isOn() { return !Boolean.FALSE.equals(enabled); }
}
