package dev.lifus.janetreborn.scripting;

public enum ScriptState {
  DISCOVERED,
  AWAITING_APPROVAL,
  LOADING,
  LOADED,
  ENABLED,
  DISABLED,
  ERROR,
  PAUSED,
  UNLOADED
}
