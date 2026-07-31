package org.wyrdsekai.daemon.desktop;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.daemon.common.DaemonConfig;
import org.wyrdsekai.daemon.common.InferenceRequest;
import org.wyrdsekai.daemon.common.InferenceResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceProcessTest {

    @Test
    void forwardRequest_noBackend_returnsError() {
        var config = new DaemonConfig();
        config.setInferencePort(19999); // port with nothing listening

        var process = new InferenceProcess(config);

        var request = new InferenceRequest(
            "test-001", "test-model",
            List.of(new InferenceRequest.ChatMessage("user", "Hello")),
            64, 0.7
        );

        var response = process.forwardRequest(request);

        assertThat(response.hasError()).isTrue();
        assertThat(response.requestId()).isEqualTo("test-001");
    }

    @Test
    void backendName_nullBeforeStart() {
        var config = new DaemonConfig();
        var process = new InferenceProcess(config);
        assertThat(process.backendName()).isNull();
    }
}
