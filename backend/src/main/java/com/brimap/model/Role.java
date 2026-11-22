package com.brimap.model;

/** The six application roles. The dataset's role strings map onto these. */
public enum Role {
    EMP("employee"), CLK("ap_clerk"), APR("approver"), FM("finance_manager"), ADM("controller_admin"), AUD("auditor");

    public final String datasetName;

    Role(String datasetName) { this.datasetName = datasetName; }

    public static Role fromDataset(String s) {
        for (Role r : values()) if (r.datasetName.equalsIgnoreCase(s.trim())) return r;
        throw new IllegalArgumentException("Unknown role " + s);
    }
}
