package com.potato;

/**
 * Immutable metadata describing a stored object.
 *
 * @param fileName         the stored file name
 * @param fileExtension    the file extension, without the leading dot
 * @param fileSize         the size of the file in bytes
 * @param md5              the MD5 digest of the file contents in hex
 * @param createdAt        ISO-8601 timestamp of creation
 * @param lastAccessedAt   ISO-8601 timestamp of last access, or {@code null}
 * @param storageType      the type of storage (e.g. {@code "DISK"})
 * @param storageLocation  the location of the file within the file manager
 * @param accessCount      the number of times the object has been accessed
 */
public record ObjectMetadata(
        String fileName,
        String fileExtension,
        long fileSize,
        String md5,
        String createdAt,
        String lastAccessedAt,
        String storageType,
        String storageLocation,
        long accessCount) {
}
