package com.brimap.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/** A time-bounded delegation: approvals assigned to fromUser go to toUser between start and end (inclusive). */
@Entity @Table(name = "delegations")
@Getter @Setter @NoArgsConstructor
public class Delegation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String fromUserId;
    private String toUserId;
    private LocalDate startDate;
    /** Null means open-ended (until the person returns). */
    private LocalDate endDate;
    private String reason;

    public boolean activeOn(LocalDate d) {
        return !d.isBefore(startDate) && (endDate == null || !d.isAfter(endDate));
    }
}
