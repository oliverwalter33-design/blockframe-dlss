package de.morau.nvidiadlss;

import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * Dev-only frame-deterministic third-person driver for reproducible A/B
 * captures. It is disabled unless the master developer-diagnostics switch and
 * {@code nvidia_dlss.devSequenceMotion} are explicitly set. The rendered
 * player transform and animation are derived
 * from the capture-frame offset instead of simulation ticks, so different
 * renderer frame times cannot change the camera path or articulated pose.
 */
final class ThirdPersonDlaaSequenceDriver {
    enum Mode {
        NONE,
        COMPOSITE
    }

    private record Sample(
        double x,
        double y,
        double z,
        float yaw,
        float walkPosition,
        float walkSpeed,
        float attack,
        boolean crouching
    ) {}

    private static final int START = DeveloperDiagnostics.enabled()
        ? intSetting("nvidia_dlss.devSequenceStartFrame", 180)
        : 180;
    private static final int FRAMES = DeveloperDiagnostics.enabled()
        ? Math.max(0, intSetting("nvidia_dlss.devSequenceFrames", 0))
        : 0;
    private static final long FIXED_DAY_TIME = DeveloperDiagnostics.enabled()
        ? longSetting("nvidia_dlss.devSequenceDayTime", 6000L)
        : 6000L;
    private static final Mode MODE = DeveloperDiagnostics.enabled()
        ? modeSetting()
        : Mode.NONE;

    private static LocalPlayer ownedPlayer;
    private static double originX;
    private static double originY;
    private static double originZ;
    private static float originYaw;
    private static float originPitch;
    private static boolean originalNoPhysics;
    private static boolean originalNoGravity;

    private ThirdPersonDlaaSequenceDriver() {}

    static void update(int frame) {
        if (
            !DeveloperDiagnostics.enabled()
                || MODE == Mode.NONE
                || FRAMES == 0
        ) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        int offset = frame - START;
        if (offset < 0 || offset >= FRAMES || minecraft.player == null) {
            release(minecraft.options);
            return;
        }

        LocalPlayer player = minecraft.player;
        if (offset == 0 || ownedPlayer != player) {
            acquire(player);
        }

        Options options = minecraft.options;
        setMovement(options, false, false, false, false, false, false);
        if (minecraft.level != null) {
            minecraft.level.setTimeFromServer(FIXED_DAY_TIME);
        }

        Sample sample = sample(offset);
        player.noPhysics = true;
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.setPose(sample.crouching ? Pose.CROUCHING : Pose.STANDING);
        player.setPos(sample.x, sample.y, sample.z);
        player.setYRot(sample.yaw);
        player.setXRot(originPitch);
        player.setOldPosAndRot(
            new Vec3(sample.x, sample.y, sample.z),
            sample.yaw,
            originPitch
        );
        player.yBodyRot = sample.yaw;
        player.yBodyRotO = sample.yaw;
        player.yHeadRot = sample.yaw;
        player.yHeadRotO = sample.yaw;
        setWalkAnimation(minecraft, player, sample);
        player.oAttackAnim = sample.attack;
        player.attackAnim = sample.attack;
        player.swinging = sample.attack > 0.0F;
        player.swingingArm = InteractionHand.MAIN_HAND;
        player.swingTime = Math.round(sample.attack * 6.0F);
    }

    private static void acquire(LocalPlayer player) {
        release(Minecraft.getInstance().options);
        ownedPlayer = player;
        originX = player.getX();
        originY = player.getY();
        originZ = player.getZ();
        originYaw = player.getYRot();
        originPitch = player.getXRot();
        originalNoPhysics = player.noPhysics;
        originalNoGravity = player.isNoGravity();
    }

    private static Sample sample(int offset) {
        double x = originX;
        double z = originZ;
        float walkPosition = 0.0F;
        float walkSpeed = 0.0F;
        for (int frame = 1; frame <= offset; frame++) {
            int phase = Math.floorMod(frame, 240);
            float yaw = originYaw + 0.65F * frame;
            double forward = 0.0;
            double left = 0.0;
            float speed = 0.0F;
            if (phase >= 20 && phase < 70) {
                forward = 0.035;
                speed = 0.62F;
            } else if (phase >= 70 && phase < 120) {
                forward = 0.055;
                speed = 1.0F;
            } else if (phase >= 120 && phase < 160) {
                left = 0.035;
                speed = 0.66F;
            } else if (phase >= 160 && phase < 190) {
                forward = 0.040;
                speed = 0.82F;
            } else if (phase >= 190 && phase < 220) {
                forward = 0.018;
                speed = 0.35F;
            }
            double radians = Math.toRadians(yaw);
            x += -Math.sin(radians) * forward + Math.cos(radians) * left;
            z += Math.cos(radians) * forward + Math.sin(radians) * left;
            walkPosition += speed * 0.72F;
            walkSpeed = speed;
        }

        int phase = Math.floorMod(offset, 240);
        double y = originY;
        if (phase >= 160 && phase < 190) {
            double jump = (phase - 160) / 30.0;
            y += Math.sin(Math.PI * jump) * 0.82;
        }
        float attack = 0.0F;
        if (phase >= 220) {
            float cycle = Math.floorMod(phase - 220, 12) / 12.0F;
            attack = (float) Math.sin(Math.PI * cycle);
        }
        return new Sample(
            x,
            y,
            z,
            originYaw + 0.65F * offset,
            walkPosition,
            walkSpeed,
            attack,
            phase >= 190 && phase < 220
        );
    }

    private static void setWalkAnimation(
        Minecraft minecraft,
        LocalPlayer player,
        Sample sample
    ) {
        player.walkAnimation.stop();
        if (sample.walkSpeed <= 0.0F) {
            return;
        }
        float partial = minecraft
            .getDeltaTracker()
            .getGameTimeDeltaPartialTick(false);
        player.walkAnimation.setSpeed(sample.walkSpeed);
        player.walkAnimation.update(sample.walkSpeed, 1.0F, 1.0F);
        float denominator = sample.walkSpeed * (1.0F + partial);
        float scale = denominator > 1.0e-6F
            ? sample.walkPosition / denominator
            : 1.0F;
        player.walkAnimation.update(sample.walkSpeed, 1.0F, scale);
    }

    private static void release(Options options) {
        setMovement(options, false, false, false, false, false, false);
        if (ownedPlayer == null) {
            return;
        }
        ownedPlayer.noPhysics = originalNoPhysics;
        ownedPlayer.setNoGravity(originalNoGravity);
        ownedPlayer.swinging = false;
        ownedPlayer.oAttackAnim = 0.0F;
        ownedPlayer.attackAnim = 0.0F;
        ownedPlayer = null;
    }

    private static void setMovement(
        Options options,
        boolean forward,
        boolean left,
        boolean sprint,
        boolean jump,
        boolean crouch,
        boolean attack
    ) {
        options.keyUp.setDown(forward);
        options.keyLeft.setDown(left);
        options.keySprint.setDown(sprint);
        options.keyJump.setDown(jump);
        options.keyShift.setDown(crouch);
        options.keyAttack.setDown(attack);
    }

    private static int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(
                System.getProperty(key, Integer.toString(fallback))
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longSetting(String key, long fallback) {
        try {
            return Long.parseLong(
                System.getProperty(key, Long.toString(fallback))
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Mode modeSetting() {
        String value = System.getProperty(
            "nvidia_dlss.devSequenceMotion",
            "NONE"
        );
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            NvidiaDlssMod.LOGGER.warn(
                "Unbekannte IQ-A/B-Bewegungssequenz {}; verwende NONE",
                value
            );
            return Mode.NONE;
        }
    }
}
