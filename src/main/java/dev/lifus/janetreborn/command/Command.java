package dev.lifus.janetreborn.command;

@FunctionalInterface
public interface Command {
  CommandResult execute(CommandCall context) throws Exception;
}
