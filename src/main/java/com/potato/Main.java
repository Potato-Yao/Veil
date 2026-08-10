package com.potato;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

        // 4. Store objects; each file lands at ./avatar/<key>_<user_id> and a metadata
        //    row is inserted into the veil_metadata_avatar table.
        byte[] data = "hello veil!".getBytes(StandardCharsets.UTF_8);
        avatarManager.update("user123", "avatar.png", new ByteArrayInputStream(data), Map.of("user_id", "u1"));
        avatarManager.put("user456", "banner.png",
                new ByteArrayInputStream("a longer banner image".getBytes(StandardCharsets.UTF_8)),
                Map.of("user_id", "u1"));

        // 5. Retrieve an object: metadata plus a stream of its contents. Each get()
        //    records last_accessed_at and increments access_count.
        ObjectData object = avatarManager.get("user123", Map.of("user_id", "u1"));
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

        // 7. Query results are addressable: feed key + addition keys back into get().
        if (!matches.isEmpty()) {
            ObjectReference first = matches.get(0);
            try (ObjectData result = avatarManager.get(first.key(), first.additionKeys())) {
                System.out.println("Largest match: " + result.metadata().fileName()
                        + " accessed " + result.metadata().accessCount() + " time(s)");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 8. Partially update metadata without rewriting the file content.
        avatarManager.updateMetadata("user123", Map.of("user_id", "u1"),
                ObjectStatement.builder().set("file_name", "avatar-v2.png").build());

        // 9. Check existence and remove an object.
        System.out.println("Exists before remove: " + avatarManager.checkExist("user123", Map.of("user_id", "u1")));
        avatarManager.remove("user123", Map.of("user_id", "u1"));
        System.out.println("Exists after remove: " + avatarManager.checkExist("user123", Map.of("user_id", "u1")));

        // 10. Batch-remove every remaining object in the namespace.
        System.out.println("Removed " + avatarManager.removeAll(ObjectStatement.builder().build()) + " remaining object(s)");
    }
}
