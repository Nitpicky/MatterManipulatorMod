package com.ethermod.client.beam;

import com.ethermod.beam.BeamBallEntity;

import com.ethermod.EtherMod;
import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws the orb in a blocky, Minecraft style: a translucent tumbling shell cube around a bright
 * core cube, with pixels orbiting it, tinted by the orb mode, and a trail of shrinking pixel cubes.
 * Sizes snap to whole pixels. The same drawing is used for the charge preview in front of the
 * nozzle, where it grows with charge and pixels are pulled in.
 *
 * <p>Your own orbs start at the nozzle and blend into their real flight path over the first few
 * ticks, since the server spawns them at an approximate point.
 *
 * <p>Built only on the vanilla lightning render type, so it works under shader packs.
 */
@OnlyIn(Dist.CLIENT)
public class BeamBallRenderer extends EntityRenderer<BeamBallEntity> {

    private static final ResourceLocation NO_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "textures/entity/beam_ball.png");

    /** Visual radius per charge phase. */
    private static final float[] PHASE_RADIUS = {0.14f, 0.24f, 0.38f};

    private static final int TRAIL_POINTS = 16;

    private final Map<Integer, Deque<Vec3>> trails = new HashMap<>();
    private final Map<Integer, Vec3> launches = new HashMap<>();
    private static final float BLEND_TICKS = 4f;

    public BeamBallRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public boolean shouldRender(
            BeamBallEntity entity, Frustum frustum, double x, double y, double z) {
        return true; // the trail can be on screen when the orb isn't
    }

    @Override
    public void render(
            BeamBallEntity entity,
            float yaw,
            float partialTick,
            PoseStack ps,
            MultiBufferSource buf,
            int light) {
        if (com.ethermod.client.ShaderCompat.inShadowPass()) return; // see ShaderCompat
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        Vec3 worldPos = entity.getPosition(partialTick);
        float age = entity.tickCount + partialTick;

        // Own orbs: start exactly in front of the nozzle, then blend into the real path
        if (entity.getOwner() == mc.player && BeamRenderer.nozzle != null) {
            Vec3 launch =
                    launches.computeIfAbsent(
                            entity.getId(),
                            k ->
                                    BeamRenderer.orbAnchor(
                                            PHASE_RADIUS[
                                                    Math.max(0, Math.min(2, entity.getPhase()))],
                                            camPos));
            if (age < BLEND_TICKS) {
                float t = age / BLEND_TICKS;
                t = t * t * (3 - 2 * t);
                worldPos = launch.add(worldPos.subtract(launch).scale(t));
            }
        }
        Vec3 rel = worldPos.subtract(camPos);

        // Work in camera-relative space
        ps.pushPose();
        Vec3 entRel = entity.getPosition(partialTick).subtract(camPos);
        ps.translate(-entRel.x, -entRel.y, -entRel.z);
        Matrix4f m = ps.last().pose();
        VertexConsumer vc = com.ethermod.client.Fx.glow();

        OrbMode mode = entity.getMode();
        float[] col =
                mode == OrbMode.IGNITE && entity.isSoulFire()
                        ? new float[] {.18f, .9f, 1f, 1f}
                        : colorFor(mode, entity.getBeamColor());
        int phase = Math.max(0, Math.min(2, entity.getPhase()));
        float radius = PHASE_RADIUS[phase];
        float time = age;

        // ---- Trail: shrinking pixel cubes (not for a hook on its rope, or a pollen cloud) ----
        if (mode != OrbMode.TETHER && mode != OrbMode.MASS && !entity.isDrifting()) {
            Deque<Vec3> trail = trails.computeIfAbsent(entity.getId(), k -> new ArrayDeque<>());
            if (trail.isEmpty() || trail.peekFirst().distanceToSqr(worldPos) > 0.04) {
                trail.addFirst(worldPos);
                while (trail.size() > TRAIL_POINTS) trail.removeLast();
            }
            int i = 0;
            for (Vec3 p : trail) {
                if (i++ == 0) continue;
                float t = (float) i / (TRAIL_POINTS + 1);
                float h = BeamFx.px(radius * 0.6f * (1f - t));
                BeamFx.spinCube(
                        vc,
                        m,
                        p.subtract(camPos),
                        h,
                        time * 0.2 + i,
                        0.5,
                        col[0],
                        col[1],
                        col[2],
                        0.6f * (1f - t));
            }
        }
        if (trails.size() > 64) pruneTrails(mc);

        switch (mode) {
            case WATER, LAVA -> drawBlob(vc, m, rel, radius, col, time, entity.getId());
            case FROST -> drawShard(vc, m, rel, entity.getDeltaMovement(), col, time);
            case IGNITE -> drawEmber(vc, m, rel, col, time);
            case GROWTH -> {
                if (entity.isDrifting()) drawCloud(vc, m, rel, col, time, entity.getPhase());
                else drawOrb(vc, m, rel, radius * 0.7f, col, time, 1f);
            }
            case TETHER -> {
                BeamFx.spinCube(vc, m, rel, BeamFx.PX * 2, time * 0.3, 0.7, 0.9f, 0.9f, 1f, 0.95f);
                if (entity.isAnchor())
                    drawZipline(vc, m, mc, entity, rel, camPos, partialTick, time);
                else drawRope(vc, m, mc, entity, rel, camPos, partialTick, time);
            }
            case GUST -> drawGust(vc, m, rel, time, phase, 1f);
            case MASS -> {}
            default -> drawOrb(vc, m, rel, radius, col, time, 1f);
        }
        ps.popPose();
        // Mass Driver: the block itself, tumbling
        if (mode == OrbMode.MASS && !entity.getCarried().isAir()) {
            Vec3 blockAt = rel.subtract(entRel);
            ps.pushPose();
            ps.translate(blockAt.x, blockAt.y, blockAt.z);
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(entity.getYRot()));
            ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(entity.getXRot()));
            // Steady Hand: while the throw charges the block turns see-through, so the landing
            // ghost and the spot behind it show
            if (aimingThrow(entity)) drawGhostBlock(ps, buf, entity.getCarried(), THROW_ALPHA, light);
            else drawBlock(ps, buf, entity.getCarried(), Vec3.ZERO, time, 1.0f, light);
            ps.popPose();
        }
    }

    /** Water and Lava: a wobbling cluster of pixel cubes, like a lobbed blob of liquid. */
    private static void drawBlob(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            float radius,
            float[] col,
            float time,
            int seed) {
        float h = BeamFx.px(radius * 0.8f);
        BeamFx.spinCube(vc, m, c, h, time * 0.1, 0.4, col[0], col[1], col[2], 0.8f);
        for (int k = 0; k < 5; k++) {
            double a = time * 0.2 + k * 1.2566 + seed;
            Vec3 o =
                    new Vec3(
                            Math.cos(a) * h * 0.9,
                            Math.sin(a * 1.7) * h * 0.7,
                            Math.sin(a) * h * 0.9);
            BeamFx.cube(
                    vc,
                    m,
                    c.add(o),
                    BeamFx.PX * 1.5f,
                    mix(col[0], 1, 0.3f),
                    mix(col[1], 1, 0.3f),
                    mix(col[2], 1, 0.3f),
                    0.85f);
        }
    }

    /** Frost: a thin ice shard pointing along its flight. */
    private static void drawShard(
            VertexConsumer vc, Matrix4f m, Vec3 c, Vec3 vel, float[] col, float time) {
        drawShard(vc, m, c, vel, col, time, 1f);
    }

    private static void drawShard(
            VertexConsumer vc, Matrix4f m, Vec3 c, Vec3 vel, float[] col, float time, float s) {
        Vec3 dir = vel.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : vel.normalize();
        Vec3 tip = c.add(dir.scale(0.35 * s)), tail = c.subtract(dir.scale(0.35 * s));
        BeamFx.prism(
                vc, m, tail, tip, BeamFx.PX * 1.5f * s, time * 0.2, col[0], col[1], col[2], 0.55f);
        BeamFx.prism(
                vc,
                m,
                c.subtract(dir.scale(0.2 * s)),
                tip.add(dir.scale(0.06 * s)),
                BeamFx.PX * 0.7f * s,
                time * 0.2,
                0.95f,
                1f,
                1f,
                0.95f);
    }

    /** Ignite: a small flickering ember. */
    private static void drawEmber(VertexConsumer vc, Matrix4f m, Vec3 c, float[] col, float time) {
        drawEmber(vc, m, c, col, time, 1f);
    }

    private static void drawEmber(
            VertexConsumer vc, Matrix4f m, Vec3 c, float[] col, float time, float s) {
        float flick = ((int) (time * 0.7f)) % 2 == 0 ? 1f : 0.8f;
        BeamFx.spinCube(
                vc,
                m,
                c,
                BeamFx.PX * 2.5f * flick * s,
                time * 0.3,
                0.8,
                col[0],
                col[1] * 0.7f,
                col[2] * 0.4f,
                0.6f);
        BeamFx.spinCube(vc, m, c, BeamFx.PX * 1.2f * s, -time * 0.4, 0.8, 1f, 0.95f, 0.6f, 0.95f);
    }

    /** Charged Growth: a loose, slowly turning cloud of pollen pixels. */
    private static void drawCloud(
            VertexConsumer vc, Matrix4f m, Vec3 c, float[] col, float time, int phase) {
        drawCloud(vc, m, c, col, time, phase, 1f);
    }

    private static void drawCloud(
            VertexConsumer vc, Matrix4f m, Vec3 c, float[] col, float time, int phase, float s) {
        drawCloud(vc, m, c, col, time, phase, s, 0.75f);
    }

    static void drawCloud(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            float[] col,
            float time,
            int phase,
            float s,
            float alpha) {
        int n = 14 + 8 * phase;
        float spread = (0.7f + 0.3f * phase) * s;
        for (int i = 0; i < n; i++) {
            double a1 = i * 2.399963 + time * 0.03;
            double a2 = Math.acos(1 - 2 * ((i + 0.5) / n));
            double rr = spread * (0.5 + 0.5 * Math.sin(i * 1.7 + time * 0.05));
            Vec3 o =
                    new Vec3(
                            Math.cos(a1) * Math.sin(a2) * rr,
                            Math.cos(a2) * rr * 0.5,
                            Math.sin(a1) * Math.sin(a2) * rr);
            boolean yellow = i % 4 == 0;
            BeamFx.cube(
                    vc,
                    m,
                    c.add(o),
                    BeamFx.PX * (i % 3 == 0 ? 1f : 0.6f) * Math.max(0.5f, Math.min(1f, s * 2)),
                    yellow ? 1f : col[0],
                    yellow ? 0.95f : col[1],
                    yellow ? 0.5f : col[2],
                    alpha);
        }
    }

    /** Gust: a small swirl of wind pixels. */
    static void drawGust(
            VertexConsumer vc, Matrix4f m, Vec3 c, float time, int phase, float s) {
        int n = 10 + 6 * phase;
        float r = (0.2f + 0.12f * phase) * s;
        for (int i = 0; i < n; i++) {
            double a = i * 2.399963 + time * 0.6;
            double h = ((i * 0.37) % 1.0 - 0.5) * r * 1.6;
            double rr = r * (0.4 + 0.6 * ((i * 0.61) % 1.0));
            Vec3 p = c.add(Math.cos(a) * rr, h, Math.sin(a) * rr);
            BeamFx.cube(
                    vc, m, p, BeamFx.PX * (i % 3 == 0 ? 1f : 0.6f) * s, 0.85f, 0.97f, 0.95f, 0.55f);
        }
        BeamFx.spinCube(
                vc,
                m,
                c,
                BeamFx.PX * (1.5f + phase) * s,
                time * 0.5,
                0.6,
                0.8f,
                0.95f,
                0.95f,
                0.25f);
    }

    /** Mass Driver: the carried block, small and tumbling, at a camera-relative position. */
    static void drawBlock(
            PoseStack ps,
            MultiBufferSource buf,
            net.minecraft.world.level.block.state.BlockState state,
            Vec3 rel,
            float time,
            float scale,
            int light) {
        ps.pushPose();
        ps.translate(rel.x, rel.y, rel.z);

        ps.scale(scale, scale, scale);
        ps.translate(-0.5, -0.5, -0.5);
        Minecraft.getInstance()
                .getBlockRenderer()
                .renderSingleBlock(
                        state,
                        ps,
                        buf,
                        light,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    /** How visible a held Mass Driver block stays while a Steady Hand throw charges. */
    private static final float THROW_ALPHA = 0.35f;

    /** A held Mass Driver block whose owner is charging a throw with Steady Hand. */
    private static boolean aimingThrow(BeamBallEntity e) {
        return e.isMassHeld()
                && e.getOwner() instanceof net.minecraft.world.entity.player.Player p
                && p.isUsingItem()
                && com.ethermod.trait.Traits.has(p.getMainHandItem(), "tectonic.steady_hand");
    }

    /** The carried block, see-through ({@code alpha}), at the current pose. */
    private static void drawGhostBlock(
            PoseStack ps,
            MultiBufferSource buf,
            net.minecraft.world.level.block.state.BlockState state,
            float alpha,
            int light) {
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            drawBlock(ps, buf, state, Vec3.ZERO, 0, 1f, light);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var model = mc.getBlockRenderer().getBlockModel(state);
        int tint = mc.getBlockColors().getColor(state, null, null, 0);
        float r = tint == -1 ? 1f : (tint >> 16 & 255) / 255f,
                g = tint == -1 ? 1f : (tint >> 8 & 255) / 255f,
                b = tint == -1 ? 1f : (tint & 255) / 255f;
        VertexConsumer vc =
                new FadedConsumer(
                        buf.getBuffer(net.minecraft.client.renderer.Sheets.translucentCullBlockSheet()),
                        alpha);
        ps.pushPose();
        ps.translate(-0.5, -0.5, -0.5);
        for (var rt :
                model.getRenderTypes(
                        state,
                        net.minecraft.util.RandomSource.create(42),
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY))
            mc.getBlockRenderer()
                    .getModelRenderer()
                    .renderModel(
                            ps.last(),
                            vc,
                            state,
                            model,
                            r,
                            g,
                            b,
                            light,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                            net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                            rt);
        ps.popPose();
    }

    /** Passes vertices through with their alpha scaled. */
    private record FadedConsumer(VertexConsumer to, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            to.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            to.setColor(r, g, b, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            to.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            to.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            to.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            to.setNormal(x, y, z);
            return this;
        }
    }

    /** Zipline: the rope between two anchor hooks (drawn once, by the lower id). */
    private static void drawZipline(
            VertexConsumer vc,
            Matrix4f m,
            Minecraft mc,
            BeamBallEntity a,
            Vec3 aRel,
            Vec3 camPos,
            float pt,
            float time) {
        int pid = a.getPartnerId();
        if (pid < 0 || pid < a.getId() || mc.level == null) return;
        if (!(mc.level.getEntity(pid) instanceof BeamBallEntity b)) return;
        Vec3 bRel = b.getPosition(pt).subtract(camPos);
        int rgb = a.getBeamColor();
        float r = ((rgb >> 16) & 255) / 255f,
                g = ((rgb >> 8) & 255) / 255f,
                bl = (rgb & 255) / 255f;
        BeamFx.prism(vc, m, aRel, bRel, BeamFx.PX * 1.4f, time * 0.1, r, g, bl, 0.35f);
        BeamFx.prism(
                vc,
                m,
                aRel,
                bRel,
                BeamFx.PX * 0.5f,
                time * 0.1,
                mix(r, 1, 0.6f),
                mix(g, 1, 0.6f),
                mix(bl, 1, 0.6f),
                0.95f);
    }

    /**
     * Tether: the rope is a thin beam, in the owner's beam colour, from their nozzle to the hook.
     */
    private static void drawRope(
            VertexConsumer vc,
            Matrix4f m,
            Minecraft mc,
            BeamBallEntity hook,
            Vec3 hookRel,
            Vec3 camPos,
            float pt,
            float time) {
        if (!(hook.getOwner() instanceof net.minecraft.world.entity.player.Player owner)) return;
        Vec3 from;
        if (owner == mc.player && BeamRenderer.nozzle != null) {
            from = BeamRenderer.nozzle;
        } else {
            Vec3 look = owner.getViewVector(pt);
            Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
            from =
                    owner.getEyePosition(pt)
                            .add(look.scale(0.6))
                            .add(right.scale(0.3))
                            .add(0, -0.3, 0);
        }
        Vec3 a = from.subtract(camPos);
        int rgb = hook.getBeamColor();
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        BeamFx.prism(vc, m, a, hookRel, BeamFx.PX * 1.4f, time * 0.1, r, g, b, 0.35f);
        BeamFx.prism(
                vc,
                m,
                a,
                hookRel,
                BeamFx.PX * 0.5f,
                time * 0.1,
                mix(r, 1, 0.6f),
                mix(g, 1, 0.6f),
                mix(b, 1, 0.6f),
                0.95f);
    }

    private void pruneTrails(Minecraft mc) {
        Iterator<Integer> it = trails.keySet().iterator();
        while (it.hasNext()) {
            var e = mc.level == null ? null : mc.level.getEntity(it.next());
            if (e == null || e.isRemoved()) it.remove();
        }
        launches.keySet().retainAll(trails.keySet());
    }

    @Override
    public ResourceLocation getTextureLocation(BeamBallEntity entity) {
        return NO_TEXTURE;
    }

    // =========================================================================
    // Shared drawing
    // =========================================================================

    /** Draws an orb at a camera-relative position. {@code intensity} 0..1 scales brightness. */
    static void drawOrb(
            VertexConsumer vc,
            Matrix4f m,
            Vec3 c,
            float radius,
            float[] col,
            float time,
            float intensity) {
        float r = col[0], g = col[1], b = col[2];
        float wr = mix(r, 1, 0.7f), wg = mix(g, 1, 0.7f), wb = mix(b, 1, 0.7f);
        radius *= BeamRenderer.orbNearScale(c);
        // whole pixels, except when shrunk right next to the camera
        float shell = radius >= BeamFx.PX ? BeamFx.px(radius) : radius;
        float core = radius >= 2 * BeamFx.PX ? BeamFx.px(radius * 0.5f) : radius * 0.5f;
        // a pulse that steps by whole pixels
        if (((int) (time * 0.4f)) % 2 == 0) shell += shell * 0.12f;

        BeamFx.spinCube(
                vc,
                m,
                c,
                shell,
                time * 0.12,
                0.6 + 0.3 * Math.sin(time * 0.05),
                r,
                g,
                b,
                0.35f * intensity);
        BeamFx.spinCube(vc, m, c, core, -time * 0.2, 0.9, wr, wg, wb, 0.95f * intensity);

        // orbiting pixels, snapped to 8 positions per turn
        int n = 4;
        float rad = shell * 1.6f;
        for (int k = 0; k < n; k++) {
            double ang = time * 0.25 + k * Math.PI * 2 / n;
            ang = Math.round(ang / (Math.PI / 4)) * (Math.PI / 4);
            double tilt = (k % 2 == 0 ? 0.5 : -0.5);
            Vec3 p =
                    c.add(
                            Math.cos(ang) * rad,
                            Math.sin(ang) * rad * Math.sin(tilt),
                            Math.sin(ang) * rad * Math.cos(tilt));
            BeamFx.cube(
                    vc,
                    m,
                    p,
                    Math.min(BeamFx.PX * 0.5f, shell * 0.2f),
                    wr,
                    wg,
                    wb,
                    0.9f * intensity);
        }
    }

    /** Orb colour: Energy follows the beam colour, other modes have their own. */
    static float[] colorFor(OrbMode mode, int beamRgb) {
        if (mode == OrbMode.ENERGY) {
            return new float[] {
                ((beamRgb >> 16) & 255) / 255f,
                ((beamRgb >> 8) & 255) / 255f,
                (beamRgb & 255) / 255f,
                0.9f
            };
        }
        return new float[] {mode.r(), mode.g(), mode.b(), 1f};
    }

    /** Radius of the charging orb right now (also used to place it clear of the nozzle). */
    public static float previewRadius() {
        float progress = Math.max(0f, Math.min(1f, BeamRenderState.chargeProgress));
        return (0.06f + 0.16f * progress) * (1f + 0.25f * BeamRenderState.phaseFlash);
    }

    /**
     * The charging orb in front of the nozzle. {@code pos} is camera-relative. Grows with charge,
     * swells at each phase, and pulls pixels in.
     */
    public static void renderPreview(
            PoseStack ps,
            MultiBufferSource buf,
            Vec3 pos,
            int phase,
            float gameTime,
            float scaleMult,
            Vec3 offset) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var stack = Manipulator.stack(mc.player);
        OrbMode mode =
                BeamRenderState.chargeMode != null
                        ? BeamRenderState.chargeMode
                        : OrbMode.get(stack).rmbShot();
        float[] col =
                mode == OrbMode.IGNITE
                                && com.ethermod.trait.Traits.has(stack, "pyrotechnic.soulwake")
                        ? new float[] {.18f, .9f, 1f, 1f}
                        : colorFor(mode, com.ethermod.item.ManipulatorColors.beam(stack));

        // a Mass Driver throw charges what you already carry: nothing extra at the nozzle
        if (mode == OrbMode.MASS) return;
        float progress = Math.max(0f, Math.min(1f, BeamRenderState.chargeProgress));
        float radius = previewRadius() * scaleMult;

        Vec3 c = pos.add(offset);
        float k = BeamRenderer.orbNearScale(c);
        VertexConsumer vc = com.ethermod.client.Fx.glow();
        Matrix4f m = ps.last().pose();
        // The charging orb already has the shape it will fly as
        float s = radius / PHASE_RADIUS[1];
        switch (mode) {
            case WATER, LAVA -> drawBlob(vc, m, c, radius, col, gameTime, 0);
            case FROST -> drawShard(vc, m, c, mc.player.getViewVector(1f), col, gameTime, s);
            case IGNITE -> drawEmber(vc, m, c, col, gameTime, s * 1.4f);
            case GROWTH -> drawCloud(vc, m, c, col, gameTime, phase, s * 0.35f);
            case GUST -> drawGust(vc, m, c, gameTime, phase, s * 0.6f);
            default -> drawOrb(vc, m, c, radius, col, gameTime, 0.6f + 0.4f * progress);
        }

        // Inward pixels: fixed directions, sliding toward the centre
        int n = 6 + phase * 4;
        for (int i = 0; i < n; i++) {
            double a1 = i * 2.399963; // golden angle
            double a2 = Math.acos(1 - 2 * ((i + 0.5) / n));
            Vec3 dir =
                    new Vec3(
                            Math.cos(a1) * Math.sin(a2), Math.cos(a2), Math.sin(a1) * Math.sin(a2));
            float t = ((gameTime * (0.06f + 0.03f * phase) + i * 0.37f) % 1f);
            double dist = (radius + (1 - t) * (0.3 + 0.1 * phase)) * k;
            BeamFx.cube(
                    vc,
                    m,
                    c.add(dir.scale(dist)),
                    BeamFx.PX * 0.5f * k,
                    mix(col[0], 1, 0.5f),
                    mix(col[1], 1, 0.5f),
                    mix(col[2], 1, 0.5f),
                    0.9f * t);
        }
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
