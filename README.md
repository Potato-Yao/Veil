# Veil

A Java library for storing and managing files with metadata.

## Usage

```java
import com.potato.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

        // 4. Store an object; the file lands at ./avatar/user123_u1 and a metadata
        //    row is inserted into the veil_metadata_avatar table.
        byte[] data = "hello veil!".getBytes(StandardCharsets.UTF_8);
        avatarManager.overwritePut("user123", "avatar.png",
                new ByteArrayInputStream(data), Map.of("user_id", "u1"));
    }
}
```

## Build & dependency

Requires JDK 17+.

```bash
./gradlew build
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

## PostgreSQL

By default metadata is persisted in SQLite. To use PostgreSQL instead, pass a PostgreSQL
`DataSource` and select `DatabaseType.POSTGRES` when building the `DatabaseManager`:

```java
import com.potato.*;
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
