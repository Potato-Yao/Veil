package com.potato;

public class Main {
    public static void main(String[] args) {
        VeilConfiguration veilConfiguration = VeilConfiguration.initForDev();
        SqliteDatabaseManager databaseManager = DatabaseManager.builder()
                .dataSource(veilConfiguration.getDataSource())
                .build();
        ObjectManager avatarManager = ObjectManager.build("avatar", databaseManager);
    }
}
