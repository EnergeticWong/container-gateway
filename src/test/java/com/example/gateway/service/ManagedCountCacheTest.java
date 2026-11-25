package com.example.gateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedCountCacheTest {

    @Test
    void refreshStoresCount() {
        ContainerService service = mock(ContainerService.class);
        when(service.managedCount()).thenReturn(7L);
        ManagedCountCache cache = new ManagedCountCache(service, new com.example.gateway.metrics.GatewayMetrics());

        cache.refresh();
        assertEquals(7L, cache.current());
    }

    @Test
    void refreshFailureKeepsLastSuccessfulValue() {
        ContainerService service = mock(ContainerService.class);
        when(service.managedCount()).thenReturn(5L);
        ManagedCountCache cache = new ManagedCountCache(service, new com.example.gateway.metrics.GatewayMetrics());
        cache.refresh();

        doThrow(new RuntimeException("node unreachable")).when(service).managedCount();
        cache.refresh();
        assertEquals(5L, cache.current());
    }
}
