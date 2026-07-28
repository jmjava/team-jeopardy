package com.example.app;

import com.example.api.BaseService;
import com.example.api.GreetingService;

public class AppMain extends BaseService implements GreetingService {
    public static void main(String[] args) {
        System.out.println(new AppMain().greet("Gradle"));
    }

    @Override
    public String name() {
        return "app-main";
    }

    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
