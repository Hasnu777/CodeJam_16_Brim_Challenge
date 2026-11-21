package com.brimap.auth;

import com.brimap.model.AppUser;
import com.brimap.model.Role;

import java.io.Serializable;
import java.math.BigDecimal;

/** The principal stored in the HTTP session. Re-read from the DB for anything limit- or status-sensitive. */
public record CurrentUser(String id, String fullName, Role role, String departmentId, BigDecimal approvalLimitCad)
        implements Serializable {

    public static CurrentUser of(AppUser u) {
        return new CurrentUser(u.getId(), u.getFullName(), u.getRole(), u.getDepartmentId(), u.getApprovalLimitCad());
    }

    public boolean is(Role... roles) {
        for (Role r : roles) if (r == role) return true;
        return false;
    }
}
