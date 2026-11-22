package com.brimap.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Static access to the logged-in user for services (controllers can also inject it). */
public final class Auth {
    private Auth() {}

    public static Optional<CurrentUser> current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof CurrentUser u) return Optional.of(u);
        return Optional.empty();
    }

    public static CurrentUser require() {
        return current().orElseThrow(() -> new ForbiddenException("Not logged in"));
    }

    /** Actor id for audit rows; "system" for seeding and background jobs. */
    public static String actorId() {
        return current().map(CurrentUser::id).orElse("system");
    }
}
