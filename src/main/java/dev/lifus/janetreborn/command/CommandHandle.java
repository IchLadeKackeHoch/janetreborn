package dev.lifus.janetreborn.command;

public interface CommandHandle extends AutoCloseable {
  CommandSpec descriptor();

  boolean isRegistered();

  @Override
  void close();
}
