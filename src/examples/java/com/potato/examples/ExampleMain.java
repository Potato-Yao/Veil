package com.potato.examples;

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

public class ExampleMain {
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
        //    records last_accessed_at and increments access_count.
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
