package opsec.misuyaka.sdk.setting;

import org.lwjgl.glfw.GLFW;

public final class KeySetting extends Setting<Integer> {
  public static final int UNBOUND = GLFW.GLFW_KEY_UNKNOWN;

  public KeySetting(String name) {
    super(name, UNBOUND);
  }

  public KeySetting(String id, String name) {
    super(id, name, UNBOUND);
  }

  @Override
  public void setValue(Integer value) {
    if (value != UNBOUND && (value < GLFW.GLFW_KEY_SPACE || value > GLFW.GLFW_KEY_LAST)) {
      throw new IllegalArgumentException("Invalid GLFW key: " + value);
    }
    super.setValue(value);
  }

  public boolean isBound() {
    return getValue() >= GLFW.GLFW_KEY_SPACE && getValue() <= GLFW.GLFW_KEY_LAST;
  }

  public String displayName() {
    if (!isBound()) return "Unbound";
    String name = GLFW.glfwGetKeyName(getValue(), 0);
    if (name != null && !name.isBlank()) return name.toUpperCase();
    return switch (getValue()) {
      case GLFW.GLFW_KEY_ESCAPE -> "Escape";
      case GLFW.GLFW_KEY_ENTER -> "Enter";
      case GLFW.GLFW_KEY_TAB -> "Tab";
      case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
      case GLFW.GLFW_KEY_INSERT -> "Insert";
      case GLFW.GLFW_KEY_DELETE -> "Delete";
      case GLFW.GLFW_KEY_RIGHT -> "Right";
      case GLFW.GLFW_KEY_LEFT -> "Left";
      case GLFW.GLFW_KEY_DOWN -> "Down";
      case GLFW.GLFW_KEY_UP -> "Up";
      case GLFW.GLFW_KEY_PAGE_UP -> "Page Up";
      case GLFW.GLFW_KEY_PAGE_DOWN -> "Page Down";
      case GLFW.GLFW_KEY_HOME -> "Home";
      case GLFW.GLFW_KEY_END -> "End";
      case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps Lock";
      case GLFW.GLFW_KEY_SCROLL_LOCK -> "Scroll Lock";
      case GLFW.GLFW_KEY_NUM_LOCK -> "Num Lock";
      case GLFW.GLFW_KEY_PRINT_SCREEN -> "Print Screen";
      case GLFW.GLFW_KEY_PAUSE -> "Pause";
      case GLFW.GLFW_KEY_LEFT_SHIFT -> "Left Shift";
      case GLFW.GLFW_KEY_LEFT_CONTROL -> "Left Ctrl";
      case GLFW.GLFW_KEY_LEFT_ALT -> "Left Alt";
      case GLFW.GLFW_KEY_LEFT_SUPER -> "Left Super";
      case GLFW.GLFW_KEY_RIGHT_SHIFT -> "Right Shift";
      case GLFW.GLFW_KEY_RIGHT_CONTROL -> "Right Ctrl";
      case GLFW.GLFW_KEY_RIGHT_ALT -> "Right Alt";
      case GLFW.GLFW_KEY_RIGHT_SUPER -> "Right Super";
      default ->
          getValue() >= GLFW.GLFW_KEY_F1 && getValue() <= GLFW.GLFW_KEY_F25
              ? "F" + (getValue() - GLFW.GLFW_KEY_F1 + 1)
              : "Key " + getValue();
    };
  }
}
