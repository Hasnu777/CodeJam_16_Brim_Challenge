package com.brimap.intake.mail;

import com.brimap.config.MailProperties;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Talks real IMAP to an in-process GreenMail server: login, UNSEEN search, mark SEEN only after a successful save. */
class ImapMailboxTest {
    private GreenMail server;
    private ImapMailbox mailbox;

    @BeforeEach
    void start() {
        server = new GreenMail(ServerSetupTest.SMTP_IMAP);
        server.start();
        server.setUser("ap@harborline.test", "ap@harborline.test", "secret");
        var props = new MailProperties(true, "127.0.0.1", ServerSetupTest.IMAP.getPort(), "ap@harborline.test", "secret",
                "INBOX", false, 10, false, null);
        mailbox = new ImapMailbox(props);
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void readsUnseenOnceAndMarksThemSeen() throws Exception {
        GreenMailUtil.sendTextEmail("ap@harborline.test", "ar@vendor.test", "Invoice 1", "Please find attached our invoice 1.", ServerSetupTest.SMTP);
        GreenMailUtil.sendTextEmail("ap@harborline.test", "ar@vendor.test", "Invoice 2", "Please find attached our invoice 2.", ServerSetupTest.SMTP);

        List<String> subjects = new ArrayList<>();
        int read = mailbox.readUnseen(m -> {
            try { subjects.add(MailParser.parse(m).subject()); return true; } catch (Exception e) { return false; }
        });
        assertThat(read).isEqualTo(2);
        assertThat(subjects).containsExactlyInAnyOrder("Invoice 1", "Invoice 2");

        assertThat(mailbox.readUnseen(m -> true)).isZero(); // already marked SEEN
    }

    @Test
    void messageStaysUnseenWhenSavingFails() throws Exception {
        GreenMailUtil.sendTextEmail("ap@harborline.test", "ar@vendor.test", "Invoice 3", "body", ServerSetupTest.SMTP);
        assertThat(mailbox.readUnseen(m -> false)).isEqualTo(1); // handler failed
        assertThat(mailbox.readUnseen(m -> true)).isEqualTo(1);  // so it is offered again
    }
}
