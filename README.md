# Veil

A Java library for storing and managing files with metadata.

## Usage

```java
import com.potato.VeilConfiguration;
import com.potato.database.DatabaseManager;
import com.potato.database.KeyType;
import com.potato.object.ObjectData;
import com.potato.object.ObjectManager;
import com.potato.object.ObjectReference;
import com.potato.object.ObjectStatement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        // 1. Initialize the global configuration (once per process).
        VeilConfiguration configuration = VeilConfiguration.initForDev();

        // 2. Build a database manager, declaring any additional key columns.
        DatabaseManager databaseManager = DatabaseManager.builder()
                .dataSource(configuration.getDataSource())
                .keyColumn("user_id", KeyType.TEXT)
                .build();

        // 3. Create an object manager for a namespace, restricting accepted file types.
        ObjectManager avatarManager = ObjectManager.builder()
                .namespace("avatar")
                .databaseManager(databaseManager)
                .allowExtension("png", "jpg", ".jpeg")
                .build();

        // 3b. A text-mode manager skips the extension check entirely.
        ObjectManager noteManager = ObjectManager.builder()
                .namespace("notes")
                .databaseManager(databaseManager)
                .textMode(true)
                .build();

        // 4. Store objects; each file lands under ./avatar/<h1>/<h2>/<objectId>.<extension>
        //    (e.g. ./avatar/7a/31/fb7a3110-...-1234.png), where h1/h2 are a hash-based
        //    fan-out and the objectId is an opaque UUID that never comes from the key. A
        //    metadata row is inserted into the veil_metadata_avatar table. A statement
        //    carries the primary key and any additional key values. The sample image is
        //    the test fixture at src/test/resources/a.png.
        byte[] avatarBytes = readImage();
        ObjectStatement avatar = ObjectStatement.builder().key("user123").kv("user_id", "u1").build();
        avatarManager.update(avatar, "avatar.png", new ByteArrayInputStream(avatarBytes));
        avatarManager.update(ObjectStatement.builder().key("user456").kv("user_id", "u1").build(),
                "banner.png",
                new ByteArrayInputStream(avatarBytes));

        // 4b. Text mode stores any extension; a plain ObjectManager.build(...) keeps the
        //     old behavior and accepts every file type.
        ObjectStatement note = ObjectStatement.builder().key("greeting").kv("user_id", "u1").build();
        noteManager.update(note, "hello.txt", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        System.out.println("Stored note: " + noteManager.checkExist(note));

        // 4c. Every built manager is registered by namespace; retrieve it at any time.
        ObjectManager sameAvatar = ObjectManager.getInstance("avatar");
        System.out.println("getInstance(\"avatar\") is the built manager: " + (sameAvatar == avatarManager));
        System.out.println("getInstance(\"missing\") returns null: " + (ObjectManager.getInstance("missing") == null));
        System.out.println("Avatar check via registered manager: " + sameAvatar.checkExist(avatar));

        // 5. Retrieve an object: metadata plus a stream of its contents. Each get()
        //    records access statistics in memory; they are flushed to the database
        //    in periodic batches rather than with a synchronous write per read.
        ObjectData object = avatarManager.get(avatar);
        try (InputStream stream = object.stream()) {
            byte[] content = stream.readAllBytes();
            System.out.println("Retrieved " + object.metadata().fileName() + " (" + object.metadata().fileSize() + " bytes)");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        // 6. Query the namespace with range and condition filters, ordered by size.
        ObjectStatement statement = ObjectStatement.builder()
                .where("file_extension", ObjectStatement.Op.EQ, "png")
                .between("file_size", 1L, 100L)
                .orderBy("file_size", ObjectStatement.Direction.DESC)
                .build();
        List<ObjectReference> matches = avatarManager.query(statement);
        System.out.println("PNG files between 1 and 100 bytes: " + matches.size());
        System.out.println("Total PNGs in namespace: " + avatarManager.count(statement));

        // 7. Query results are addressable: feed key + kv back into get().
        if (!matches.isEmpty()) {
            ObjectReference first = matches.get(0);
            try (ObjectData result = avatarManager.get(ObjectStatement.builder()
                    .key(first.key()).kv(first.kv()).build())) {
                System.out.println("Largest match: " + result.metadata().fileName()
                        + " accessed " + result.metadata().accessCount() + " time(s)");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 8. Partially update metadata without rewriting the file content.
        avatarManager.updateMetadata(ObjectStatement.builder()
                .key("user123").kv("user_id", "u1")
                .set("file_name", "avatar-v2.png").build());

        // 9. Check existence and remove an object.
        System.out.println("Exists before remove: " + avatarManager.checkExist(avatar));
        avatarManager.remove(avatar);
        System.out.println("Exists after remove: " + avatarManager.checkExist(avatar));
    }

    private static byte[] readImage() {
        try {
            return Files.readAllBytes(Path.of("src/test/resources/a.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

## Build & dependency

Requires JDK 17+.

```bash
./gradlew build
```

To run the example application in `src/examples`:

```bash
./gradlew runExample
```

To publish a release to Maven Central (see below for the required credentials):

```bash
./gradlew publishAggregationToCentralPortal
```

### Gradle

```kotlin
dependencies {
    implementation("io.github.potato-yao:veil:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.potato-yao</groupId>
    <artifactId>veil</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Publishing setup

Publishing targets the Central Portal at `central.sonatype.com` and requires the
following one-time setup:

1. Sign in to [central.sonatype.com](https://central.sonatype.com) with your GitHub
   account; the `io.github.potato-yao` namespace is provisioned automatically.
2. Generate a Portal User Token (top-right menu -> "Generate User Token").
3. Create a GPG RSA-4096 key and upload its public key
   (`gpg --keyserver keys.openpgp.org --send-key <KEY_ID>`).

Then configure credentials and signing in `~/.gradle/gradle.properties` (never commit
secrets):

```properties
centralPortalUsername=<portal token username>
centralPortalPassword=<portal token password>
signing.gnupg.keyName=<GPG KEY_ID>
signing.gnupg.passphrase=<GPG passphrase>
signing.gnupg.executable=gpg
```

> **Note:** `VeilConfiguration` must be initialized before any `ObjectManager` is built.

## Object identity

An object's identity is its primary key together with every additional key column
value declared on the `DatabaseManager`. With `user_id` as an additional key,
`("user123", "u1")` and `("user123", "u2")` are two distinct objects. Every
single-object operation (`get`, `update`, `remove`, `checkExist`,
`updateMetadata`) must carry the full identity; a missing or mismatched additional
key value addresses a different (possibly absent) object.

The identity is immutable: `update()` replaces the content and metadata of the
object with that identity (preserving its access statistics and creation time), or creates a new
object if the identity does not exist yet. To move an object to a different key,
remove it and store it again.

## Concurrency

Mutations of the same object (`put`, `update`, `remove`, `updateMetadata`, `get`) are
serialized through a bounded striped lock, so concurrent operations on one key never
interleave while distinct keys run in parallel.

Access statistics (`access_count` and `last_accessed_at`) are accumulated in memory
when objects are read and flushed to the database in batched transactions about once
per second, so reads do not perform a synchronous database write. Call
`DatabaseManager.flushAccessStats()` to force a flush, for example before reading the
statistics directly or before shutting down (`DatabaseManager` implements
`AutoCloseable`; `close()` performs a final best-effort flush and stops the flusher).
Statistics are eventually consistent: unflushed deltas are lost if the JVM crashes.

Every `DatabaseManager` operation opens one connection from the supplied `DataSource`.
For production you should provide a pooled `DataSource` (for example HikariCP) rather
than a bare one. SQLite is a single-writer engine and is intended for development;
`initForDev()` configures its data source with a busy timeout and WAL journal mode so
concurrent writers wait instead of failing. Use PostgreSQL for production workloads.

## PostgreSQL

By default metadata is persisted in SQLite. To use PostgreSQL instead, pass a PostgreSQL
`DataSource` and select `DatabaseType.POSTGRES` when building the `DatabaseManager`:

```java
import com.potato.database.DatabaseManager;
import com.potato.database.DatabaseType;
import org.postgresql.ds.PGSimpleDataSource;

PGSimpleDataSource dataSource = new PGSimpleDataSource();
dataSource.setUrl("jdbc:postgresql://localhost:5432/veil");
dataSource.setUser("veil");
dataSource.setPassword("secret");

DatabaseManager databaseManager = DatabaseManager.builder()
        .dataSource(dataSource)
        .databaseType(DatabaseType.POSTGRES)
        .keyColumn("user_id", KeyType.TEXT)
        .build();
```

The `org.postgresql:postgresql` driver is bundled with the library, so no extra
dependency is required to use it.
