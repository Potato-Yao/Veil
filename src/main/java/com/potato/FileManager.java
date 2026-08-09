package com.potato;

import java.io.InputStream;
import java.io.OutputStream;

public abstract class FileManager {
    public abstract FileManager build();

    public abstract void put(String location, InputStream inputStream);

    public abstract OutputStream get(String location);

    public abstract void delete(String location);
}
