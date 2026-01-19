package com.example.mowziestrackscompat.client;

import com.example.mowziestrackscompat.MowziesTracksCompat;
import com.example.mowziestrackscompat.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Coexistence goals with OverhauledMusic:
 * - OverhauledMusic continues to handle Biome/Mob/LowHP.
 * - This mod ONLY adds a boss layer for Mowzie bosses.
 * - When a Mowzie boss is active, we suppress OTHER music (vanilla + OverhauledMusic) so you never hear two tracks.
 * - We also hard-cancel Mowzie's built-in boss themes.
 */
@Mod.EventBusSubscriber(modid = MowziesTracksCompat.MODID, value = Dist.CLIENT)
public class MowziesMusicCompatClient {

    // Boss entity IDs (Mowzie's Mobs 1.7.3)
    private static final ResourceLocation FROSTMAW_ID = new ResourceLocation("mowziesmobs", "frostmaw");
    private static final ResourceLocation WROUGHTNAUT_ID = new ResourceLocation("mowziesmobs", "ferrous_wroughtnaut");
    private static final ResourceLocation UMVUTHI_ID = new ResourceLocation("mowziesmobs", "umvuthi");

    // Boss theme SoundEvent IDs (from mowziesmobs sounds.json)
    private static final Set<ResourceLocation> MOWZIE_THEME_EVENTS = Set.of(
            new ResourceLocation("mowziesmobs", "music.ferrous_wroughtnaut_theme"),
            new ResourceLocation("mowziesmobs", "music.frostmaw_theme"),
            new ResourceLocation("mowziesmobs", "music.umvuthi_theme"),

            // Sculptor themes (optional, but we mute them too so you never get double music)
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_combat"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_ending"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_intro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level1_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level2_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_1"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_level3_2"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_outro"),
            new ResourceLocation("mowziesmobs", "music.sculptor_theme_transition")
    );

