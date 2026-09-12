package opsec.misuyaka.sdk;

public final class ModuleException extends RuntimeException {
  public ModuleException(Module module, String phase, Throwable cause) {
    super(module.getName() + " failed during " + phase, cause);
  }
}
