package com.potato.util;

/**
 * Validation for namespace identifiers.
 *
 * <p>Namespaces are interpolated into SQL table names
 * ({@code veil_metadata_<namespace>}) and into storage paths, so they are restricted to
 * plain identifier characters: ASCII letters, digits and underscores. Anything else —
 * quotes, semicolons, spaces, hyphens, dots — is rejected before it can reach SQL or
 * the filesystem.</p>
 */
public final class Namespaces {
    private Namespaces() {
    }

    /**
     * @param namespace the namespace to check
     * @return {@code true} if the namespace is a safe identifier
     */
    public static boolean isValid(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * Ensures the given namespace is a safe identifier.
     *
     * @param namespace the namespace to validate
     * @throws IllegalArgumentException if the namespace is not a plain identifier
     */
    public static void requireValid(String namespace) {
        if (!isValid(namespace)) {
            if (namespace == null || namespace.isEmpty()) {
                throw new IllegalArgumentException("Namespace must not be null or empty");
            }
            throw new IllegalArgumentException(
                    "Namespace must only contain letters, digits and underscores: \"" + namespace + "\"");
        }
    }
}
