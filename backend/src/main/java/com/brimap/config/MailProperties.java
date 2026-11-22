package com.brimap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live AP mailbox over IMAP. Off unless {@code enabled} is true; the dataset's emails.json keeps working either way.
 *
 * @param enabled     read from the mail server at all
 * @param host        IMAP host, e.g. imap.gmail.com, outlook.office365.com
 * @param port        993 for IMAPS (default)
 * @param username    mailbox login (the AP address)
 * @param password    app password from the provider; set in .env only
 * @param folder      folder to read, default INBOX
 * @param ssl         IMAPS (TLS from the start), default true
 * @param maxPerFetch cap on messages read per fetch, default 50
 * @param autoPoll    also fetch on a timer (every {@code pollMs}); new mail still waits for "Process inbox"
 * @param pollMs      timer interval in milliseconds, default 60000
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(boolean enabled, String host, Integer port, String username, String password, String folder,
                             Boolean ssl, Integer maxPerFetch, boolean autoPoll, Long pollMs) {
    public int portOrDefault() { return port == null ? (sslOrDefault() ? 993 : 143) : port; }
    public boolean sslOrDefault() { return ssl == null || ssl; }
    public String folderOrDefault() { return folder == null || folder.isBlank() ? "INBOX" : folder; }
    public int maxPerFetchOrDefault() { return maxPerFetch == null || maxPerFetch <= 0 ? 50 : maxPerFetch; }

    public boolean configured() {
        return enabled && notBlank(host) && notBlank(username) && notBlank(password);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
