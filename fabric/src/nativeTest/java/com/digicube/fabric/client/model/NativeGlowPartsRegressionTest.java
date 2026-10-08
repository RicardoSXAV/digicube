package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.fabric.client.render.DigimonRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Glow parts ({@code glow_parts} in ground_models.json) as the compiled NativeGroundModel draws them, on every model whose
 * entry names some (posed where they show: in a clip that shows them when they are hidden at rest, as an aura or speed
 * lines are) and on Leomon's model with a subtree and a lone part of its mesh named here:
 * <ul>
 * <li>the body's pass leaves them out and draws the rest at the body's light;</li>
 * <li>the glow's pass, as the renderer submits it, draws them alone and full-bright, where the body's pose puts them;</li>
 * <li>the two passes draw the whole body once between them, and each puts back what it changed (the parts are shared);</li>
 * <li>a model without glow parts has no glow pass (a body of fire is lit whole by its renderer), and an entry naming a
 *     part its mesh lacks is refused.</li>
 * </ul>
 */
public final class NativeGlowPartsRegressionTest {
    private static int checks;
    /** A dim light (block 3, sky 5): the body's own, which its other parts keep. */
    private static final int DIM = 3 << 4 | 5 << 20;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    public static void main(String[] args) throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        int models = 0;
        for (var definition : NativeGroundModel.definitions().values()) {
            if (definition.glowParts().isEmpty()) continue;
            var root = definition.createLayer().bakeRoot();
            var model = new NativeGroundModel(root, definition);
            split(definition.species().toString(), model, shown(model, root, definition));
            models++;
        }
        // Leomon's model with glow parts named here: a part with all it carries and a lone part on another branch.
        var leomon = NativeGroundModel.definitions().get(Constants.id("leomon"));
        var named = pick(leomon);
        split("leomon with " + named, new NativeGroundModel(leomon.createLayer().bakeRoot(), withGlowParts(leomon, named)), resting());
        models++;
        // No glow parts, no glow pass: a body of fire is drawn whole and full-bright by its renderer.
        var meramon = NativeGroundModel.definitions().get(Constants.id("meramon"));
        check(meramon.glow() && meramon.glowParts().isEmpty()
                && new NativeGroundModel(meramon.createLayer().bakeRoot(), meramon).glowPass() == null, "a body of fire has no pass for parts");
        check((new NativeGroundModel(leomon.createLayer().bakeRoot(), leomon).glowPass() == null) == leomon.glowParts().isEmpty(),
                "a model has a glow pass only with glow parts");
        boolean refused = false;
        try { new NativeGroundModel(leomon.createLayer().bakeRoot(), withGlowParts(leomon, List.of("no_such_part"))); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "a glow part the mesh lacks is refused");
        System.out.println("PASS: glow parts draw full-bright in a pass of their own, the body at its light (" + checks + " checks, " + models + " models)");
    }

    /** The body standing in its idle. */
    private static DigimonRenderState resting() {
        var state = new DigimonRenderState();
        state.gaitShares = new float[]{1, 0, 0, 0};
        state.ageInTicks = 7;
        return state;
    }

    /**
     * A pose that shows the model's glow parts: its idle when one shows there, else the middle of the first stretch of a clip
     * that shows one (a membrane its clips show, read from the clips' visibility keys), played as an attack.
     */
    private static DigimonRenderState shown(NativeGroundModel model, ModelPart root, NativeGroundModel.Definition definition) {
        var state = resting();
        model.setupAnim(state);
        var parts = NativeModelGeometry.mesh(definition.geometry()).parts();
        for (var p : parts) if (definition.glowParts().contains(p.name()) && shown(root, p.path())) return state;
        com.google.gson.JsonObject clips;
        try (var input = NativeGlowPartsRegressionTest.class.getResourceAsStream("/assets/" + definition.animation().getNamespace() + "/"
                + definition.animation().getPath())) {
            clips = net.minecraft.util.GsonHelper.parse(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject("clips");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        for (var clip : clips.entrySet()) {
            var body = clip.getValue().getAsJsonObject();
            if (!body.has("visibility")) continue;
            for (var steps : body.getAsJsonObject("visibility").entrySet()) {
                if (!definition.glowParts().contains(steps.getKey())) continue;
                var keys = steps.getValue().getAsJsonArray();
                for (int i = 0; i < keys.size(); i++) {
                    if (!keys.get(i).getAsJsonArray().get(1).getAsBoolean()) continue;
                    float from = keys.get(i).getAsJsonArray().get(0).getAsFloat();
                    float until = i + 1 < keys.size() ? keys.get(i + 1).getAsJsonArray().get(0).getAsFloat() : body.get("length").getAsFloat();
                    state.attackAnimationName = clip.getKey();
                    state.attackAnimation.start(0);
                    state.ageInTicks = (from + until) / 2;
                    return state;
                }
            }
        }
        check(false, definition.species() + ": no pose shows its glow parts");
        return state;
    }

    /** The body's pass, the glow's as the renderer submits it, and the whole body before and after, compared. */
    @SuppressWarnings("unchecked")
    private static void split(String label, NativeGroundModel model, DigimonRenderState state) {
        model.setupAnim(state);
        var whole = draw(model, state, DIM);
        state.glowSplit = true;
        var body = draw(model, state, DIM);
        List<Object[]> submits = new ArrayList<>();
        var collector = (SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("submitModel")) submits.add(arguments);
                    return null;
                });
        model.submitGlow(state, new PoseStack(), collector, null, OverlayTexture.NO_OVERLAY, -1, 0);
        check(submits.size() == 1 && submits.getFirst()[0] == model.glowPass() && submits.getFirst()[1] == state,
                label + ": the renderer's layer submits one glow pass of the body's state");
        int light = (int) submits.getFirst()[4];
        check(light == LightCoordsUtil.FULL_BRIGHT, label + ": the glow pass is submitted full-bright");
        var glow = draw((Model<DigimonRenderState>) submits.getFirst()[0], state, light);
        state.glowSplit = false;
        var after = draw(model, state, DIM);
        check(!glow.at.isEmpty() && glow.light.stream().allMatch(l -> l == LightCoordsUtil.FULL_BRIGHT), label + ": the glow parts draw full-bright");
        check(body.at.size() < whole.at.size() && body.light.stream().allMatch(l -> l == DIM), label + ": the rest of the body keeps its light");
        var together = counts(body.at); counts(glow.at).forEach((at, n) -> together.merge(at, n, Integer::sum));
        check(together.equals(counts(whole.at)), label + ": the two passes draw the whole body once, the glow parts where its pose puts them");
        check(counts(after.at).equals(counts(whole.at)) && after.light.stream().allMatch(l -> l == DIM), label + ": each pass puts back what it changed");
    }

    /** One pass drawn as the model feature renderer draws a submit: posed for the state, then every vertex captured. */
    private static Capture draw(Model<DigimonRenderState> model, DigimonRenderState state, int light) {
        model.setupAnim(state);
        var capture = new Capture();
        model.renderToBuffer(new PoseStack(), capture, light, OverlayTexture.NO_OVERLAY, -1);
        return capture;
    }

    private static Map<String, Integer> counts(List<String> at) {
        var counts = new HashMap<String, Integer>();
        for (String a : at) counts.merge(a, 1, Integer::sum);
        return counts;
    }

    /**
     * Two parts of a model's mesh, shown as it stands: the first with faces of its own and parts under it, below the top
     * three of the hierarchy, and the first part with faces and nothing under it on another branch.
     */
    private static List<String> pick(NativeGroundModel.Definition definition) {
        var root = definition.createLayer().bakeRoot();
        var model = new NativeGroundModel(root, definition);
        var state = new DigimonRenderState();
        state.gaitShares = new float[]{1, 0, 0, 0};
        model.setupAnim(state);
        var parts = NativeModelGeometry.mesh(definition.geometry()).parts();
        String subtree = null, lone = null;
        String[] under = null;
        for (var p : parts) {
            if (p.quads().length == 0 || !shown(root, p.path())) continue;
            boolean carries = java.util.Arrays.stream(parts).anyMatch(q -> q.path().length > p.path().length
                    && java.util.Arrays.equals(q.path(), 0, p.path().length, p.path(), 0, p.path().length));
            if (subtree == null && carries && p.path().length >= 4) { subtree = p.name(); under = p.path(); }
        }
        for (var p : parts) {
            if (p.quads().length == 0 || !shown(root, p.path()) || p.path().length < 3) continue;
            boolean carries = java.util.Arrays.stream(parts).anyMatch(q -> q.path().length > p.path().length
                    && java.util.Arrays.equals(q.path(), 0, p.path().length, p.path(), 0, p.path().length));
            boolean below = under != null && p.path().length >= under.length && java.util.Arrays.equals(p.path(), 0, under.length, under, 0, under.length);
            if (!carries && !below) { lone = p.name(); break; }
        }
        check(subtree != null && lone != null, definition.species() + ": a part with parts under it and a lone part on another branch");
        return List.of(subtree, lone);
    }

    /** Whether a part and every part above it are shown. */
    private static boolean shown(ModelPart root, String[] path) {
        ModelPart part = root;
        for (String name : path) { part = part.getChild(name); if (!part.visible) return false; }
        return true;
    }

    /** The definition with other glow parts (the record's own constructor, every other component as it is). */
    private static NativeGroundModel.Definition withGlowParts(NativeGroundModel.Definition definition, List<String> parts) throws ReflectiveOperationException {
        var components = NativeGroundModel.Definition.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals("glowParts") ? parts : components[i].getAccessor().invoke(definition);
        }
        return NativeGroundModel.Definition.class.getDeclaredConstructor(types).newInstance(values);
    }

    /** Every vertex drawn: where it is (to a thousandth of a block) and the light it carries. */
    private static final class Capture implements VertexConsumer {
        final List<String> at = new ArrayList<>();
        final List<Integer> light = new ArrayList<>();

        private void vertex(float x, float y, float z, int light) {
            at.add(Math.round(x * 1000) + "," + Math.round(y * 1000) + "," + Math.round(z * 1000));
            this.light.add(light);
        }

        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) { vertex(x, y, z, light); }
        @Override public VertexConsumer addVertex(float x, float y, float z) { vertex(x, y, z, 0); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { light.set(light.size() - 1, u | v << 16); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
