package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.AttackBox;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.AttackTravelSync;
import com.digicube.entity.DigimonAnimationEvents;
import com.digicube.fabric.client.render.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Actual compiled renderer transforms versus native vertices and server contact volumes. Lizard Dance is three forms on
 * three stacked uses: the dash (the knife, while the root travels), the right and the left forearm cuts (the left one
 * with the knife); each blade stays inside its cuboid through its window at eight headings and three heights. Akinakes
 * is three leaps: the greatsword inside its cuboid as it is driven into the floor, the impact on the floor ahead of the
 * feet, nothing hurting in flight. Then the model: the knife points ahead of the fist; the trousers are one garment
 * (each leg a seat, a thigh and a shin sleeve, mitred at the hip and the knee) whose seams stay closed, whose faces
 * never turn inside out and which keep the pelvis inside them through the gait and every attack; and the hanging
 * cloth (three hinged parts the clips never key) is pushed out by the thighs without flying up or swaying aside.
 */
public final class NativeDinohyumonRegressionTest {
    private static final float SCALE = .32F;
    private static final double MARGIN = .25;
    /** Key reduction moves a vertex at the end of a long chain by less than this many blocks. */
    private static final double TOLERANCE = .008;
    private static int checks;
    private static double worst;

    private static PoseStack stack(float yaw, Vec3 origin) {
        var s = new PoseStack(); s.translate(origin.x, origin.y, origin.z);
        TectonicWaveRenderer.applyWorldTransform(s, yaw, SCALE);
        s.translate(0, EntityModel.MODEL_Y_OFFSET, 0); return s;
    }
    private static Map<String, List<Vec3>> rendered(ModelPart root, float yaw, Vec3 origin) {
        Map<String, List<Vec3>> actual = new HashMap<>();
        root.visit(stack(yaw, origin), (pose, path, index, cube) -> {
            var vertices = actual.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>());
            for (var polygon : cube.polygons) for (var vertex : polygon.vertices()) {
                var v = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
                vertices.add(new Vec3(v.x, v.y, v.z));
            }
        });
        return actual;
    }
    private static void near(Vec3 a, Vec3 b, String label) {
        double error = a.distanceTo(b); worst = Math.max(worst, error); checks++;
        if (error > TOLERANCE) throw new AssertionError(label + " error=" + error + " expected=" + a + " actual=" + b);
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** Every drawn vertex lies inside the server's cuboid, which extends past what is drawn by the margin on every side. */
    private static void encloses(AttackBox box, List<Vec3> vertices, double margin, String label) {
        Vec3[] axes = {box.x(), box.y(), box.z()};
        check(vertices != null && !vertices.isEmpty(), label + " has drawn vertices");
        for (int i = 0; i < 3; i++) {
            Vec3 axis = axes[i];
            double length = axis.length(), low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (Vec3 v : vertices) {
                double along = v.subtract(box.center()).dot(axis) / length;
                low = Math.min(low, along); high = Math.max(high, along);
            }
            double error = Math.max(Math.abs(high + margin - length), Math.abs(low - margin + length)); worst = Math.max(worst, error); checks++;
            if (error > TOLERANCE) throw new AssertionError(label + " drawn " + low + ".." + high + " against +-" + length + " margin " + margin);
        }
    }

    /** A sleeve's faces as drawn: four corners each (blocks) and the drawn normal. */
    private record Face(Vec3[] corners, Vec3 normal) {}
    private static List<Face> faces(ModelPart root, String... path) {
        var out = new ArrayList<Face>();
        var stack = new PoseStack(); ModelPart part = root;
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        var pose = stack.last();
        var polygons = new ArrayList<ModelPart.Polygon>();
        part.visit(new PoseStack(), (p, sub, index, cube) -> { if (sub.isEmpty()) polygons.addAll(List.of(cube.polygons)); });
        for (var polygon : polygons) {
            var corners = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                var v = polygon.vertices()[i];
                var w = pose.pose().transformPosition(v.worldX(), v.worldY(), v.worldZ(), new org.joml.Vector3f());
                corners[i] = new Vec3(w.x, w.y, w.z);
            }
            var n = pose.transformNormal(polygon.normal(), new org.joml.Vector3f());
            out.add(new Face(corners, new Vec3(n.x, n.y, n.z)));
        }
        return out;
    }
    private static org.joml.Matrix4f frame(ModelPart root, String... path) {
        var stack = new PoseStack(); ModelPart part = root;
        for (String name : path) { part = part.getChild(name); part.translateAndRotate(stack); }
        return new org.joml.Matrix4f(stack.last().pose());
    }
    /** Both triangles of a face turned the way it is drawn (1), both turned away (-1), or folded across (0). */
    private static int facing(Face f) {
        var c = f.corners;
        double a = c[1].subtract(c[0]).cross(c[2].subtract(c[0])).dot(f.normal), b = c[2].subtract(c[0]).cross(c[3].subtract(c[0])).dot(f.normal);
        return a > 1.0E-9 && b > 1.0E-9 ? 1 : a < -1.0E-9 && b < -1.0E-9 ? -1 : 0;
    }
    /** How each sleeve's faces turn at rest, the way every pose must keep them. */
    private static final Map<String, int[]> REST_FACING = new HashMap<>();

    /**
     * The trousers on the pose just set up: at the hip and the knee every ring vertex of one sleeve meets one of the
     * next (1e-4 blocks), no sleeve face is turned inside out, and the pelvis's seat (its lower corners 5 px and more
     * either side of the middle, 1 px under the hip pivot) stays in the thigh sleeve's section, a quarter pixel at most
     * outside it; nearer the middle it is the crotch, trouser cloth between the legs.
     */
    private static void trousers(ModelPart root, String label) {
        for (String side : List.of("L", "R")) {
            var seat = faces(root, "root", "pelvis", "trouser_seat_" + side);
            var thigh = faces(root, "root", "pelvis", "thigh_" + side, "trouser_thigh_" + side);
            var shin = faces(root, "root", "pelvis", "thigh_" + side, "shin_" + side, "trouser_shin_" + side);
            // Every side face (in columns, the same on both sides of a seam) has two corners on its cut, and all of them
            // meet: the seat's (all but its lid) at the hip, the thigh sleeve's at the knee.
            int[] seams = {meeting(seat, thigh), meeting(thigh, seat), meeting(thigh, shin), meeting(shin, thigh)};
            check(seams[0] == 2 * (seat.size() - 1) && seams[1] == seams[0] && seams[2] == 2 * thigh.size() && seams[3] == seams[2],
                    label + " seams closed " + side + ": " + Arrays.toString(seams));
            String[] names = {"seat", "thigh", "shin"}; int k = 0;
            for (var sleeve : List.of(seat, thigh, shin)) {
                int[] rest = REST_FACING.computeIfAbsent(names[k] + side, n -> sleeve.stream().mapToInt(NativeDinohyumonRegressionTest::facing).toArray());
                for (int i = 0; i < sleeve.size(); i++) check(facing(sleeve.get(i)) == rest[i] && rest[i] != 0, label + " " + names[k] + " " + side + " face " + i + " keeps its side out");
                k++;
            }
            var toThigh = frame(root, "root", "pelvis", "thigh_" + side).invert().mul(frame(root, "root", "pelvis"));
            float sign = side.equals("L") ? 1 : -1;
            for (float x : new float[]{5, 9, 13.5F}) for (float z : new float[]{-8.5F, 8.5F}) {
                var p = toThigh.transformPosition(sign * x / 16, 1F / 16, z / 16, new org.joml.Vector3f()).mul(16);
                // Above the hip's cut it is in the seat; below it, in the thigh sleeve.
                double out = p.y <= 0 ? 0 : Math.max(Math.abs(p.x) - 8, Math.abs(p.z) - 9); worst = Math.max(worst, Math.max(0, out) / 16);
                check(out < .25, label + " pelvis corner " + side + " " + x + "," + z + " inside the thigh's sleeve: " + p);
            }
        }
    }
    /** How many of {@code a}'s corners meet a corner of {@code b} within 1e-4 blocks. */
    private static int meeting(List<Face> a, List<Face> b) {
        int count = 0;
        for (var f : a) for (var c : f.corners) {
            double best = Double.POSITIVE_INFINITY;
            for (var g : b) for (var d : g.corners) best = Math.min(best, c.distanceToSqr(d));
            if (best < 1.0E-8) count++;
        }
        return count;
    }

    private static DigimonAttack attack(DigimonSpecies species, String name) {
        return species.attacks().stream().filter(a -> a.id().getPath().equals(name)).findFirst().orElseThrow();
    }

    /** Each blade column of an authored attack inside its cuboid at every quarter tick it is out, eight headings, three heights. */
    private static int sweep(NativeGroundModel model, ModelPart root, DigimonRenderState state, DigimonAttack attack, String[] parts) {
        var authored = AuthoredAttacks.get(attack);
        check(authored.parts().size() >= parts.length, attack.id() + " has a column for every blade");
        state.attackDefinition = attack; state.attackAnimationName = attack.animationName(false);
        state.attackAnimation.start(0);
        var window = authored.hitWindows().getFirst();
        int swept = 0;
        for (float t = 0; t <= attack.durationTicks(); t += .25F) {
            state.ageInTicks = t; model.setupAnim(state);
            float tick = state.attackAnimation.getTimeInMillis(state.ageInTicks) / 50F;
            var boxes = authored.sample(tick);
            for (int column = 0; column < parts.length; column++) {
                if (boxes[column] == null) continue;
                check(tick >= window[0] - .2 && tick <= window[1] + .2, attack.id() + " strikes only in its window, not at " + tick);
                swept++;
                for (int h = 0; h < 8; h++) for (int elevation = -1; elevation <= 1; elevation++) {
                    float yaw = h * 45; var origin = new Vec3(13, 80 + elevation, -19);
                    encloses(boxes[column].world(origin, yaw, 0), rendered(root, yaw, origin).get(parts[column]), MARGIN, parts[column] + " " + attack.id().getPath() + " " + t + " yaw " + yaw);
                }
            }
        }
        return swept;
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("dinohyumon"));
        check(species.body().modelScale() == SCALE, "exported and installed at one scale");
        var definition = NativeGroundModel.definitions().get(species.id());
        var root = definition.createLayer().bakeRoot(); var model = new NativeGroundModel(root, definition);
        var sleeves = SleeveBends.rig(root, definition.bends()); // the model's own, for poses set without setupAnim
        var state = new DigimonRenderState(); state.modelScale = SCALE; state.attackAnimation.start(0);
        state.cloth = new ClothChains.State();

        // The moves: the sword first (a leap, tried whenever it is worth it), the dance filling its cooldown.
        var dance = attack(species, "lizard_dance"); var sword = attack(species, "akinakes");
        check(species.attacks().getFirst() == sword && species.attacks().size() == 2, "the sword is tried before the dance");
        var danceForms = AuthoredAttacks.forms(dance); var swordForms = AuthoredAttacks.forms(sword);
        check(danceForms != null && danceForms.choice() == AuthoredAttacks.FormChoice.COMBO && danceForms.all().stream().map(a -> a.id().getPath()).toList()
                .equals(List.of("lizard_dance", "lizard_dance_right", "lizard_dance_left")), "the dance: dash, right cut, left cut, as a combo");
        check(swordForms != null && swordForms.choice() == AuthoredAttacks.FormChoice.REACH && swordForms.all().size() == 3, "the sword: three leaps by distance");
        check(AuthoredAttacks.get(dance).charges() == 3 && AuthoredAttacks.get(sword).charges() == 1, "three stacked uses of the dance, one of the sword");
        for (var form : danceForms.all()) check(AuthoredAttacks.move(form) == dance, form.id() + " spends the dance's uses");
        var keys = danceForms.all().stream().map(f -> AuthoredAttacks.get(f).key()).toList();
        check(keys.equals(List.of("forward", "right", "left")), "a rider picks them with W, D and A: " + keys);
        check(com.digicube.entity.AttackForms.rider(dance, true, false, false) == dance
                && com.digicube.entity.AttackForms.rider(dance, false, false, true) == danceForms.all().get(1)
                && com.digicube.entity.AttackForms.rider(dance, true, true, false) == danceForms.all().get(2)
                && com.digicube.entity.AttackForms.rider(dance, false, false, false) == dance, "movement keys pick the forms");
        for (int f = 0; f < DigimonAnimationEvents.MAX_FORMS; f++) for (int index : new int[]{0, 1, DigimonAnimationEvents.MAX_ATTACKS - 1}) {
            byte event = DigimonAnimationEvents.start(index, false, f);
            check(event < 0 && DigimonAnimationEvents.attackIndex(event) == index && DigimonAnimationEvents.form(event) == f && !DigimonAnimationEvents.mirrored(event)
                    && event != DigimonAnimationEvents.CANCEL && event != DigimonAnimationEvents.CONTACT, "form " + f + " of attack " + index + " starts in one event");
        }

        // The dash: travels about three blocks, one blow of the knife, no leap; the cuts stand.
        var stab = AuthoredAttacks.get(dance);
        check(dance.kind() == DigimonAttack.Kind.BOX_SWEEP && stab.rootTravel() && stab.leap() == null && stab.hitWindows().size() == 1 && stab.maxHits() == 1,
                "the dash is one travelling thrust");
        double travel = dance.motion().sample(dance.durationTicks()).travel();
        check(AttackTravelSync.drivesRoot(dance) && travel > 2.7 && travel < 3.3, "the dash carries its caster about three blocks: " + travel);
        check(AttackTravelSync.steps(dance, 7) == AttackTravelSync.LUNGE_STEPS, "lunge sync while the dash travels");
        for (var cut : danceForms.all().subList(1, 3)) {
            check(!AuthoredAttacks.get(cut).travels() && AuthoredAttacks.get(cut).hitWindows().size() == 1 && cut.durationTicks() <= 16, cut.id() + " is a quick standing cut");
        }
        check(stab.particles() == com.digicube.entity.StrikeParticles.STEEL, "steel");
        check(sweep(model, root, state, dance, new String[]{"cleaver"}) >= 10, "the knife is swept through the thrust");
        check(sweep(model, root, state, danceForms.all().get(1), new String[]{"crescent_R"}) >= 10, "the right blade is swept through its cut");
        check(sweep(model, root, state, danceForms.all().get(2), new String[]{"crescent_L", "cleaver"}) >= 20, "the left blade and the knife are swept through the cut");

        // The leaps: the jump lands before its blade can hurt, the blade is inside its cuboid as it is driven in, the
        // impact sits on the floor about two blocks ahead of the feet, nothing hurts in flight; longer leaps hit harder.
        DigimonAttack previous = null;
        for (var leapForm : swordForms.all()) {
            var authored = AuthoredAttacks.get(leapForm); var leap = authored.leap();
            check(leapForm.kind() == DigimonAttack.Kind.BOX_BURST && leap != null && !authored.rootTravel() && authored.hitWindows().size() == 1
                    && authored.hitWindows().getFirst()[0] >= leap.land() - 1, leapForm.id() + " lands before its blade can hurt");
            check(AttackTravelSync.steps(leapForm, leap.launch() + 2) == AttackTravelSync.LUNGE_STEPS && !AttackTravelSync.drivesRoot(leapForm), "lunge sync through the flight");
            check(leapForm.motion().minimumRange() >= 2 && leapForm.range() > leapForm.motion().minimumRange(), "a jump is not a melee move");
            if (previous != null) check(leapForm.power() > previous.power() && leapForm.range() > previous.range() && leap.apex() > AuthoredAttacks.get(previous).leap().apex()
                    && leap.land() - leap.launch() > AuthoredAttacks.get(previous).leap().land() - AuthoredAttacks.get(previous).leap().launch()
                    && leapForm.motion().minimumRange() < previous.range(), "a longer leap flies longer, hits harder and leaves no gap in distance: " + leapForm.id());
            previous = leapForm;
            check(sweep(model, root, state, leapForm, new String[]{"akinakes"}) >= 8, "the blade is swept through the impact");
            var ring = authored.sample(leap.land() + .5)[1];
            check(ring != null && ring.center().z > 1.5 && ring.center().z < 2.6 && Math.abs(ring.center().x) < .4 && ring.bounds().minY > -.1
                    && ring.bounds().getXsize() >= 1.9 && ring.bounds().getXsize() <= 2.3, "the impact sits on the floor about two blocks ahead: " + (ring == null ? null : ring.center()));
            for (int t = 0; t < leap.land() - 1; t++) check(authored.sample(t)[0] == null && authored.sample(t)[1] == null, "nothing hurts before the landing");
        }

        // The effects: one model for each move, a clip for each form, every part the volumes name.
        for (var forms : List.of(danceForms, swordForms)) {
            var first = AuthoredAttacks.get(forms.all().getFirst());
            var effect = new NativeEffectModel(NativeEffectModel.createLayer(first.effect()).bakeRoot(), first.effect());
            var fxRoot = NativeEffectModel.createLayer(first.effect()).bakeRoot();
            var clips = new HashSet<String>();
            for (var form : forms.all()) {
                var authored = AuthoredAttacks.get(form);
                check(authored.effect().equals(first.effect()) && effect.has(authored.effectClip()) && clips.add(authored.effectClip()), form.id() + " plays a clip of its own");
                for (var group : authored.visualParts()) for (String name : group) check(fxRoot.hasChild(name), authored.effect() + " draws " + name);
                for (String name : authored.contactParts()) check(fxRoot.hasChild(name), authored.effect() + " sparks " + name);
            }
        }

        var animations = new NativeAnimationSet(root, definition.animation());
        for (var forms : List.of(danceForms, swordForms)) for (var form : forms.all()) check(animations.has(form.animationName(false)), "a clip for " + form.id());

        // The knife points ahead of the fist; the trousers at rest.
        state.attackAnimation.stop(); state.attackAnimationName = null; state.attackDefinition = null;
        root.getAllParts().forEach(ModelPart::resetPose);
        var still = rendered(root, 0, Vec3.ZERO);
        double fistZ = still.get("hand_L").stream().mapToDouble(Vec3::z).average().orElseThrow();
        double fistX = still.get("hand_L").stream().mapToDouble(Vec3::x).average().orElseThrow();
        double tipZ = still.get("cleaver").stream().mapToDouble(Vec3::z).max().orElseThrow();
        double knifeX = still.get("cleaver").stream().mapToDouble(v -> Math.abs(v.x - fistX)).max().orElseThrow();
        check(tipZ - fistZ > .5 && knifeX < .35, "the knife points forward from the fist, not out to the side: ahead " + (tipZ - fistZ) + " aside " + knifeX);
        check(definition.bends().size() == 4 && root.getChild("root").getChild("pelvis").hasChild("trouser_seat_L"), "both legs bend at the hip and the knee");
        model.setupAnim(state); trousers(root, "rest");

        // Gait: walk and run share the phase; the cloth hangs from the belt and swings clear of a forward thigh, without
        // flying up or swaying aside.
        check(animations.has("run") && animations.blendNames().contains("run") && animations.blendNames().contains("walk"), "walk and run lattices");
        check(definition.cloth().size() == 1 && definition.cloth().getFirst().segments().equals(List.of("cloth_0", "cloth_1", "cloth_2")), "one three-segment cloth chain");
        var belt = root.getChild("root").getChild("pelvis").getChild("belt"); var cloth0 = belt.getChild("cloth_0");
        check(Math.abs(cloth0.y - 3) < .01 && cloth0.z < -11 && cloth0.z > -12.5 && Math.abs(cloth0.getChild("cloth_1").y - 14) < .01, "hinges on the panel's cut lines");
        for (String clip : List.of("idle", "walk", "run", "lizard_dance", "lizard_dance_right", "lizard_dance_left", "akinakes", "akinakes_mid", "akinakes_long")) {
            root.getAllParts().forEach(ModelPart::resetPose); animations.apply(clip, 7.25F, 1);
            var c0 = belt.getChild("cloth_0");
            check(c0.xRot == 0 && c0.zRot == 0 && c0.getChild("cloth_1").xRot == 0, clip + " never keys the cloth");
        }
        state.cloth = new ClothChains.State();
        double forwardMost = 0, clothMost = 0, rollMost = 0;
        for (int frame = 0; frame < 160; frame++) {
            state.ageInTicks = frame * .5F; state.groundAnimationAmount = 1; state.groundAnimationPhase = frame * .5F; state.groundRunAmount = frame < 80 ? 0 : 1;
            state.x = frame * .05; state.bodyRot = 0;
            model.setupAnim(state);
            var thigh = root.getChild("root").getChild("pelvis").getChild("thigh_L"); var c0 = belt.getChild("cloth_0");
            for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot), "finite gait and cloth");
            check(c0.xRot <= .06F, "the cloth never falls back into the pelvis: " + c0.xRot);
            var c1 = c0.getChild("cloth_1"); var c2 = c1.getChild("cloth_2");
            double hem = -(c0.xRot + c1.xRot + c2.xRot);
            check(hem < Math.toRadians(81), "the cloth's hem never flies up level: " + Math.toDegrees(hem));
            if (frame > 20) rollMost = Math.max(rollMost, Math.abs(c0.zRot));
            if (-thigh.xRot > forwardMost) { forwardMost = -thigh.xRot; clothMost = -c0.xRot; }
            trousers(root, (frame < 80 ? "walk " : "run ") + state.groundAnimationPhase);
        }
        check(forwardMost > .3 && clothMost > .2, "with the thigh " + forwardMost + " forward the cloth swings " + clothMost);
        check(rollMost < Math.toRadians(8), "walking straight, the cloth barely sways aside: " + Math.toDegrees(rollMost));
        // Every attack, a quarter tick at a time: the trousers hold through the lunges, cuts and leaps.
        for (var forms : List.of(danceForms, swordForms)) for (var form : forms.all()) {
            state.attackDefinition = form; state.attackAnimationName = form.animationName(false); state.attackAnimation.start(0);
            for (float t = 0; t <= form.durationTicks(); t += .25F) { state.ageInTicks = t; model.setupAnim(state); trousers(root, form.id().getPath() + " " + t); }
        }
        state.attackAnimation.stop(); state.attackAnimationName = null; state.attackDefinition = null;
        for (float amount : new float[]{0, .25F, .5F, 1, 0}) {
            state.groundAnimationAmount = amount; state.groundAnimationPhase = 7.125F; model.setupAnim(state);
            for (var p : root.getAllParts()) check(Float.isFinite(p.x + p.y + p.z + p.xScale + p.yScale + p.zScale), "finite reset/gait");
        }
        if (args.length > 0) {
            try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(args[0]))) {
                for (var entry : net.minecraft.util.GsonHelper.parse(reader).getAsJsonArray("samples")) {
                    var row = entry.getAsJsonObject(); root.getAllParts().forEach(ModelPart::resetPose);
                    animations.apply(row.get("clip").getAsString(), row.get("tick").getAsFloat(), 1);
                    SleeveBends.apply(sleeves);
                    for (int h = 0; h < 8; h += 3) {
                        var origin = new Vec3(-11, 50, 27); float yaw = h * 45;
                        var actual = rendered(root, yaw, origin);
                        for (var object : row.getAsJsonArray("objects")) {
                            var o = object.getAsJsonObject(); var vertices = actual.get(o.get("name").getAsString());
                            for (var vertex : o.getAsJsonArray("points")) {
                                var p = vertex.getAsJsonArray();
                                var expected = AttackGeometry.world(origin, new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()), yaw);
                                near(expected, vertices.stream().min(Comparator.comparingDouble(expected::distanceToSqr)).orElseThrow(),
                                        "native " + row.get("clip") + " " + row.get("tick") + " " + o.get("name"));
                            }
                        }
                    }
                }
            }
        }
        Constants.LOG.info("Dinohyumon native parity: {} checks, worst error {} blocks", checks, worst);
    }
}
