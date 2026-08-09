package com.potato;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} that counts the number of bytes read.
 *
 * <p>Used internally while storing objects so the exact size can be recorded without a
 * separate pass over the data.</p>
 */
public class CountingInputStream extends FilterInputStream {
    private long count;

    public CountingInputStream(InputStream in) {
        super(in);
    }

    /**
     * Reads a single byte, incrementing the counter unless end-of-stream is reached.
     *
     * @return the byte read, or {@code -1} if the end of the stream has been reached
     * @throws IOException if an I/O error occurs
     */
    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            count++;
        }
        return b;
    }

    /**
     * Reads up to {@code len} bytes, incrementing the counter by the number of bytes
     * actually read.
     *
     * @param b    the buffer into which the data is read
     * @param off  the start offset in the buffer
     * @param len  the maximum number of bytes to read
     * @return the number of bytes read, or {@code -1} if the end of the stream has been
     *         reached
     * @throws IOException if an I/O error occurs
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n != -1) {
            count += n;
        }
        return n;
    }

    /**
     * @return the number of bytes read from this stream so far
     */
    public long getByteCount() {
        return count;
    }
}
