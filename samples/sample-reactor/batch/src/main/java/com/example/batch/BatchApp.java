package com.example.batch;

import java.util.concurrent.Executors;

public class BatchApp {
    public void start() {
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> System.out.println("batch-ready"));
    }
}