    // Underlying file paths used by those events (extra safety)
    private static final Set<ResourceLocation> MOWZIE_THEME_FILES = Set.of(
            new ResourceLocation("mowziesmobs", "music/ferrous_wroughtnaut"),
            new ResourceLocation("mowziesmobs", "music/frostmaw"),
            new ResourceLocation("mowziesmobs", "music/umvuthi"),
            new ResourceLocation("mowziesmobs", "music/sculptor/combat"),
            new ResourceLocation("mowziesmobs", "music/sculptor/ending"),
            new ResourceLocation("mowziesmobs", "music/sculptor/intro"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level1_1"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level1_2"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level2_1"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level2_2"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level3_1"),
            new ResourceLocation("mowziesmobs", "music/sculptor/level3_2"),
            new ResourceLocation("mowziesmobs", "music/sculptor/outro"),
            new ResourceLocation("mowziesmobs", "music/sculptor/transition")
    );

    // Start boss music when you're within 20 blocks
    private static final double BOSS_RADIUS = 20.0D;

    // Simple smooth fade (seconds = FADE_TICKS / 20)
    private static final int FADE_TICKS = 40; // 2 seconds

    private static int tick = 0;

    private static ResourceLocation currentBossId = null;
    private static BossMusicSound currentMusic = null;

    // While true, we suppress other music so you never get overlap.
    // We keep this true while fading out too.
    private static boolean bossOverrideActive = false;

    // When a boss ends, keep bossOverrideActive on for FADE_TICKS to prevent overlap during fade-out.
    private static int releaseTicks = 0;

    /**
     * Cancels Mowzie's built-in boss themes ALWAYS.
     * When bossOverrideActive is true, also cancels OverhauledMusic + vanilla music (so you don't hear two tracks).
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance inst = event.getSound();
        if (inst == null) return;

        ResourceLocation loc = inst.getLocation();
        if (loc == null) return;

        // Always cancel Mowzie boss themes, even if they are not played on SoundSource.MUSIC.
        if (MOWZIE_THEME_EVENTS.contains(loc) || MOWZIE_THEME_FILES.contains(loc)) {
            event.setSound(null);
            return;
        }

        // From here on, only deal with MUSIC category to avoid muting unrelated SFX.
        if (inst.getSource() != SoundSource.MUSIC) return;

        if (!bossOverrideActive) return;

        // Suppress OverhauledMusic tracks while our boss layer is active.
        if ("overhauledmusic".equals(loc.getNamespace())) {
            event.setSound(null);
            return;
        }

        // Suppress vanilla music while boss layer is active.
        if ("minecraft".equals(loc.getNamespace())) {
            String path = loc.getPath();
            if (path != null && (path.startsWith("music") || path.contains("music."))) {
                event.setSound(null);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        tick++;

        // Handle fade-out release window every tick.
        if (releaseTicks > 0) {
            releaseTicks--;
            if (releaseTicks == 0 && currentBossId == null) {
                // Fade-out window ended; allow other music again.
                bossOverrideActive = false;
                stopBossMusicImmediateIfStuck();
            }
        }

        // Scan for bosses 2x/sec (cheap and responsive enough).
        if (tick % 10 != 0) return;

        ResourceLocation boss = findNearbyBoss(mc);

        if (boss == null) {
            // If we just lost the boss, fade out and keep override active during fade to prevent overlap.
            if (currentBossId != null) {
                currentBossId = null;
                beginBossFadeOut();
                bossOverrideActive = true;
                releaseTicks = FADE_TICKS;
            }
            return;
        }

        // Boss is present
        bossOverrideActive = true;
        releaseTicks = 0;

        if (!boss.equals(currentBossId)) {
            // Boss changed / just started.
            currentBossId = boss;

            // Stop/fade any currently playing music (vanilla + OverhauledMusic + anything else in MUSIC)
            boolean fadedOverhauled = fadeOutOverhauledMusic(FADE_TICKS);
            if (!fadedOverhauled) {
                stopAllMusicCategory(mc);
            }

            // Extra safety: stop any Mowzie theme that might already be running (even if it started before we cancelled events)
            stopMowzieThemes(mc);

            // Start our boss music
            startBossMusic(boss);
        }
    }

    private static ResourceLocation findNearbyBoss(Minecraft mc) {
        var level = mc.level;
        var player = mc.player;

        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(BOSS_RADIUS))) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.isAlive()) continue;

            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(le.getType());
            if (id == null) continue;

            if (FROSTMAW_ID.equals(id) || WROUGHTNAUT_ID.equals(id) || UMVUTHI_ID.equals(id)) {
                return id;
            }
        }
        return null;
    }

    private static void startBossMusic(ResourceLocation bossId) {
        // Stop old boss music immediately so we don't overlap two boss tracks.
        if (currentMusic != null) {
            try { currentMusic.stopNow(); } catch (Throwable ignored) {}
        }

        Minecraft mc = Minecraft.getInstance();

        // Stop vanilla music manager just in case
        try { mc.getMusicManager().stopPlaying(); } catch (Throwable ignored) {}

        SoundEvent ev;
        if (FROSTMAW_ID.equals(bossId)) {
            ev = ModSounds.FROSTMAW_BOSS.get();
        } else if (WROUGHTNAUT_ID.equals(bossId)) {
            ev = ModSounds.FERROUS_WROUGHTNAUT_BOSS.get();
        } else if (UMVUTHI_ID.equals(bossId)) {
            ev = ModSounds.UMVUTHI_BOSS.get();
        } else {
            return;
        }

        currentMusic = new BossMusicSound(ev);
        mc.getSoundManager().play(currentMusic);
    }

    private static void beginBossFadeOut() {
        if (currentMusic == null) return;
        try {
            currentMusic.beginFadeOut();
        } catch (Throwable ignored) {
            // If anything goes wrong, just stop it.
            try { currentMusic.stopNow(); } catch (Throwable ignored2) {}
            currentMusic = null;
        }
    }

    private static void stopBossMusicImmediateIfStuck() {
        // By the time releaseTicks hits 0, the sound SHOULD have stopped itself.
        // If it's somehow still around, stop it to prevent lingering.
        if (currentMusic != null) { 
            try { currentMusic.stopNow(); } catch (Throwable ignored) {}
        }
        currentMusic = null;
    }

    /**
     * Attempts to fade out OverhauledMusic's currently playing tracks (if OverhauledMusic is installed).
     * This gives you smooth transitions without hard-stopping the whole MUSIC channel.
     *
     * @return true if OverhauledMusic was detected and the hook ran.
     */
    private static boolean fadeOutOverhauledMusic(int ticks) {
        try {
            Class<?> clientEvents = Class.forName("com.overhauledmusic.client.ClientEvents");
            Field directorField = clientEvents.getDeclaredField("DIRECTOR");
            directorField.setAccessible(true);
            Object director = directorField.get(null);
            if (director == null) return false;

            Field instancesField = director.getClass().getDeclaredField("instances");
            instancesField.setAccessible(true);
            Object instancesObj = instancesField.get(director);
            if (!(instancesObj instanceof Map)) return true;

            Map<?, ?> map = (Map<?, ?>) instancesObj;
            for (Object inst : map.values()) {
                if (inst == null) continue;
                try {
                    Method fadeTo = inst.getClass().getMethod("fadeTo", float.class, int.class);
                    fadeTo.invoke(inst, 0.0F, ticks);
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    Method setActive = inst.getClass().getMethod("setActive", boolean.class);
                    setActive.invoke(inst, false);
                } catch (NoSuchMethodException ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Stops ALL currently playing MUSIC (best-effort). This is what prevents overlap with OverhauledMusic.
     */
    private static void stopAllMusicCategory(Minecraft mc) {
        try {
            mc.getSoundManager().stop((net.minecraft.resources.ResourceLocation)null, SoundSource.MUSIC);
        } catch (Throwable ignored) {
            // If the method signature differs, we fall back to vanilla MusicManager stop.
            try { mc.getMusicManager().stopPlaying(); } catch (Throwable ignored2) {}
        }
    }

    /**
     * Extra safety: forcibly stop any Mowzie boss theme sounds that might already be playing.
     * We try a few likely channels because Mowzie may not always use MUSIC.
     */
    private static void stopMowzieThemes(Minecraft mc) {
        for (ResourceLocation rl : MOWZIE_THEME_EVENTS) {
            stopSoundOnCommonChannels(mc, rl);
        }
        for (ResourceLocation rl : MOWZIE_THEME_FILES) {
            stopSoundOnCommonChannels(mc, rl);
        }
    }

    private static void stopSoundOnCommonChannels(Minecraft mc, ResourceLocation rl) {
        try {
            mc.getSoundManager().stop(rl, SoundSource.MUSIC);
        } catch (Throwable ignored) {}
        try {
            mc.getSoundManager().stop(rl, SoundSource.RECORDS);
        } catch (Throwable ignored) {}
        try {
            mc.getSoundManager().stop(rl, SoundSource.AMBIENT);
        } catch (Throwable ignored) {}
    }

    private static class BossMusicSound extends AbstractTickableSoundInstance {
        private int age = 0;
        private boolean fadingOut = false;
        private int outAge = 0;

        protected BossMusicSound(SoundEvent event) {
            super(event, SoundSource.MUSIC, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F; // fade in
            this.relative = true;
            this.x = 0;
            this.y = 0;
            this.z = 0;
        }

        @Override
        public void tick() {
            if (!fadingOut) {
                age++;
                float t = Math.min(1.0F, age / (float) FADE_TICKS);
                this.volume = t;
            } else {
                outAge++;
                float t = Math.max(0.0F, 1.0F - (outAge / (float) FADE_TICKS));
                this.volume = t;
                if (outAge >= FADE_TICKS) {
                    this.stop();
                }
            }
        }

        public void beginFadeOut() {
            this.fadingOut = true;
            this.outAge = 0;
        }

        public void stopNow() {
            this.stop();
        }
    }
}
