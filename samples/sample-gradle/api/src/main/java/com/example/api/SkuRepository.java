package com.example.api;

import java.util.Optional;

public interface SkuRepository {
    Optional<String> findName(String sku);
}
