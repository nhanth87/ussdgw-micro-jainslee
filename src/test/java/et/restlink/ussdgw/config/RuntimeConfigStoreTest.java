package et.restlink.ussdgw.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigStoreTest {
    @Test
    void getOrFallsBackWhenEmpty() {
        RuntimeConfigStore store = new RuntimeConfigStore();
        // no DB — cache empty
        assertThat(store.getOr("missing", "def")).isEqualTo("def");
        assertThat(store.getBool("x", true)).isTrue();
        assertThat(store.getInt("x", 7)).isEqualTo(7);
        assertThat(store.getLong("x", 9L)).isEqualTo(9L);
    }

    @Test
    void inMemoryPutVisibleViaCache() throws Exception {
        RuntimeConfigStore store = new RuntimeConfigStore();
        var cache = RuntimeConfigStore.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, String>) cache.get(store);
        map.put(RuntimeConfigStore.Keys.ASYNC_GATE_MS, "5000");
        map.put(RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, "false");
        assertThat(store.getLong(RuntimeConfigStore.Keys.ASYNC_GATE_MS, 7000)).isEqualTo(5000L);
        assertThat(store.getBool(RuntimeConfigStore.Keys.HTTP_CLIENT_BRIDGE, true)).isFalse();
    }
}
