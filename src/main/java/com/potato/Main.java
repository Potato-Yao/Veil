package com.potato;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        VeilConfiguration veilConfiguration = VeilConfiguration.initForDev();
        DatabaseManager databaseManager = DatabaseManager.builder()
                .dataSource(veilConfiguration.getDataSource())
                .keyColumn("user_id", KeyType.TEXT)
                .build();
        ObjectManager avatarManager = ObjectManager.build("avatar", databaseManager);

        byte[] data = "hello veil!".getBytes(StandardCharsets.UTF_8);
        avatarManager.overwritePut("user123", "avatar.png", new ByteArrayInputStream(data), Map.of("user_id", "u1"));
        System.out.println("Stored to ./avatar/user123_u1 and inserted a row into veil_metadata_avatar");

        ObjectData object = avatarManager.get("user123", Map.of("user_id", "u1"));
        try (InputStream stream = object.stream()) {
            byte[] content = stream.readAllBytes();
            System.out.println("Retrieved " + object.metadata().fileName() + " (" + object.metadata().fileSize() + " bytes): " + new String(content));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

//        System.out.println("Exists before remove: " + avatarManager.checkExist("user123", Map.of("user_id", "u1")));
//        avatarManager.remove("user123", Map.of("user_id", "u1"));
//        System.out.println("Exists after remove: " + avatarManager.checkExist("user123", Map.of("user_id", "u1")));
    }
}
