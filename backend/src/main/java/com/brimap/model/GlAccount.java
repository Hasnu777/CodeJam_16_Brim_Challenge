package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "gl_accounts")
@Getter @Setter @NoArgsConstructor
public class GlAccount {
    @Id private String accountNumber;
    private String name;
    private String accountType;
    private String notes;
}
