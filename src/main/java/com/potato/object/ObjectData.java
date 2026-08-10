package com.potato.object;

import java.io.IOException;
import java.io.InputStream;

/**
 * A stored object: its metadata together with a stream of its contents.
 *
 * <p>Closing the {@link ObjectData} closes its {@link #stream()}. It may be used in a
 * try-with-resources block.</p>
 *
 * @param metadata  the object's metadata
 * @param stream    the object's raw content
 */
public record ObjectData(ObjectMetadata metadata, InputStream stream) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
