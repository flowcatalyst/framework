package io.flowcatalyst.framework.locks.redis;

import io.flowcatalyst.framework.conformance.Conformance;
import org.junit.jupiter.api.Test;

/** Writes and checks the shared lock scripts ({@code conformance/lua/locks/}), which the Rust implementation loads. */
class LockScriptConformanceTest {

  @Test
  void scriptsAreTheSharedFiles() {
    Conformance.check("lua/locks/acquire.lua", RedisLockDriver.ACQUIRE_LUA);
    Conformance.check("lua/locks/refresh.lua", RedisLockDriver.REFRESH_LUA);
    Conformance.check("lua/locks/release.lua", RedisLockDriver.RELEASE_LUA);
  }
}
