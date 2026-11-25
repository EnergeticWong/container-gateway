package com.example.gateway.service;

import com.example.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CreateIdempotencyStoreTest {

    private CreateIdempotencyStore store(long ttlSeconds) {
        GatewayProperties props = new GatewayProperties();
        props.setCreateIdempotencyTtlSeconds(ttlSeconds);
        return new CreateIdempotencyStore(props);
    }

    @Test
    void lookupMissThenHit() {
        CreateIdempotencyStore s = store(600);
        assertNull(s.lookup("c1", "k1"));
        s.remember("c1", "k1", "container-a");
        assertEquals("container-a", s.lookup("c1", "k1"));
    }

    @Test
    void keysAreScopedByClient() {
        CreateIdempotencyStore s = store(600);
        s.remember("c1", "k1", "container-a");
        assertNull(s.lookup("c2", "k1"));
    }

    @Test
    void forgetRemovesEntry() {
        CreateIdempotencyStore s = store(600);
        s.remember("c1", "k1", "container-a");
        s.forget("c1", "k1");
        assertNull(s.lookup("c1", "k1"));
    }

    @Test
    void lookupEntryCarriesRequestDigest() {
        CreateIdempotencyStore s = store(600);
        s.remember("c1", "k1", "container-a", "digest-xyz");
        CreateIdempotencyStore.Lookup e = s.lookupEntry("c1", "k1");
        assertNotNull(e);
        assertEquals("container-a", e.containerId());
        assertEquals("digest-xyz", e.digest());
    }

    @Test
    void lockIsStablePerKey() {
        CreateIdempotencyStore s = store(600);
        assertSame(s.lockFor("c1", "k1"), s.lockFor("c1", "k1"));
        assertNotNull(s.lockFor("c1", "k2"));
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        CreateIdempotencyStore s = store(1);
        s.remember("c1", "k1", "container-a");
        assertEquals("container-a", s.lookup("c1", "k1"));
        Thread.sleep(1100);
        assertNull(s.lookup("c1", "k1"));
    }
}
