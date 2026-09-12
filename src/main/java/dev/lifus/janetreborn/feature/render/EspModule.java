package dev.lifus.janetreborn.feature.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import dev.lifus.janetreborn.platform.minecraft.mixin.LivingEntityRendererAccessor;
import dev.lifus.janetreborn.service.friend.FriendStore;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.awt.Color;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class EspModule extends Module {
  static final Color DEFAULT_2D_COLOR = new Color(144, 144, 255);
  static final Color DEFAULT_3D_COLOR = new Color(144, 144, 255);
  static final Color DEFAULT_SKELETON_COLOR = Color.WHITE;
  private final Minecraft minecraft;
  private final FriendStore friends;
  private final BoolSetting playersOnly = add(new BoolSetting("Players Only", true));
  private final BoolSetting hideSelf = add(new BoolSetting("Hide Self", true));
  private final BoolSetting box2d = add(new BoolSetting("2D Box", true));
  private final ModeSetting<Box2DMode> box2dMode =
      add(new ModeSetting<>("2D Box Mode", Box2DMode.CORNERS));
  private final BoolSetting equalCornerLength = add(new BoolSetting("Equal Corner Length", true));
  private final NumberSetting<Double> maxDistance =
      add(new NumberSetting<>("Max Distance", 96.0, 16.0, 256.0, 4.0).unit("blocks"));
  private final ColorSetting box2dColor = add(new ColorSetting("2D Box Color", DEFAULT_2D_COLOR));
  private final BoolSetting box3d = add(new BoolSetting("3D Box", false));
  private final ColorSetting box3dColor = add(new ColorSetting("3D Box Color", DEFAULT_3D_COLOR));
  private final BoolSetting skeleton = add(new BoolSetting("Skeleton", false));
  private final ColorSetting skeletonColor =
      add(new ColorSetting("Skeleton Color", DEFAULT_SKELETON_COLOR));
  private final BoolSetting healthBar = add(new BoolSetting("Health Bar", true));
  private final ModeSetting<BarPosition> healthBarPosition =
      add(new ModeSetting<>("Health Bar Position", BarPosition.LEFT));

  @Subscribe private final Listener<FrameRenderEvent> render = this::render;

  public EspModule(Minecraft minecraft, FriendStore friends) {
    super("ESP", "Highlights entities", Category.VISUAL);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.friends = Objects.requireNonNull(friends, "friends");
    box2dMode.visibleWhen(box2d);
    equalCornerLength.visibleWhen(
        () -> box2d.getValue() && box2dMode.getValue() == Box2DMode.CORNERS);
    equalCornerLength.markAdvanced();
    box2dColor.visibleWhen(box2d);
    box3dColor.visibleWhen(box3d);
    skeletonColor.visibleWhen(skeleton);
    healthBarPosition.visibleWhen(healthBar);
  }

  private void render(FrameRenderEvent event) {
    if (minecraft.level == null
        || (!box2d.getValue()
            && !box3d.getValue()
            && !skeleton.getValue()
            && !healthBar.getValue())) return;
    ImDrawList drawList = ImGui.getBackgroundDrawList();
    for (Entity entity : minecraft.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living
          && living.isAlive()
          && living.distanceToSqr(minecraft.gameRenderer.mainCamera().position())
              <= maxDistance.getValue() * maxDistance.getValue()
          && !living.isDeadOrDying()
          && (!hideSelf.getValue() || living != minecraft.player)
          && (!playersOnly.getValue() || living instanceof Player)
          && (!(living instanceof Player player) || !friends.isProtected(player))) {
        drawBox(drawList, living, event.getTickDelta());
      }
    }
  }

  private void drawBox(ImDrawList drawList, LivingEntity entity, float tickDelta) {
    Vec3 position = entity.getPosition(tickDelta);
    AABB box = entity.getBoundingBox().move(position.subtract(entity.position()));
    Camera camera = minecraft.gameRenderer.mainCamera();
    if (!inFront(camera, box.getCenter())) return;
    float minX = Float.POSITIVE_INFINITY;
    float minY = Float.POSITIVE_INFINITY;
    float maxX = Float.NEGATIVE_INFINITY;
    float maxY = Float.NEGATIVE_INFINITY;
    float[][] projectedCorners = new float[8][2];
    for (int i = 0; i < 8; i++) {
      Vec3 point =
          new Vec3(
              (i & 1) == 0 ? box.minX : box.maxX,
              (i & 2) == 0 ? box.minY : box.maxY,
              (i & 4) == 0 ? box.minZ : box.maxZ);
      if (!inFront(camera, point)) return;
      Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
      float x = (float) ((projected.x + 1) * 0.5 * ImGui.getIO().getDisplaySizeX());
      float y = (float) ((1 - projected.y) * 0.5 * ImGui.getIO().getDisplaySizeY());
      projectedCorners[i][0] = x;
      projectedCorners[i][1] = y;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }
    if (box3d.getValue()) draw3DBox(drawList, projectedCorners);
    if (box2d.getValue()) draw2DBox(drawList, minX, minY, maxX, maxY);
    if (skeleton.getValue() && entity instanceof AbstractClientPlayer player) {
      drawSkeleton(drawList, player, camera, tickDelta);
    }
    if (healthBar.getValue()) drawHealthBar(drawList, entity, minX, minY, maxX, maxY);
  }

  private void draw2DBox(ImDrawList drawList, float minX, float minY, float maxX, float maxY) {
    int shadow = ImColor.rgba(0, 0, 0, 255);
    int color = ImColor.rgba(box2dColor.getValue());
    if (box2dMode.getValue() == Box2DMode.FULL) {
      drawList.addRect(minX, minY, maxX, maxY, shadow, 0, 0, 4);
      drawList.addRect(minX, minY, maxX, maxY, color, 0, 0, 2);
      return;
    }
    float[] lengths = cornerLengths(maxX - minX, maxY - minY, equalCornerLength.getValue());
    float horizontal = lengths[0];
    float vertical = lengths[1];
    drawCorners(drawList, minX, minY, maxX, maxY, horizontal, vertical, shadow, 4.0f);
    drawCorners(drawList, minX, minY, maxX, maxY, horizontal, vertical, color, 2.0f);
  }

  static float[] cornerLengths(float width, float height, boolean equal) {
    float horizontal = Math.max(3.0f, width * 0.25f);
    float vertical = Math.max(3.0f, height * 0.25f);
    if (equal) horizontal = vertical = Math.max(3.0f, Math.min(width, height) * 0.25f);
    return new float[] {horizontal, vertical};
  }

  private void drawCorners(
      ImDrawList drawList,
      float minX,
      float minY,
      float maxX,
      float maxY,
      float horizontal,
      float vertical,
      int color,
      float thickness) {
    drawList.addLine(minX, minY, minX + horizontal, minY, color, thickness);
    drawList.addLine(minX, minY, minX, minY + vertical, color, thickness);
    drawList.addLine(maxX, minY, maxX - horizontal, minY, color, thickness);
    drawList.addLine(maxX, minY, maxX, minY + vertical, color, thickness);
    drawList.addLine(minX, maxY, minX + horizontal, maxY, color, thickness);
    drawList.addLine(minX, maxY, minX, maxY - vertical, color, thickness);
    drawList.addLine(maxX, maxY, maxX - horizontal, maxY, color, thickness);
    drawList.addLine(maxX, maxY, maxX, maxY - vertical, color, thickness);
  }

  private void draw3DBox(ImDrawList drawList, float[][] corners) {
    draw3DEdges(drawList, corners, ImColor.rgba(0, 0, 0, 255), 4.0f);
    draw3DEdges(drawList, corners, ImColor.rgba(box3dColor.getValue()), 2.0f);
  }

  private void draw3DEdges(ImDrawList drawList, float[][] corners, int color, float thickness) {
    for (int corner = 0; corner < corners.length; corner++) {
      if ((corner & 1) == 0) drawEdge(drawList, corners, corner, corner | 1, color, thickness);
      if ((corner & 2) == 0) drawEdge(drawList, corners, corner, corner | 2, color, thickness);
      if ((corner & 4) == 0) drawEdge(drawList, corners, corner, corner | 4, color, thickness);
    }
  }

  private void drawEdge(
      ImDrawList drawList, float[][] corners, int from, int to, int color, float thickness) {
    drawList.addLine(
        corners[from][0], corners[from][1], corners[to][0], corners[to][1], color, thickness);
  }

  private void drawSkeleton(
      ImDrawList drawList, AbstractClientPlayer player, Camera camera, float tickDelta) {
    var renderer = minecraft.getEntityRenderDispatcher().getPlayerRenderer(player);
    AvatarRenderState state = renderer.createRenderState();
    renderer.extractRenderState(player, state, tickDelta);
    var model = renderer.getModel();
    model.setupAnim(state);

    PoseStack poseStack = new PoseStack();
    poseStack.translate(player.getPosition(tickDelta));
    if (state.hasPose(Pose.SLEEPING) && state.bedOrientation != null) {
      float offset = state.eyeHeight - 0.1f;
      poseStack.translate(
          -state.bedOrientation.getStepX() * offset, 0, -state.bedOrientation.getStepZ() * offset);
    }
    poseStack.scale(state.scale, state.scale, state.scale);
    LivingEntityRendererAccessor rendererAccess = (LivingEntityRendererAccessor) renderer;
    rendererAccess.janetReborn$setupRotations(state, poseStack, state.bodyRot, state.scale);
    poseStack.scale(-1, -1, 1);
    rendererAccess.janetReborn$scale(state, poseStack);
    poseStack.translate(0, -1.501, 0);

    ModelPart root = model.root();
    ModelLine headLine = modelCenterLine(poseStack, root, model.head);
    ModelLine leftArm = modelCenterLine(poseStack, root, model.leftArm);
    ModelLine rightArm = modelCenterLine(poseStack, root, model.rightArm);
    ModelLine leftLeg = modelCenterLine(poseStack, root, model.leftLeg);
    ModelLine rightLeg = modelCenterLine(poseStack, root, model.rightLeg);
    if (headLine == null
        || leftArm == null
        || rightArm == null
        || leftLeg == null
        || rightLeg == null) return;

    Vec3 head = midpoint(headLine.start(), headLine.end());
    Vec3 leftShoulder = leftArm.start();
    Vec3 rightShoulder = rightArm.start();
    Vec3 leftHip = leftLeg.start();
    Vec3 rightHip = rightLeg.start();
    Vec3 shoulders = midpoint(leftShoulder, rightShoulder);
    Vec3 hips = midpoint(leftHip, rightHip);

    int shadow = ImColor.rgba(0, 0, 0, 255);
    int color = ImColor.rgba(skeletonColor.getValue());
    Vec3[][] bones = {
      {head, shoulders},
      {leftShoulder, rightShoulder},
      {shoulders, hips},
      {leftShoulder, leftArm.end()},
      {rightShoulder, rightArm.end()},
      {leftHip, rightHip},
      {leftHip, leftLeg.end()},
      {rightHip, rightLeg.end()}
    };
    for (Vec3[] bone : bones) drawWorldLine(drawList, camera, bone[0], bone[1], shadow, 4.0f);
    for (Vec3[] bone : bones) drawWorldLine(drawList, camera, bone[0], bone[1], color, 2.0f);
  }

  private ModelLine modelCenterLine(PoseStack poseStack, ModelPart root, ModelPart part) {
    poseStack.pushPose();
    root.translateAndRotate(poseStack);
    ModelLine[] result = new ModelLine[1];
    float[] longest = {-1.0f};
    part.visit(
        poseStack,
        (pose, path, index, cube) -> {
          if (!path.isEmpty()) return;
          float length = cube.maxY - cube.minY;
          if (length <= longest[0]) return;

          float centerX = (cube.minX + cube.maxX) / 32.0f;
          float centerZ = (cube.minZ + cube.maxZ) / 32.0f;
          Vector3f start =
              pose.pose().transformPosition(new Vector3f(centerX, cube.minY / 16.0f, centerZ));
          Vector3f end =
              pose.pose().transformPosition(new Vector3f(centerX, cube.maxY / 16.0f, centerZ));
          result[0] =
              new ModelLine(new Vec3(start.x, start.y, start.z), new Vec3(end.x, end.y, end.z));
          longest[0] = length;
        });
    poseStack.popPose();
    return result[0];
  }

  static Vec3 midpoint(Vec3 first, Vec3 second) {
    return new Vec3(
        (first.x + second.x) * 0.5, (first.y + second.y) * 0.5, (first.z + second.z) * 0.5);
  }

  private void drawWorldLine(
      ImDrawList drawList, Camera camera, Vec3 from, Vec3 to, int color, float thickness) {
    float[] first = project(camera, from);
    float[] second = project(camera, to);
    if (first == null || second == null) return;
    drawList.addLine(first[0], first[1], second[0], second[1], color, thickness);
  }

  private float[] project(Camera camera, Vec3 point) {
    if (!inFront(camera, point)) return null;
    Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
    return new float[] {
      (float) ((projected.x + 1) * 0.5 * ImGui.getIO().getDisplaySizeX()),
      (float) ((1 - projected.y) * 0.5 * ImGui.getIO().getDisplaySizeY())
    };
  }

  private void drawHealthBar(
      ImDrawList drawList, LivingEntity entity, float minX, float minY, float maxX, float maxY) {
    float health = Math.max(0.0f, Math.min(1.0f, entity.getHealth() / entity.getMaxHealth()));
    int background = ImColor.rgba(0, 0, 0, 220);
    int red = ImColor.rgba(230, 45, 45, 255);
    int green = ImColor.rgba(45, 220, 85, 255);
    float gap = 3.0f;
    float thickness = 2.0f;
    switch (healthBarPosition.getValue()) {
      case LEFT -> {
        float left = minX - gap - thickness;
        drawList.addRectFilled(left - 1, minY - 1, left + thickness + 1, maxY + 1, background);
        drawVerticalHealth(
            drawList, left, minY, left + thickness, maxY, health, green, red, background);
      }
      case RIGHT -> {
        float left = maxX + gap;
        drawList.addRectFilled(left - 1, minY - 1, left + thickness + 1, maxY + 1, background);
        drawVerticalHealth(
            drawList, left, minY, left + thickness, maxY, health, green, red, background);
      }
      case TOP -> {
        float top = minY - gap - thickness;
        drawList.addRectFilled(minX - 1, top - 1, maxX + 1, top + thickness + 1, background);
        drawHorizontalHealth(
            drawList, minX, top, maxX, top + thickness, health, red, green, background);
      }
      case BOTTOM -> {
        float top = maxY + gap;
        drawList.addRectFilled(minX - 1, top - 1, maxX + 1, top + thickness + 1, background);
        drawHorizontalHealth(
            drawList, minX, top, maxX, top + thickness, health, red, green, background);
      }
    }
  }

  private void drawVerticalHealth(
      ImDrawList drawList,
      float minX,
      float minY,
      float maxX,
      float maxY,
      float health,
      int top,
      int bottom,
      int background) {
    drawList.addRectFilledMultiColor(minX, minY, maxX, maxY, top, top, bottom, bottom);
    float hiddenBottom = maxY - (maxY - minY) * health;
    if (hiddenBottom > minY) drawList.addRectFilled(minX, minY, maxX, hiddenBottom, background);
  }

  private void drawHorizontalHealth(
      ImDrawList drawList,
      float minX,
      float minY,
      float maxX,
      float maxY,
      float health,
      int left,
      int right,
      int background) {
    drawList.addRectFilledMultiColor(minX, minY, maxX, maxY, left, right, right, left);
    float hiddenLeft = minX + (maxX - minX) * health;
    if (hiddenLeft < maxX) drawList.addRectFilled(hiddenLeft, minY, maxX, maxY, background);
  }

  private boolean inFront(Camera camera, Vec3 point) {
    Vec3 offset = point.subtract(camera.position());
    Vector3fc forward = camera.forwardVector();
    return offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z() > 0.05;
  }

  private enum BarPosition {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM
  }

  private enum Box2DMode {
    FULL,
    CORNERS
  }

  private record ModelLine(Vec3 start, Vec3 end) {}
}
