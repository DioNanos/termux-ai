package mcpharness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Full-duplex streams over a blocking SocketChannel. java.nio.channels.Channels.newInputStream/
 * newOutputStream hold the channel's blockingLock for the whole blocking call, so a thread blocked
 * in read would stall every write: unlike the platform's LocalSocket streams. These go straight to
 * the channel, whose read and write have independent locks.
 */
public final class ChannelStreams {
    private ChannelStreams() {}

    public static InputStream in(final SocketChannel ch) {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                return ch.read(ByteBuffer.wrap(b, off, len));
            }
        };
    }

    public static OutputStream out(final SocketChannel ch) {
        return new OutputStream() {
            @Override public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                while (bb.hasRemaining()) ch.write(bb);
            }
        };
    }
}
