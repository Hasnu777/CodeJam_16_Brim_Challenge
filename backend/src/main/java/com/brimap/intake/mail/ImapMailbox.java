package com.brimap.intake.mail;

import com.brimap.config.MailProperties;
import jakarta.mail.*;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.function.Predicate;

/**
 * The only class that talks to the mail server. Reads UNSEEN messages from one folder and marks a message SEEN only
 * after the caller has saved it, so a crash mid-fetch re-reads the message next time (the Message-ID check stops a
 * double save).
 */
@Component
@RequiredArgsConstructor
public class ImapMailbox {
    private final MailProperties props;

    /** Called once per unseen message; return true once it is safely stored, and the message is then marked SEEN. */
    public interface Handler extends Predicate<Message> {}

    public int readUnseen(Handler handler) throws MessagingException {
        Properties p = new Properties();
        String protocol = props.sslOrDefault() ? "imaps" : "imap";
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".host", props.host());
        p.put("mail." + protocol + ".port", String.valueOf(props.portOrDefault()));
        p.put("mail." + protocol + ".connectiontimeout", "15000");
        p.put("mail." + protocol + ".timeout", "30000");
        // Never send the password in clear text, except to a server on this machine (local test servers).
        if (!props.sslOrDefault() && !loopback(props.host())) p.put("mail.imap.starttls.required", "true");
        Session session = Session.getInstance(p);

        try (Store store = session.getStore(protocol)) {
            store.connect(props.host(), props.portOrDefault(), props.username(), props.password());
            Folder folder = store.getFolder(props.folderOrDefault());
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unseen = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                int n = 0;
                for (Message m : unseen) {
                    if (n >= props.maxPerFetchOrDefault()) break;
                    if (handler.test(m)) m.setFlag(Flags.Flag.SEEN, true);
                    n++;
                }
                return n;
            } finally {
                folder.close(false);
            }
        }
    }

    static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
