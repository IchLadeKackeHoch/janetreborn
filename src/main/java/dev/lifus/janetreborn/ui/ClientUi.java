package dev.lifus.janetreborn.ui;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.config.Configs;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import dev.lifus.janetreborn.feature.client.ClientSettingsModule;
import dev.lifus.janetreborn.scripting.ScriptManager;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import java.util.Objects;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Modules;
import org.lwjgl.glfw.GLFW;

public final class ClientUi {
  private final ImGuiImplGlfw glfw = new ImGuiImplGlfw();
  private final ImGuiImplGl3 gl3 = new ImGuiImplGl3();
  private final Fonts fonts = new Fonts(gl3);
  private final Minecraft minecraft;
  private final UiEffects effects;
  private final NoticeView notifications;
  private final EventBus eventBus;
  private final Modules modules;
  private final ConfigView configPanel;
  private final ModuleView modulePanel;
  private boolean initialized;
  private boolean open;
  private boolean keyDown;
  private boolean escapeDown;
  private boolean restoreGrabbed;
  private boolean centerNextOpen;
  private Page page = Page.MODULES;
  private ClientSettingsModule clientSettings;
  private ScriptView scriptPanel;

  public ClientUi(
      Minecraft minecraft,
      Notices notifications,
      EventBus eventBus,
      Modules modules,
      Configs configs) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    effects = new UiEffects(minecraft);
    this.notifications = new NoticeView(notifications, effects);
    this.eventBus = eventBus;
    this.modules = modules;
    configPanel = new ConfigView(configs);
    modulePanel = new ModuleView(minecraft, modules);
  }

  public void init() {
    if (initialized) return;
    ImGui.createContext();
    ImGui.getIO().setIniFilename(null);
    ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
    setupStyle();
    fonts.load();
    glfw.init(minecraft.getWindow().handle(), true);
    gl3.init("#version 150");
    gl3.createFontsTexture();
    effects.init();
    initialized = true;
  }

  public void render(DeltaTracker deltaTracker) {
    if (!initialized) return;
    fonts.update();
    glfw.newFrame();
    gl3.newFrame();
    if (clientSettings != null) {
      ImGui.getIO().setFontGlobalScale(clientSettings.uiScale());
      notifications.setDisplayMillis(clientSettings.notificationDurationMillis());
    }
    ImGui.newFrame();
    updateMenuKey();
    modulePanel.captureKey(open);
    boolean notificationsEnabled = clientSettings == null || clientSettings.notificationsEnabled();
    boolean hudVisible =
        modules.getAll().stream()
            .anyMatch(
                module ->
                    module.isToggleable()
                        && module.isEnabled()
                        && module.getCategory() == Category.HUD);
    effects.beginFrame(
        open || hudVisible || notificationsEnabled && notifications.hasVisibleNotifications(open));
    effects.captureMinecraftScene();
    Theme.apply();
    if (open)
      GLFW.glfwSetInputMode(
          minecraft.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    eventBus.publish(new FrameRenderEvent(deltaTracker.getGameTimeDeltaPartialTick(true)));
    if (open) drawMenu();
    notifications.render(open, notificationsEnabled);
    ImGui.render();
    effects.renderShadows();
    gl3.renderDrawData(ImGui.getDrawData());
  }

  public void shutdown() {
    if (!initialized) return;
    if (scriptPanel != null) scriptPanel.close();
    effects.close();
    gl3.shutdown();
    glfw.shutdown();
    ImGui.destroyContext();
    initialized = false;
  }

  public boolean isOpen() {
    return open;
  }

  public void setClientSettings(ClientSettingsModule clientSettings) {
    this.clientSettings = clientSettings;
    fonts.setSettings(clientSettings);
  }

  public void setPostProcessingConfiguration(PostFxConfig configuration) {
    effects.setConfiguration(configuration);
  }

  public void setScriptManager(ScriptManager scripts) {
    scriptPanel = new ScriptView(scripts, modulePanel);
  }

  public void drawHudSurface() {
    effects.drawCurrentWindow(UiEffects.Surface.HUD);
  }

  public boolean isMovementKey(KeyEvent event) {
    return minecraft.options.keyUp.matches(event)
        || minecraft.options.keyDown.matches(event)
        || minecraft.options.keyLeft.matches(event)
        || minecraft.options.keyRight.matches(event)
        || minecraft.options.keyJump.matches(event)
        || minecraft.options.keyShift.matches(event)
        || minecraft.options.keySprint.matches(event);
  }

  private void updateMenuKey() {
    boolean pressed =
        GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT)
            == GLFW.GLFW_PRESS;
    if (pressed && !keyDown && !modulePanel.isCapturingKey()) {
      open = !open;
      if (open) {
        beginInputCapture();
        restoreGrabbed = minecraft.mouseHandler.isMouseGrabbed();
        centerNextOpen = true;
      } else closeMenu();
    }
    keyDown = pressed;
    boolean escape =
        GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
    if (open && escape && !escapeDown) {
      if (!modulePanel.cancelKeyCapture()) {
        open = false;
        closeMenu();
      }
    }
    escapeDown = escape;
  }

  private void drawMenu() {
    float displayWidth = ImGui.getIO().getDisplaySizeX();
    float displayHeight = ImGui.getIO().getDisplaySizeY();
    float availableWidth = Math.max(480, displayWidth - 24);
    float availableHeight = Math.max(360, displayHeight - 24);
    float width = Math.min(1100, availableWidth);
    float height = Math.min(760, availableHeight);
    ImGui.setNextWindowSizeConstraints(
        Math.min(780, availableWidth),
        Math.min(520, availableHeight),
        availableWidth,
        availableHeight);
    ImGui.setNextWindowSize(width, height, ImGuiCond.FirstUseEver);
    if (centerNextOpen) {
      ImGui.setNextWindowPos(
          ImGui.getIO().getDisplaySizeX() * 0.5f,
          ImGui.getIO().getDisplaySizeY() * 0.5f,
          ImGuiCond.Always,
          0.5f,
          0.5f);
      centerNextOpen = false;
    }
    if (ImGui.begin(
        "##JanetReborn",
        ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoScrollbar)) {
      drawHeader();
      ImGui.dummy(0, 8);
      if (ImGui.beginChild("##PrimaryNavigation", 184, 0, true)) {
        ImGui.textDisabled("WORKSPACE");
        ImGui.dummy(0, 4);
        if (ControlKit.navItem("Modules", "Modules", "01", page == Page.MODULES)) {
          page = Page.MODULES;
        }
        if (ControlKit.navItem("Settings", "Internal", "02", page == Page.SETTINGS)) {
          page = Page.SETTINGS;
        }
        if (ControlKit.navItem("Configs", "Profiles", "03", page == Page.CONFIGS)) {
          page = Page.CONFIGS;
        }
        if (scriptPanel != null
            && ControlKit.navItem("Scripts", "Scripts", "04", page == Page.SCRIPTS)) {
          page = Page.SCRIPTS;
        }
        float noteY = Math.max(ImGui.getCursorPosY() + 20, ImGui.getWindowHeight() - 72);
        ImGui.setCursorPosY(noteY);
        ImGui.textDisabled("RIGHT SHIFT");
        ImGui.textDisabled("Close workspace");
      }
      ImGui.endChild();
      ImGui.sameLine();
      if (ImGui.beginChild("##WorkspaceContent", 0, 0, true)) {
        drawPageHeading();
        ImGui.dummy(0, 6);
        switch (page) {
          case MODULES -> modulePanel.drawAllCategories();
          case SETTINGS -> modulePanel.drawSettings();
          case CONFIGS -> configPanel.draw();
          case SCRIPTS -> {
            if (scriptPanel != null) scriptPanel.draw();
          }
        }
      }
      ImGui.endChild();
    }
    ImGui.end();
  }

  private void drawHeader() {
    var draw = ImGui.getWindowDrawList();
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    draw.addRectFilled(x, y, x + width, y + 58, imgui.ImColor.rgb(31, 36, 40), 5);
    draw.addRect(x, y, x + width, y + 58, ControlKit.BORDER, 5);
    draw.addRectFilled(x + 15, y + 15, x + 19, y + 43, ControlKit.ACCENT, 2);
    draw.addText(x + 31, y + 11, ControlKit.TEXT, "JANET REBORN");
    draw.addText(x + 31, y + 34, ControlKit.MUTED, "CONTROL WORKSPACE");
    String hint = "RIGHT-CLICK A MODULE TO CONFIGURE";
    draw.addText(x + width - ImGui.calcTextSizeX(hint) - 18, y + 22, ControlKit.MUTED, hint);
    ImGui.dummy(width, 58);
  }

  private void drawPageHeading() {
    switch (page) {
      case MODULES -> {
        ImGui.text("Module library");
        ImGui.textDisabled("Browse by category · left-click to enable · right-click for settings");
      }
      case SETTINGS -> {
        ImGui.text("Internal settings");
        ImGui.textDisabled("Client-level behavior and shared services");
      }
      case CONFIGS -> {
        ImGui.text("Profiles");
        ImGui.textDisabled("Save and restore complete workspace configurations");
      }
      case SCRIPTS -> {
        ImGui.text("Scripts");
        ImGui.textDisabled("Manage live Lua extensions and their modules");
      }
    }
    ImGui.separator();
  }

  private void closeMenu() {
    modulePanel.clearKeyCapture();
    endInputCapture();
    GLFW.glfwSetInputMode(
        minecraft.getWindow().handle(),
        GLFW.GLFW_CURSOR,
        restoreGrabbed ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
    minecraft.mouseHandler.setIgnoreFirstMove();
  }

  private void beginInputCapture() {
    boolean forward = minecraft.options.keyUp.isDown();
    boolean backward = minecraft.options.keyDown.isDown();
    boolean left = minecraft.options.keyLeft.isDown();
    boolean right = minecraft.options.keyRight.isDown();
    boolean jump = minecraft.options.keyJump.isDown();
    boolean crouch = minecraft.options.keyShift.isDown();
    boolean sprint = minecraft.options.keySprint.isDown();
    KeyMapping.releaseAll();
    minecraft.options.keyUp.setDown(forward);
    minecraft.options.keyDown.setDown(backward);
    minecraft.options.keyLeft.setDown(left);
    minecraft.options.keyRight.setDown(right);
    minecraft.options.keyJump.setDown(jump);
    minecraft.options.keyShift.setDown(crouch);
    minecraft.options.keySprint.setDown(sprint);
  }

  private void endInputCapture() {
    KeyMapping.releaseAll();
    KeyMapping.setAll();
  }

  private enum Page {
    MODULES,
    SETTINGS,
    CONFIGS,
    SCRIPTS
  }

  private void setupStyle() {
    ImGuiStyle style = ImGui.getStyle();
    style.setAlpha(1);
    style.setDisabledAlpha(0.6f);
    style.setWindowPadding(8, 8);
    style.setWindowRounding(7);
    style.setWindowBorderSize(1);
    style.setWindowMinSize(32, 32);
    style.setWindowTitleAlign(0, 0.5f);
    style.setWindowMenuButtonPosition(ImGuiDir.Left);
    style.setChildRounding(4);
    style.setChildBorderSize(1);
    style.setPopupRounding(4);
    style.setPopupBorderSize(1);
    style.setFramePadding(5, 2);
    style.setFrameRounding(3);
    style.setFrameBorderSize(1);
    style.setItemSpacing(6, 6);
    style.setItemInnerSpacing(6, 6);
    style.setCellPadding(6, 6);
    style.setIndentSpacing(25);
    style.setColumnsMinSpacing(6);
    style.setScrollbarSize(15);
    style.setScrollbarRounding(9);
    style.setGrabMinSize(10);
    style.setGrabRounding(3);
    style.setTabRounding(4);
    style.setTabBorderSize(1);
    style.setTabCloseButtonMinWidthSelected(0);
    style.setTabCloseButtonMinWidthUnselected(0);
    style.setColorButtonPosition(ImGuiDir.Right);
    style.setButtonTextAlign(0.5f, 0.5f);
    style.setSelectableTextAlign(0, 0);
    color(style, ImGuiCol.Text, 1, 1, 1, 1);
    color(style, ImGuiCol.TextDisabled, 0.49803922f, 0.49803922f, 0.49803922f, 1);
    color(style, ImGuiCol.WindowBg, 0.09803922f, 0.09803922f, 0.09803922f, 1);
    color(style, ImGuiCol.ChildBg, 0, 0, 0, 0);
    color(style, ImGuiCol.PopupBg, 0.1882353f, 0.1882353f, 0.1882353f, 0.92f);
    color(style, ImGuiCol.Border, 0.1882353f, 0.1882353f, 0.1882353f, 0.29f);
    color(style, ImGuiCol.BorderShadow, 0, 0, 0, 0.24f);
    color(style, ImGuiCol.FrameBg, 0.047058824f, 0.047058824f, 0.047058824f, 0.54f);
    color(style, ImGuiCol.FrameBgHovered, 0.1882353f, 0.1882353f, 0.1882353f, 0.54f);
    color(style, ImGuiCol.FrameBgActive, 0.2f, 0.21960784f, 0.22745098f, 1);
    color(style, ImGuiCol.TitleBg, 0.05882353f, 0.05882353f, 0.05882353f, 1);
    color(style, ImGuiCol.TitleBgActive, 0.05882353f, 0.05882353f, 0.05882353f, 1);
    color(style, ImGuiCol.TitleBgCollapsed, 0, 0, 0, 1);
    color(style, ImGuiCol.MenuBarBg, 0.13725491f, 0.13725491f, 0.13725491f, 1);
    color(style, ImGuiCol.ScrollbarBg, 0.047058824f, 0.047058824f, 0.047058824f, 0.54f);
    color(style, ImGuiCol.ScrollbarGrab, 0.3372549f, 0.3372549f, 0.3372549f, 0.54f);
    color(style, ImGuiCol.ScrollbarGrabHovered, 0.4f, 0.4f, 0.4f, 0.54f);
    color(style, ImGuiCol.ScrollbarGrabActive, 0.5568628f, 0.5568628f, 0.5568628f, 0.54f);
    color(style, ImGuiCol.CheckMark, 0.32941177f, 0.6666667f, 0.85882354f, 1);
    color(style, ImGuiCol.SliderGrab, 0.32941177f, 0.6666667f, 0.85882354f, 1);
    color(style, ImGuiCol.SliderGrabActive, 0.7058824f, 0.3529412f, 1, 1);
    color(style, ImGuiCol.Button, 0.047058824f, 0.047058824f, 0.047058824f, 0.54f);
    color(style, ImGuiCol.ButtonHovered, 0.1882353f, 0.1882353f, 0.1882353f, 0.54f);
    color(style, ImGuiCol.ButtonActive, 0.2f, 0.21960784f, 0.22745098f, 1);
    color(style, ImGuiCol.Header, 0, 0, 0, 0.52f);
    color(style, ImGuiCol.HeaderHovered, 0, 0, 0, 0.36f);
    color(style, ImGuiCol.HeaderActive, 0.2f, 0.21960784f, 0.22745098f, 0.33f);
    color(style, ImGuiCol.Separator, 0.2784314f, 0.2784314f, 0.2784314f, 0.29f);
    color(style, ImGuiCol.SeparatorHovered, 0.4392157f, 0.4392157f, 0.4392157f, 0.29f);
    color(style, ImGuiCol.SeparatorActive, 0.4f, 0.4392157f, 0.46666667f, 1);
    color(style, ImGuiCol.ResizeGrip, 0.2784314f, 0.2784314f, 0.2784314f, 0.29f);
    color(style, ImGuiCol.ResizeGripHovered, 0.4392157f, 0.4392157f, 0.4392157f, 0.29f);
    color(style, ImGuiCol.ResizeGripActive, 0.4f, 0.4392157f, 0.46666667f, 1);
    color(style, ImGuiCol.Tab, 0, 0, 0, 0.52f);
    color(style, ImGuiCol.TabHovered, 0.13725491f, 0.13725491f, 0.13725491f, 1);
    color(style, ImGuiCol.TabActive, 0.2f, 0.2f, 0.2f, 0.36f);
    color(style, ImGuiCol.TabUnfocused, 0, 0, 0, 0.52f);
    color(style, ImGuiCol.TabUnfocusedActive, 0.13725491f, 0.13725491f, 0.13725491f, 1);
    color(style, ImGuiCol.PlotLines, 1, 0, 0, 1);
    color(style, ImGuiCol.PlotLinesHovered, 1, 0, 0, 1);
    color(style, ImGuiCol.PlotHistogram, 1, 0, 0, 1);
    color(style, ImGuiCol.PlotHistogramHovered, 1, 0, 0, 1);
    color(style, ImGuiCol.TableHeaderBg, 0, 0, 0, 0.52f);
    color(style, ImGuiCol.TableBorderStrong, 0, 0, 0, 0.52f);
    color(style, ImGuiCol.TableBorderLight, 0.2784314f, 0.2784314f, 0.2784314f, 0.29f);
    color(style, ImGuiCol.TableRowBg, 0, 0, 0, 0);
    color(style, ImGuiCol.TableRowBgAlt, 1, 1, 1, 0.06f);
    color(style, ImGuiCol.TextSelectedBg, 0.2f, 0.21960784f, 0.22745098f, 1);
    color(style, ImGuiCol.DragDropTarget, 0.32941177f, 0.6666667f, 0.85882354f, 1);
    color(style, ImGuiCol.NavHighlight, 1, 0, 0, 1);
    color(style, ImGuiCol.NavWindowingHighlight, 1, 0, 0, 0.7f);
    color(style, ImGuiCol.NavWindowingDimBg, 1, 0, 0, 0.2f);
    color(style, ImGuiCol.ModalWindowDimBg, 1, 0, 0, 0.35f);
  }

  private void color(ImGuiStyle style, int index, float red, float green, float blue, float alpha) {
    style.setColor(index, red, green, blue, alpha);
  }
}
