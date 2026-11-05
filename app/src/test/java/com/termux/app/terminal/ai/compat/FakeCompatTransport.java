package com.termux.app.terminal.ai.compat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Controlled transport peer for the JVM adapter tests: records the wire line
 *  and answers from canned bytes, or fails as instructed (no retry proof).
 *  It deliberately simulates NO timing of its own: the deadline behaviour is
 *  proven on the production loop in LocalSocketCompatTransportTest. */
final class FakeCompatTransport implements CompatTransport {
    String lastRequest;
    int exchanges;
    byte[] response;
    CompatTransportException failure;

    FakeCompatTransport answering(String response) {
        this.response = response.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    FakeCompatTransport failing(CompatTransportException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public byte[] exchange(String requestLine, CompatClock clock) throws CompatTransportException {
        lastRequest = requestLine;
        exchanges++;
        if (failure != null) throw failure;
        return response;
    }

    static ByteArrayInputStream noStdin() {
        return new ByteArrayInputStream(new byte[0]);
    }

    static ByteArrayInputStream stdin(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
