package com.example.app;

public final class ConfigSingleton {
    private static final ConfigSingleton INSTANCE = new ConfigSingleton();

    private ConfigSingleton() {
    }

    public static ConfigSingleton getInstance() {
        return INSTANCE;
    }

    public String env() {
        return "demo";
    }
}
