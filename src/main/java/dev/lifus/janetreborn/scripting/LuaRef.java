package dev.lifus.janetreborn.scripting;

public final class LuaRef implements AutoCloseable {
  private LuaScriptRuntime runtime;
  private final int reference;

  LuaRef(LuaScriptRuntime runtime, int reference) {
    this.runtime = runtime;
    this.reference = reference;
  }

  int reference() {
    if (runtime == null) throw new IllegalStateException("Lua reference is closed");
    return reference;
  }

  @Override
  public void close() {
    LuaScriptRuntime owner = runtime;
    if (owner == null) return;
    runtime = null;
    owner.release(reference);
  }
}
