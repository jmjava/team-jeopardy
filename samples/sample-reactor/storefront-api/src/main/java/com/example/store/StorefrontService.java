package com.example.store;

import com.example.api.PlatformService;
import com.example.api.PlatformView;
import java.time.Instant;
import java.util.List;

public class StorefrontService implements PlatformService {
    @Override
    public PlatformView findCustomer(String id) {
        List<String> ids = List.of(id);
        return new PlatformView(ids.get(0) + Instant.EPOCH);
    }
}
