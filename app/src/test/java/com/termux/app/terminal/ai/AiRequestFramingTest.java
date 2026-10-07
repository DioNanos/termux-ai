package com.termux.app.terminal.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AiRequestFramingTest {

    /** A peer that sent {@code data} and keeps its side open: reading past it times out. */
    static final class OpenPeer implements AiRequestFraming.ByteSource {
        final byte[] data;
        final boolean closes;
        int position;
        int reads;

        OpenPeer(byte[] data, boolean closes) {
            this.data = data;
            this.closes = closes;
        }

        @Override public int read(byte[] buffer) throws IOException {
            reads++;
            if (position < data.length) {
                buffer[0] = data[position++];
                return 1;
            }
            if (closes) return -1;
            throw new IOException("Read failed: EAGAIN (receive timeout)");
        }
    }

    private static OpenPeer peer(String text, boolean closes) {
        return new OpenPeer(text.getBytes(StandardCharsets.UTF_8), closes);
    }

    private static AiRequestFraming.Kind kindOf(AiRequestFraming.ByteSource source, int max) {
        try {
            AiRequestFraming.readFrame(source, max);
        } catch (AiRequestFraming.FrameException e) {
            return e.kind;
        }
        fail("expected a FrameException");
        return null;
    }

    @Test
    public void frameEndsAtNewlineAndNothingIsReadAfterIt() throws Exception {
        String frame = "{\"cmd\":\"ping\"}";
        OpenPeer peer = peer(frame + "\n", false);
        assertEquals(frame, AiRequestFraming.readFrame(peer, 1024));
        assertEquals("exactly the frame and its newline were read", frame.length() + 1, peer.reads);
    }

    @Test
    public void aPeerThatNeverClosesDoesNotBlockTheFrame() throws Exception {
        // The read after the newline would time out: the frame must not need it.
        String frame = AiRequestFraming.readFrame(peer("x\n", false), 1024);
        assertEquals("x", frame);
    }

    @Test
    public void decodesMultibyteUtf8OnlyOnceTheFrameIsComplete() throws Exception {
        String text = "{\"p\":\"è 😀 日本語\"}";
        assertEquals(text, AiRequestFraming.readFrame(peer(text + "\n", false), 1024));
    }

    @Test
    public void aFrameClosedWithoutNewlineIsAccepted() throws Exception {
        assertEquals("{\"cmd\":\"ping\"}", AiRequestFraming.readFrame(peer("{\"cmd\":\"ping\"}", true), 1024));
    }

    @Test
    public void aBareNewlineIsAnEmptyFrame() throws Exception {
        assertEquals("", AiRequestFraming.readFrame(peer("\n", false), 1024));
    }

    @Test
    public void nothingAtAllIsAnEmptyRequestError() {
        assertEquals(AiRequestFraming.Kind.EMPTY, kindOf(peer("", true), 1024));
    }

    @Test
    public void silenceWithoutNewlineIsAnIoErrorNotAnEmptyFrame() {
        assertEquals(AiRequestFraming.Kind.IO, kindOf(peer("{\"cmd\":\"aicore.gen", false), 1024));
    }

    @Test
    public void aFrameOverTheLimitIsRefusedAndAFrameAtTheLimitIsNot() throws Exception {
        assertEquals("12345678", AiRequestFraming.readFrame(peer("12345678\n", false), 8));
        assertEquals(AiRequestFraming.Kind.TOO_LARGE, kindOf(peer("123456789\n", false), 8));
    }

    @Test
    public void theLimitIsInBytesNotCharacters() {
        // four 2-byte characters are 8 bytes: five are 10 bytes
        assertEquals(AiRequestFraming.Kind.TOO_LARGE, kindOf(peer("èèèèè\n", false), 8));
    }

    @Test
    public void invalidUtf8IsRefused() {
        byte[] bad = {(byte) 0xC3, 0x28, '\n'};
        assertEquals(AiRequestFraming.Kind.INVALID_UTF8, kindOf(new OpenPeer(bad, false), 1024));
    }

    @Test
    public void defaultLimitIsOneMebibyte() {
        assertEquals(1024 * 1024, AiRequestFraming.MAX_REQUEST_BYTES);
        assertTrue(AiRequestFraming.MAX_REQUEST_BYTES > 64 * 1024);
    }
}
