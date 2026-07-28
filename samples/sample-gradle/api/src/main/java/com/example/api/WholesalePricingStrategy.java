package com.example.api;

public class WholesalePricingStrategy implements PricingStrategy {
    @Override
    public int priceCents(String sku) {
        return 750;
    }
}
