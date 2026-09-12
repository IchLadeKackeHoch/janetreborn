package dev.lifus.janetreborn.feature.misc;

import dev.lifus.janetreborn.service.friend.FriendStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;

public final class FriendsModule extends Module {
  private final Minecraft minecraft;
  private final FriendStore friends;
  private final BoolSetting middleClickFriends =
      add(new BoolSetting("middle_click_friends", "Middle-click to Toggle", true));

  public FriendsModule(Minecraft minecraft, FriendStore friends) {
    super("Friends", "Protects friends from combat modules and ESP", Category.MISC);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.friends = Objects.requireNonNull(friends, "friends");
  }

  @Override
  protected void onEnable() {
    friends.setActive(true);
  }

  @Override
  protected void onDisable() {
    friends.setActive(false);
  }

  public boolean handleMiddleClick() {
    if (!isEnabled()
        || !middleClickFriends.getValue()
        || minecraft.player == null
        || minecraft.gui.screen() != null
        || !minecraft.mouseHandler.isMouseGrabbed()
        || !(minecraft.hitResult instanceof EntityHitResult hit)
        || !(hit.getEntity() instanceof Player player)
        || player == minecraft.player) return false;
    friends.toggle(player);
    return true;
  }

  public List<FriendStore.Friend> getFriends() {
    return friends.getFriends();
  }

  public boolean removeFriend(UUID uuid) {
    return friends.remove(uuid);
  }

  public boolean addFriend(String usernameOrUuid) {
    String input = usernameOrUuid == null ? "" : usernameOrUuid.trim();
    if (input.isEmpty()) return false;
    if (minecraft.level != null) {
      for (Player player : minecraft.level.players()) {
        if (player.getName().getString().equalsIgnoreCase(input)) {
          return friends.add(player.getUUID(), player.getName().getString());
        }
      }
    }
    try {
      UUID uuid = UUID.fromString(input);
      return friends.add(uuid, input);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public boolean clearFriends() {
    return friends.clear();
  }
}
