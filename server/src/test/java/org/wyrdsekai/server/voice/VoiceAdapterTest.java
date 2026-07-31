package org.wyrdsekai.server.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceAdapterTest {

    private VoiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VoiceAdapter(SttConfig.DEFAULT);
        // Use a mock engine for testing
        adapter.setEngine((frames, config) ->
            new VoiceAdapter.Transcription("test", "look around", "en", 0.95,
                100, Instant.now()));
    }

    @Test void start_session() {
        adapter.startSession("session-1");
        assertThat(adapter.getState("session-1")).isEqualTo(VoiceAdapter.SessionState.IDLE);
        assertThat(adapter.activeSessionCount()).isEqualTo(1);
    }

    @Test void begin_listening() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        assertThat(adapter.getState("session-1")).isEqualTo(VoiceAdapter.SessionState.LISTENING);
    }

    @Test void process_frame_returns_listening() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        var result = adapter.processFrame("session-1", new byte[1024]);
        assertThat(result.transcriptionReady()).isFalse();
        assertThat(result.newState()).isEqualTo(VoiceAdapter.SessionState.LISTENING);
    }

    @Test void finish_transcription() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        adapter.processFrame("session-1", new byte[1024]);
        var result = adapter.finishTranscription("session-1");
        assertThat(result.transcriptionReady()).isTrue();
        assertThat(result.text()).isEqualTo("look around");
        assertThat(result.newState()).isEqualTo(VoiceAdapter.SessionState.IDLE);
    }

    @Test void transcription_count() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        adapter.processFrame("session-1", new byte[100]);
        adapter.finishTranscription("session-1");
        assertThat(adapter.transcriptionCount()).isEqualTo(1);
    }

    @Test void recent_transcriptions() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        adapter.processFrame("session-1", new byte[100]);
        adapter.finishTranscription("session-1");
        var recent = adapter.recentTranscriptions(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().text()).isEqualTo("look around");
    }

    @Test void end_session() {
        adapter.startSession("session-1");
        adapter.endSession("session-1");
        assertThat(adapter.activeSessionCount()).isEqualTo(0);
    }

    @Test void config_accessible() {
        assertThat(adapter.config()).isEqualTo(SttConfig.DEFAULT);
    }

    @Test void empty_buffer_no_transcription() {
        adapter.startSession("session-1");
        adapter.beginListening("session-1");
        // No frames added
        var result = adapter.finishTranscription("session-1");
        assertThat(result.transcriptionReady()).isFalse();
    }
}
