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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

        // 3. Create an object manager for a namespace.
        ObjectManager avatarManager = ObjectManager.build("avatar", databaseManager);

        // 4. Store objects; each file lands at ./avatar/<key>_<user_id> and a metadata
        //    row is inserted into the veil_metadata_avatar table. A statement carries
        //    the primary key and any additional key values. update() replaces an
        //    existing object, preserving its access statistics.
        byte[] data = "hello veil!".getBytes(StandardCharsets.UTF_8);
        ObjectStatement avatar = ObjectStatement.builder().key("user123").kv("user_id", "u1").build();
        avatarManager.update(avatar, "avatar.png", new ByteArrayInputStream(data));
        avatarManager.put(ObjectStatement.builder().key("user456").kv("user_id", "u1").build(),
                "banner.png",
                new ByteArrayInputStream("a longer banner image".getBytes(StandardCharsets.UTF_8)));

        // 5. Retrieve an object: metadata plus a stream of its contents. Each get()
        //    records last_accessed_at and increments access_count.
        ObjectData object = avatarManager.get(avatar);
        try (InputStream stream = object.stream()) {
            byte[] content = stream.readAllBytes();
            System.out.println("Retrieved " + object.metadata().fileName() + " (" + object.metadata().fileSize() + " bytes)");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        // 6. Query the namespace with range and condition filters, ordered by size.
        //    The same ObjectStatement type also drives updates and batch deletes.
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

To publish to a Maven repository of your choice:

```bash
./gradlew publish
```

### Gradle

```kotlin
dependencies {
    implementation("com.potato:Veil:1.0-SNAPSHOT")
}
```

### Maven

```xml
<dependency>
    <groupId>com.potato</groupId>
    <artifactId>Veil</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
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
object with that identity (preserving its access statistics), or creates a new
object if the identity does not exist yet. To move an object to a different key,
remove it and store it again.

## Concurrency

Mutations of the same object (`put`, `update`, `remove`, `updateMetadata`, `get`) are
serialized through a bounded striped lock, so concurrent operations on one key never
interleave while distinct keys run in parallel.

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
