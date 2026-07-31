package org.wyrdsekai.server.session;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.server.session.ClientSessionActor.Report;
import org.wyrdsekai.server.session.ClientSessionActor.VoiceTranscription;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSessionHandlerTest {

    @Test void report_fields() {
        var report = new Report("evil-agent-42", "spam", "room-nexus");
        assertThat(report.targetEntity()).isEqualTo("evil-agent-42");
        assertThat(report.reason()).isEqualTo("spam");
        assertThat(report.roomId()).isEqualTo("room-nexus");
    }

    @Test void report_implements_session_message() {
        var report = new Report("target", "reason", "room");
        assertThat(report).isInstanceOf(ClientSessionActor.SessionMessage.class);
    }

    @Test void voice_transcription_fields() {
        var vt = new VoiceTranscription("session-abc", "look around", "room-kitchen", "Alice");
        assertThat(vt.sessionId()).isEqualTo("session-abc");
        assertThat(vt.transcribedText()).isEqualTo("look around");
        assertThat(vt.roomId()).isEqualTo("room-kitchen");
        // The speaker's display name rides along because the actor has no name
        // of its own: without it the room rendered the raw sessionId as the
        // speaker (a UUID where a person's name belongs).
        assertThat(vt.speakerName()).isEqualTo("Alice");
    }

    @Test void voice_transcription_implements_session_message() {
        var vt = new VoiceTranscription("s1", "hello world", "r1", "Bob");
        assertThat(vt).isInstanceOf(ClientSessionActor.SessionMessage.class);
    }
}
