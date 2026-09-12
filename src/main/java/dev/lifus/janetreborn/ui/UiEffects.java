package dev.lifus.janetreborn.ui;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import imgui.ImDrawList;
import imgui.ImGui;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UiEffects implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger("JanetReborn/UiEffects");
  private static final String SHADER_ROOT = "/assets/janet_reborn/shaders/clickgui/";

  private static final int MAX_KERNEL_RADIUS = 24;
  private static final float WINDOW_ROUNDING = 10.0f;
  private static final float GLASS_RED = 0.035f;
  private static final float GLASS_GREEN = 0.038f;
  private static final float GLASS_BLUE = 0.055f;
  private static final float GLASS_OPACITY = 0.8f;

  public enum Surface {
    MAIN(1.0f, 1.0f),
    CHILD(0.65f, 0.88f),
    POPUP(0.8f, 1.0f),
    HUD(1.0f, 1.0f);

    private final float roundingScale;
    private final float opacityScale;

    Surface(float roundingScale, float opacityScale) {
      this.roundingScale = roundingScale;
      this.opacityScale = opacityScale;
    }
  }

  private final Minecraft minecraft;
  private final List<Region> regions = new ArrayList<>();
  private PostFxConfig configuration = new PostFxConfig() {};
  private boolean collecting;
  private boolean available;
  private boolean blurredSceneReady;
  private boolean captureFailureLogged;
  private int blurProgram;
  private int shadowProgram;
  private int blurTextureUniform;
  private int blurTexelStepUniform;
  private int blurKernelRadiusUniform;
  private int blurWeightsUniform;
  private int shadowViewportUniform;
  private int shadowRectUniform;
  private int shadowRoundingUniform;
  private int shadowSizeUniform;
  private int shadowOpacityUniform;
  private int vertexArray;
  private int sourceFramebuffer;
  private int framebufferA;
  private int framebufferB;
  private int textureA;
  private int textureB;
  private int targetWidth;
  private int targetHeight;

  public UiEffects(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public void init() {
    if (available) return;
    try {
      String vertex = readShader("fullscreen.vert");
      blurProgram = createProgram(vertex, readShader("gaussian_blur.frag"));
      shadowProgram = createProgram(vertex, readShader("rounded_shadow.frag"));
      blurTextureUniform = uniform(blurProgram, "uTexture");
      blurTexelStepUniform = uniform(blurProgram, "uTexelStep");
      blurKernelRadiusUniform = uniform(blurProgram, "uKernelRadius");
      blurWeightsUniform = uniform(blurProgram, "uWeights[0]");
      shadowViewportUniform = uniform(shadowProgram, "uViewport");
      shadowRectUniform = uniform(shadowProgram, "uRect");
      shadowRoundingUniform = uniform(shadowProgram, "uRounding");
      shadowSizeUniform = uniform(shadowProgram, "uShadowSize");
      shadowOpacityUniform = uniform(shadowProgram, "uShadowOpacity");
      vertexArray = glGenVertexArrays();
      sourceFramebuffer = glGenFramebuffers();
      framebufferA = glGenFramebuffers();
      framebufferB = glGenFramebuffers();
      available = true;
    } catch (RuntimeException | IOException exception) {
      LOGGER.warn("ClickGUI shaders are unavailable; using the opaque glass fallback", exception);
      close();
    }
  }

  public void setConfiguration(PostFxConfig configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  public void beginFrame(boolean clickGuiOpen) {
    regions.clear();
    collecting = clickGuiOpen;
    blurredSceneReady = false;
  }

  public void captureMinecraftScene() {
    if (!available
        || !collecting
        || !configuration.shadersEnabled()
        || !configuration.blurEnabled()) return;
    RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
    if (!(mainTarget.getColorTexture() instanceof GlTexture sceneTexture)) {
      logCaptureFailure("Minecraft main render target is not an OpenGL texture", null);
      return;
    }

    int width = sceneTexture.getWidth(0);
    int height = sceneTexture.getHeight(0);
    if (width <= 0 || height <= 0) return;
    GlState state = GlState.capture();
    try {
      float resolutionScale = Math.clamp(configuration.blurResolutionScale(), 0.25f, 1.0f);
      int blurWidth = Math.max(1, Math.round(width * resolutionScale));
      int blurHeight = Math.max(1, Math.round(height * resolutionScale));
      glActiveTexture(GL_TEXTURE0);
      ensureTargets(blurWidth, blurHeight);

      glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFramebuffer);
      glFramebufferTexture2D(
          GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sceneTexture.glId(), 0);
      glReadBuffer(GL_COLOR_ATTACHMENT0);
      if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("Minecraft scene framebuffer is incomplete");
      }
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebufferA);
      glBlitFramebuffer(
          0, 0, width, height, 0, 0, blurWidth, blurHeight, GL_COLOR_BUFFER_BIT, GL_LINEAR);

      prepareFullscreenState();
      int kernelRadius =
          Math.clamp(
              Math.round(configuration.blurRadius() * resolutionScale), 1, MAX_KERNEL_RADIUS);
      glUseProgram(blurProgram);
      glUniform1i(blurTextureUniform, 0);
      glUniform1i(blurKernelRadiusUniform, kernelRadius);
      glUniform1fv(blurWeightsUniform, gaussianKernel(kernelRadius));
      drawBlur(textureA, framebufferB, blurWidth, blurHeight, true);
      drawBlur(textureB, framebufferA, blurWidth, blurHeight, false);
      blurredSceneReady = true;
      captureFailureLogged = false;
    } catch (RuntimeException exception) {
      blurredSceneReady = false;
      logCaptureFailure("Could not capture and blur Minecraft's scene framebuffer", exception);
    } finally {
      state.restore();
    }
  }

  public void drawCurrentWindow(Surface surface) {
    drawCurrentWindow(surface, 1.0f);
  }

  public void drawCurrentWindow(Surface surface, float alpha) {
    if (!collecting) return;
    float x = ImGui.getWindowPosX();
    float y = ImGui.getWindowPosY();
    float width = ImGui.getWindowWidth();
    float height = ImGui.getWindowHeight();
    if (width < 2 || height < 2) return;
    float safeAlpha = Math.clamp(alpha, 0.0f, 1.0f);
    float rounding = WINDOW_ROUNDING * surface.roundingScale;
    Region region = new Region(x, y, width, height, rounding, safeAlpha);
    if (regions.stream().noneMatch(region::sameBounds)) regions.add(region);

    ImDrawList drawList = ImGui.getWindowDrawList();
    drawList.pushClipRect(x, y, x + width, y + height, false);
    if (blurredSceneReady) {
      float displayWidth = ImGui.getIO().getDisplaySizeX();
      float displayHeight = ImGui.getIO().getDisplaySizeY();
      float u0 = x / displayWidth;
      float u1 = (x + width) / displayWidth;
      float v0 = 1.0f - y / displayHeight;
      float v1 = 1.0f - (y + height) / displayHeight;
      int tint = ImGui.getColorU32(1, 1, 1, safeAlpha);
      drawList.addImageRounded(
          (long) textureA, x, y, x + width, y + height, u0, v0, u1, v1, tint, rounding);
    }
    int glass =
        ImGui.getColorU32(
            GLASS_RED, GLASS_GREEN, GLASS_BLUE, GLASS_OPACITY * surface.opacityScale * safeAlpha);
    drawList.addRectFilled(x, y, x + width, y + height, glass, rounding);
    drawList.popClipRect();
  }

  public void renderShadows() {
    if (!available
        || !collecting
        || !configuration.shadersEnabled()
        || !configuration.shadowsEnabled()
        || regions.isEmpty()) return;
    int width =
        Math.round(ImGui.getIO().getDisplaySizeX() * ImGui.getIO().getDisplayFramebufferScaleX());
    int height =
        Math.round(ImGui.getIO().getDisplaySizeY() * ImGui.getIO().getDisplayFramebufferScaleY());
    if (width <= 0 || height <= 0) return;
    GlState state = GlState.capture();
    try {
      prepareFullscreenState();
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, state.drawFramebuffer);
      glViewport(0, 0, width, height);
      if (state.framebufferSrgb) glEnable(GL_FRAMEBUFFER_SRGB);
      else glDisable(GL_FRAMEBUFFER_SRGB);
      glEnable(GL_BLEND);
      glBlendEquation(GL_FUNC_ADD);
      glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
      glEnable(GL_SCISSOR_TEST);
      glUseProgram(shadowProgram);
      glUniform2f(shadowViewportUniform, width, height);
      float scaleX = ImGui.getIO().getDisplayFramebufferScaleX();
      float scaleY = ImGui.getIO().getDisplayFramebufferScaleY();
      float shadow = Math.max(0.0f, configuration.shadowSize()) * Math.max(scaleX, scaleY);
      glUniform1f(shadowSizeUniform, shadow);
      for (Region region : regions) {
        float x = region.x * scaleX;
        float y = region.y * scaleY;
        float w = region.width * scaleX;
        float h = region.height * scaleY;
        setScissor(x, y, w, h, shadow, width, height);
        glUniform4f(shadowRectUniform, x, y, w, h);
        glUniform1f(shadowRoundingUniform, region.rounding * Math.min(scaleX, scaleY));
        glUniform1f(
            shadowOpacityUniform,
            Math.clamp(configuration.shadowOpacity(), 0.0f, 1.0f) * region.alpha);
        glDrawArrays(GL_TRIANGLES, 0, 3);
      }
    } finally {
      state.restore();
    }
  }

  static float[] gaussianKernel(int requestedRadius) {
    int radius = Math.clamp(requestedRadius, 1, MAX_KERNEL_RADIUS);
    float sigma = Math.max(0.5f, radius / 3.0f);
    float[] weights = new float[MAX_KERNEL_RADIUS + 1];
    weights[0] = 1.0f;
    float sum = 1.0f;
    for (int offset = 1; offset <= radius; offset++) {
      weights[offset] = (float) Math.exp(-(offset * offset) / (2.0f * sigma * sigma));
      sum += 2.0f * weights[offset];
    }
    for (int offset = 0; offset <= radius; offset++) weights[offset] /= sum;
    return weights;
  }

  private void drawBlur(
      int sourceTexture, int destinationFramebuffer, int width, int height, boolean horizontal) {
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destinationFramebuffer);
    glViewport(0, 0, width, height);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sourceTexture);
    glUniform2f(
        blurTexelStepUniform, horizontal ? 1.0f / width : 0, horizontal ? 0 : 1.0f / height);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }

  private void ensureTargets(int width, int height) {
    if (textureA != 0 && targetWidth == width && targetHeight == height) return;
    targetWidth = width;
    targetHeight = height;
    if (textureA == 0) textureA = glGenTextures();
    if (textureB == 0) textureB = glGenTextures();
    configureTarget(framebufferA, textureA, width, height);
    configureTarget(framebufferB, textureB, width, height);
  }

  private static void configureTarget(int framebuffer, int texture, int width, int height) {
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glDrawBuffer(GL_COLOR_ATTACHMENT0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Incomplete ClickGUI blur framebuffer");
    }
  }

  private void logCaptureFailure(String message, Throwable exception) {
    if (captureFailureLogged) return;
    captureFailureLogged = true;
    if (exception == null) LOGGER.warn(message);
    else LOGGER.warn(message, exception);
  }

  private void prepareFullscreenState() {
    glBindVertexArray(vertexArray);
    glBindSampler(0, 0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
  }

  private static void setScissor(
      float x,
      float y,
      float width,
      float height,
      float shadow,
      int framebufferWidth,
      int framebufferHeight) {
    int left = Math.max(0, (int) Math.floor(x - shadow));
    int top = Math.max(0, (int) Math.floor(y - shadow));
    int right = Math.min(framebufferWidth, (int) Math.ceil(x + width + shadow));
    int bottom = Math.min(framebufferHeight, (int) Math.ceil(y + height + shadow));
    glScissor(
        left, framebufferHeight - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
  }

  private static int createProgram(String vertexSource, String fragmentSource) {
    int vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
    int fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    int program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
      String log = glGetProgramInfoLog(program);
      glDeleteProgram(program);
      throw new IllegalStateException("Could not link ClickGUI shader: " + log);
    }
    return program;
  }

  private static int compileShader(int type, String source) {
    int shader = glCreateShader(type);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
      String log = glGetShaderInfoLog(shader);
      glDeleteShader(shader);
      throw new IllegalStateException("Could not compile ClickGUI shader: " + log);
    }
    return shader;
  }

  private static int uniform(int program, String name) {
    int location = glGetUniformLocation(program, name);
    if (location < 0) throw new IllegalStateException("Missing ClickGUI shader uniform " + name);
    return location;
  }

  private static String readShader(String name) throws IOException {
    try (InputStream stream = UiEffects.class.getResourceAsStream(SHADER_ROOT + name)) {
      if (stream == null) throw new IOException("Missing shader resource " + name);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Override
  public void close() {
    available = false;
    blurredSceneReady = false;
    regions.clear();
    if (textureA != 0) glDeleteTextures(textureA);
    if (textureB != 0) glDeleteTextures(textureB);
    if (sourceFramebuffer != 0) glDeleteFramebuffers(sourceFramebuffer);
    if (framebufferA != 0) glDeleteFramebuffers(framebufferA);
    if (framebufferB != 0) glDeleteFramebuffers(framebufferB);
    if (vertexArray != 0) glDeleteVertexArrays(vertexArray);
    if (blurProgram != 0) glDeleteProgram(blurProgram);
    if (shadowProgram != 0) glDeleteProgram(shadowProgram);
    textureA = textureB = sourceFramebuffer = framebufferA = framebufferB = 0;
    vertexArray = blurProgram = shadowProgram = 0;
    targetWidth = targetHeight = 0;
  }

  private record Region(float x, float y, float width, float height, float rounding, float alpha) {
    private boolean sameBounds(Region other) {
      return Math.abs(x - other.x) < 0.5f
          && Math.abs(y - other.y) < 0.5f
          && Math.abs(width - other.width) < 0.5f
          && Math.abs(height - other.height) < 0.5f;
    }
  }

  private record GlState(
      int readFramebuffer,
      int drawFramebuffer,
      int program,
      int vertexArray,
      int arrayBuffer,
      int activeTexture,
      int texture0,
      int sampler0,
      int[] viewport,
      int[] scissor,
      boolean blend,
      boolean depth,
      boolean cull,
      boolean scissorTest,
      boolean framebufferSrgb,
      int blendSrcRgb,
      int blendDstRgb,
      int blendSrcAlpha,
      int blendDstAlpha,
      int blendEquationRgb,
      int blendEquationAlpha) {
    private static GlState capture() {
      int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
      glActiveTexture(GL_TEXTURE0);
      int texture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
      int sampler0 = glGetInteger(GL_SAMPLER_BINDING);
      glActiveTexture(activeTexture);
      int[] viewport = new int[4];
      int[] scissor = new int[4];
      glGetIntegerv(GL_VIEWPORT, viewport);
      glGetIntegerv(GL_SCISSOR_BOX, scissor);
      return new GlState(
          glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
          glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
          glGetInteger(GL_CURRENT_PROGRAM),
          glGetInteger(GL_VERTEX_ARRAY_BINDING),
          glGetInteger(GL_ARRAY_BUFFER_BINDING),
          activeTexture,
          texture0,
          sampler0,
          viewport,
          scissor,
          glIsEnabled(GL_BLEND),
          glIsEnabled(GL_DEPTH_TEST),
          glIsEnabled(GL_CULL_FACE),
          glIsEnabled(GL_SCISSOR_TEST),
          glIsEnabled(GL_FRAMEBUFFER_SRGB),
          glGetInteger(GL_BLEND_SRC_RGB),
          glGetInteger(GL_BLEND_DST_RGB),
          glGetInteger(GL_BLEND_SRC_ALPHA),
          glGetInteger(GL_BLEND_DST_ALPHA),
          glGetInteger(GL_BLEND_EQUATION_RGB),
          glGetInteger(GL_BLEND_EQUATION_ALPHA));
    }

    private void restore() {
      glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
      glUseProgram(program);
      glBindVertexArray(vertexArray);
      glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, texture0);
      glBindSampler(0, sampler0);
      glActiveTexture(activeTexture);
      glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
      setEnabled(GL_BLEND, blend);
      setEnabled(GL_DEPTH_TEST, depth);
      setEnabled(GL_CULL_FACE, cull);
      setEnabled(GL_SCISSOR_TEST, scissorTest);
      setEnabled(GL_FRAMEBUFFER_SRGB, framebufferSrgb);
      glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
      glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
    }

    private static void setEnabled(int capability, boolean enabled) {
      if (enabled) glEnable(capability);
      else glDisable(capability);
    }
  }
}
