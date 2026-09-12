package dev.lifus.janetreborn.feature.render;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmojifyModule extends Module {
  private static final Logger LOGGER = LoggerFactory.getLogger("JanetReborn/EmojifyModule");
  private static final String TEXTURE_ROOT = "/assets/janet_reborn/textures/emojify/";
  private static final String[] TEXTURE_FILES = {
    "laugh.png",
    "hundred.png",
    "smirk.png",
    "fire.png",
    "rolling_eyes.png",
    "skull.png",
    "sunglasses.png",
    "clown.png"
  };
  private static final int MAX_ACTIVE_EMOJIS = 24;

  private final Minecraft minecraft;
  private final List<HitEmoji> active = new ArrayList<>();
  private final List<EmojiTexture> textures = new ArrayList<>();
  private final ModeSetting<EmojiStyle> emoji = mode("Emoji", EmojiStyle.RANDOM);
  private final BoolSetting playersOnly = bool("Players Only", true);
  private final NumberSetting<Integer> size =
      add(new NumberSetting<>("Size", 116, 24, 224, 2).unit("px"));
  private final NumberSetting<Integer> duration =
      add(new NumberSetting<>("duration_ms", "Duration", 750, 300, 1500, 50).unit("ms"));
  private final NumberSetting<Integer> rise =
      add(new NumberSetting<>("Rise", 46, 0, 100, 2).unit("px"));
  private final BoolSetting followTarget = bool("Follow Target", true);
  private final BoolSetting impactRing = bool("Impact Ring", true);
  private boolean textureLoadAttempted;

  public EmojifyModule(Minecraft minecraft) {
    super("Emojify", "Pops a new emoji over opponents when you hit them", Category.VISUAL);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  @Override
  protected void onInitialize() {
    listen(AttackEvent.class, this::onAttack);
    listen(FrameRenderEvent.class, this::render);
  }

  @Override
  protected void onDisable() {
    active.clear();
  }

  @Override
  protected void onCleanup() {
    active.clear();
    for (EmojiTexture texture : textures) texture.close();
    textures.clear();
  }

  private void onAttack(AttackEvent event) {
    Entity target = event.getTarget();
    if (!(target instanceof LivingEntity living)
        || target == minecraft.player
        || playersOnly.getValue() && !(target instanceof Player)
        || !living.isAlive()
        || living.isDeadOrDying()) return;

    ThreadLocalRandom random = ThreadLocalRandom.current();
    int textureIndex = emoji.getValue().textureIndex(random);
    Vec3 hitPosition = event.getHitPosition();
    Vec3 targetOffset = hitPosition.subtract(target.getPosition(1.0f));
    if (active.size() >= MAX_ACTIVE_EMOJIS) active.removeFirst();
    active.add(
        new HitEmoji(
            target,
            hitPosition,
            targetOffset,
            textureIndex,
            System.nanoTime(),
            random.nextFloat(-18.0f, 18.0f),
            random.nextFloat(0.9f, 1.12f)));
  }

  private void render(FrameRenderEvent event) {
    if (minecraft.level == null) return;
    ensureTextures();
    if (active.isEmpty() || textures.size() != TEXTURE_FILES.length) return;

    long now = System.nanoTime();
    float lifetimeNanos = duration.getValue() * 1_000_000.0f;
    Camera camera = minecraft.gameRenderer.mainCamera();
    ImDrawList drawList = ImGui.getForegroundDrawList();
    Iterator<HitEmoji> iterator = active.iterator();
    while (iterator.hasNext()) {
      HitEmoji hit = iterator.next();
      float progress = (now - hit.startedAtNanos) / lifetimeNanos;
      if (progress >= 1.0f) {
        iterator.remove();
        continue;
      }
      if (progress < 0.0f) continue;

      Vec3 anchor = hit.hitPosition;
      if (followTarget.getValue() && !hit.target.isRemoved()) {
        anchor = hit.target.getPosition(event.getTickDelta()).add(hit.targetOffset);
      }
      float[] screen = project(camera, anchor);
      if (screen == null) continue;
      drawHit(drawList, hit, screen[0], screen[1], progress);
    }
  }

  private void drawHit(
      ImDrawList drawList, HitEmoji hit, float screenX, float screenY, float progress) {
    float eased = easeOutCubic(progress);
    float centerX = screenX + hit.horizontalDrift * eased;
    float centerY = screenY - rise.getValue() * eased;
    float emojiSize = size.getValue() * hit.sizeVariation * popScale(progress);
    if (emojiSize <= 0.1f) return;

    int alpha = Math.clamp(Math.round(255.0f * fade(progress)), 0, 255);
    if (impactRing.getValue())
      drawImpactRing(drawList, centerX, centerY, progress, emojiSize, alpha);

    drawImage(
        drawList,
        textures.get(hit.textureIndex),
        centerX,
        centerY,
        emojiSize,
        ImColor.rgba(255, 255, 255, alpha));
  }

  private void drawImpactRing(
      ImDrawList drawList, float x, float y, float progress, float emojiSize, int emojiAlpha) {
    float ringProgress = Math.clamp(progress / 0.48f, 0.0f, 1.0f);
    int ringAlpha = Math.round(emojiAlpha * (1.0f - ringProgress) * 0.72f);
    if (ringAlpha <= 0) return;
    float radius = emojiSize * (0.42f + ringProgress * 0.55f);
    int color = ImColor.rgba(255, 222, 92, ringAlpha);
    drawList.addCircle(x, y, radius, color, 28, Math.max(1.5f, emojiSize * 0.045f));
    for (int ray = 0; ray < 6; ray++) {
      double angle = ray * Math.PI / 3.0;
      float inner = radius + emojiSize * 0.08f;
      float outer = inner + emojiSize * 0.13f * (1.0f - ringProgress);
      drawList.addLine(
          x + (float) Math.cos(angle) * inner,
          y + (float) Math.sin(angle) * inner,
          x + (float) Math.cos(angle) * outer,
          y + (float) Math.sin(angle) * outer,
          color,
          Math.max(1.0f, emojiSize * 0.035f));
    }
  }

  private void drawImage(
      ImDrawList drawList,
      EmojiTexture texture,
      float centerX,
      float centerY,
      float imageSize,
      int tint) {
    float width = texture.aspectRatio() >= 1.0f ? imageSize : imageSize * texture.aspectRatio();
    float height = texture.aspectRatio() >= 1.0f ? imageSize / texture.aspectRatio() : imageSize;
    float halfWidth = width * 0.5f;
    float halfHeight = height * 0.5f;
    drawList.addImage(
        (long) texture.id(),
        centerX - halfWidth,
        centerY - halfHeight,
        centerX + halfWidth,
        centerY + halfHeight,
        0.0f,
        0.0f,
        1.0f,
        1.0f,
        tint);
  }

  private float[] project(Camera camera, Vec3 point) {
    Vec3 offset = point.subtract(camera.position());
    Vector3fc forward = camera.forwardVector();
    if (offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z() <= 0.05) {
      return null;
    }
    Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
    return new float[] {
      (float) ((projected.x + 1.0) * 0.5 * ImGui.getIO().getDisplaySizeX()),
      (float) ((1.0 - projected.y) * 0.5 * ImGui.getIO().getDisplaySizeY())
    };
  }

  private void ensureTextures() {
    if (textureLoadAttempted) return;
    textureLoadAttempted = true;
    try {
      for (String file : TEXTURE_FILES) textures.add(loadTexture(TEXTURE_ROOT + file));
    } catch (RuntimeException | IOException exception) {
      for (EmojiTexture texture : textures) texture.close();
      textures.clear();
      LOGGER.error("Could not load EmojifyModule textures", exception);
    }
  }

  private EmojiTexture loadTexture(String resourcePath) throws IOException {
    NativeImage image;
    try (InputStream input = EmojifyModule.class.getResourceAsStream(resourcePath)) {
      if (input == null) throw new IOException("Missing resource " + resourcePath);
      image = NativeImage.read(input);
    }
    DynamicTexture texture = null;
    try {
      texture = new DynamicTexture(() -> "EmojifyModule " + resourcePath, image);
      if (!(texture.getTexture() instanceof GlTexture glTexture)) {
        throw new IOException("EmojifyModule requires Minecraft's OpenGL texture backend");
      }
      configureForImGui(glTexture.glId());
      return new EmojiTexture(
          texture, glTexture.glId(), (float) image.getWidth() / image.getHeight());
    } catch (RuntimeException | IOException exception) {
      if (texture != null) texture.close();
      else image.close();
      throw exception;
    }
  }

  private static void configureForImGui(int texture) {
    int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    glActiveTexture(GL_TEXTURE0);
    int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    try {
      glBindTexture(GL_TEXTURE_2D, texture);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    } finally {
      glBindTexture(GL_TEXTURE_2D, previousTexture);
      glActiveTexture(previousActiveTexture);
    }
  }

  static float popScale(float progress) {
    float enter = Math.clamp(progress / 0.3f, 0.0f, 1.0f);
    float shifted = enter - 1.0f;
    float overshoot = 1.0f + 2.70158f * shifted * shifted * shifted + 1.70158f * shifted * shifted;
    float exit = 1.0f - smoothstep(0.76f, 1.0f, progress);
    return Math.max(0.0f, overshoot * exit);
  }

  static float fade(float progress) {
    return 1.0f - smoothstep(0.68f, 1.0f, progress);
  }

  private static float easeOutCubic(float value) {
    float inverse = 1.0f - Math.clamp(value, 0.0f, 1.0f);
    return 1.0f - inverse * inverse * inverse;
  }

  private static float smoothstep(float edge0, float edge1, float value) {
    float normalized = Math.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return normalized * normalized * (3.0f - 2.0f * normalized);
  }

  private enum EmojiStyle {
    RANDOM(-1),
    LAUGH(0),
    HUNDRED(1),
    SMIRK(2),
    FIRE(3),
    ROLLING_EYES(4),
    SKULL(5),
    SUNGLASSES(6),
    CLOWN(7);

    private final int textureIndex;

    EmojiStyle(int textureIndex) {
      this.textureIndex = textureIndex;
    }

    private int textureIndex(ThreadLocalRandom random) {
      return textureIndex < 0 ? random.nextInt(TEXTURE_FILES.length) : textureIndex;
    }
  }

  private static final class HitEmoji {
    private final Entity target;
    private final Vec3 hitPosition;
    private final Vec3 targetOffset;
    private final int textureIndex;
    private final long startedAtNanos;
    private final float horizontalDrift;
    private final float sizeVariation;

    private HitEmoji(
        Entity target,
        Vec3 hitPosition,
        Vec3 targetOffset,
        int textureIndex,
        long startedAtNanos,
        float horizontalDrift,
        float sizeVariation) {
      this.target = target;
      this.hitPosition = hitPosition;
      this.targetOffset = targetOffset;
      this.textureIndex = textureIndex;
      this.startedAtNanos = startedAtNanos;
      this.horizontalDrift = horizontalDrift;
      this.sizeVariation = sizeVariation;
    }
  }

  private record EmojiTexture(DynamicTexture texture, int id, float aspectRatio)
      implements AutoCloseable {
    @Override
    public void close() {
      texture.close();
    }
  }
}
