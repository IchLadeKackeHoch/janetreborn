package dev.lifus.janetreborn.scripting;

public final class ExecutionGuard {
  private final ScriptLimits limits;
  private long remainingInstructions;
  private long deadline;
  private int depth;
  private boolean executing;

  public ExecutionGuard(ScriptLimits limits) {
    this.limits = limits;
  }

  public synchronized Frame enter(String callback) {
    if (depth >= limits.recursionDepth()) {
      throw new ScriptBudgetException("Callback recursion limit exceeded in " + callback);
    }
    if (depth++ == 0) {
      remainingInstructions = limits.instructionsPerCallback();
      deadline = System.nanoTime() + limits.callbackNanos();
      executing = true;
    }
    return new Frame();
  }

  public synchronized void check(int instructions) {
    if (!executing) return;
    remainingInstructions -= instructions;
    if (remainingInstructions < 0) {
      throw new ScriptBudgetException("Lua instruction budget exceeded");
    }
    if (System.nanoTime() > deadline) {
      throw new ScriptBudgetException("Lua callback time budget exceeded");
    }
  }

  public final class Frame implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      synchronized (ExecutionGuard.this) {
        if (closed) return;
        closed = true;
        if (--depth == 0) executing = false;
      }
    }
  }
}
