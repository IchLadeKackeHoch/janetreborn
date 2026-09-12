package dev.lifus.janetreborn.feature.misc;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.radio.RadioPlayer;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class RadioModule extends Module {
  private final Minecraft minecraft;
  private final RadioPlayer player = new RadioPlayer();
  private final ModeSetting<Station> station;
  private final BoolSetting syncMinecraftVolume;
  private final NumberSetting<Integer> volume;
  private Station playingStation;

  @Subscribe private final Listener<TickEvent> clientTick = this::tick;

  public RadioModule(Minecraft minecraft) {
    super("Radio", "Plays a selectable internet music station", Category.MISC);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    station = add(new ModeSetting<>("Station", Station.CHILLSYNTH));
    syncMinecraftVolume =
        add(new BoolSetting("sync_with_minecraft_volume", "Use Minecraft Master Volume", true));
    volume = add(new NumberSetting<>("volume", "Volume", 50, 0, 100, 1).unit("%"));
    volume.visibleWhen(syncMinecraftVolume, false);
  }

  @Override
  protected void onEnable() {
    updateVolume();
    startSelectedStation();
  }

  @Override
  protected void onDisable() {
    playingStation = null;
    player.stop();
  }

  @Override
  protected void onCleanup() {
    player.close();
  }

  public String getPlaybackStatus() {
    return player.getStatus();
  }

  private void tick(TickEvent ignored) {
    updateVolume();
    if (playingStation != station.getValue()) startSelectedStation();
  }

  private void updateVolume() {
    float selectedVolume =
        syncMinecraftVolume.getValue()
            ? minecraft.options.getSoundSourceVolume(SoundSource.MASTER)
            : volume.getValue() / 100.0f;
    player.setVolume(selectedVolume);
  }

  private void startSelectedStation() {
    playingStation = station.getValue();
    player.play(playingStation.streamUrl);
  }

  public enum Station {
    NIGHTRIDE("https://stream.nightride.fm/nightride.mp3"),
    CHILLSYNTH("https://stream.nightride.fm/chillsynth.mp3"),
    DATAWAVE("https://stream.nightride.fm/datawave.mp3"),
    SPACESYNTH("https://stream.nightride.fm/spacesynth.mp3"),
    DARKSYNTH("https://stream.nightride.fm/darksynth.mp3"),
    HORRORSYNTH("https://stream.nightride.fm/horrorsynth.mp3"),
    EBSM("https://stream.nightride.fm/ebsm.mp3"),
    RADIO_CALICO("https://stream.radio-calico.com/calico.mp3"),
    NCS("https://stream.laut.fm/ncsradio"),
    GAMING_ROCK("https://streams.radiobob.de/gamingrock/mp3-192/streams.radiobob.de/"),
    CLASSIC_ROCK("https://streams.radiobob.de/bob-classicrock/mp3-192/mediaplayer"),
    ALTERNATIVE_ROCK("https://streams.radiobob.de/bob-alternative/mp3-192/mediaplayer"),
    ROCK_HITS("https://streams.radiobob.de/bob-rockhits/mp3-192/mediaplayer"),
    METAL("https://streams.radiobob.de/bob-metal/mp3-192/mediaplayer"),
    PUNK("https://streams.radiobob.de/bob-punk/mp3-192/mediaplayer"),
    GRUNGE("https://streams.radiobob.de/bob-grunge/mp3-192/mediaplayer"),
    BLUES("https://streams.radiobob.de/blues/mp3-192/streams.radiobob.de/");

    private final String streamUrl;

    Station(String streamUrl) {
      this.streamUrl = streamUrl;
    }

    String streamUrl() {
      return streamUrl;
    }
  }
}
