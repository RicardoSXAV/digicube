package com.digicube.fabric.client.model;

import com.digicube.digimon.BreathAttacks;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * A breath of puffs played over the gait ({@code upper_body}: a body that breathes on its legs, a rider's jet on the move)
 * on every model that has one, a serpent's aside (its head rides its swimming neck). In game the parts above the upper body
 * play the gait while the server sheds the water from the attack's motion table, measured on the whole clip; the client
 * holds the aim part's turn to the clip's and sheds its own water off where the gait carries that part
 * ({@code NativeGroundModel.breathDrift}). So, through the whole clip:
 * <ul>
 * <li>the clip leaves the parts above the upper body unturned (a turn there would be lost in game and tip the head);</li>
 * <li>the table's head is the aim part's pivot in the clip's own pose (the server turns the mouth about it).</li>
 * </ul>
 */
public final class NativeLayeredBreathRegressionTest {
    private static int checks;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    /** Degrees the clip may turn a part above the upper body, and blocks the table's head may stand off the aim part's pivot. */
    private static final double TURN = .5, HEAD = .01;

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        int breaths = 0;
        double worstTurn = 0, worstHead = 0;
        var names = new StringBuilder();
        for (var definition : NativeGroundModel.definitions().values()) {
            if (definition.upperBody() == null || definition.aimPath().isEmpty() || definition.spine() != null) continue;
            var species = DigimonSpeciesRegistry.getOrThrow(definition.species());
            var root = definition.createLayer().bakeRoot();
            var clips = new NativeAnimationSet(root, definition.animation());
            float scale = species.body().modelScale();
            for (var attack : species.attacks()) {
                String clip = attack.id().getPath();
                if (!BreathAttacks.handles(attack) || attack.motion() == null || !clips.has(clip)) continue;
                breaths++;
                names.append(names.isEmpty() ? "" : ", ").append(definition.species().getPath()).append(' ').append(clip);
                for (float t = 0; t <= clips.length(clip); t += .25F) {
                    root.getAllParts().forEach(ModelPart::resetPose);
                    clips.apply(clip, t, 1);
                    ModelPart part = root;
                    for (int i = 0; i < definition.upperBody().size() - 1; i++) {
                        part = part.getChild(definition.upperBody().get(i));
                        var rest = part.getInitialPose();
                        var now = new org.joml.Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
                        var still = new org.joml.Quaternionf().rotationZYX(rest.zRot(), rest.yRot(), rest.xRot());
                        double turn = Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(now.dot(still)))));
                        worstTurn = Math.max(worstTurn, turn);
                        check(turn < TURN, String.format(Locale.ROOT, "%s %s turns %s, above the upper body, %.2f degrees at %.2f: in game it plays the gait",
                                definition.species(), clip, definition.upperBody().get(i), turn, t));
                    }
                    var stack = new PoseStack();
                    ModelPart aim = root; aim.translateAndRotate(stack);
                    for (String name : definition.aimPath()) { aim = aim.getChild(name); aim.translateAndRotate(stack); }
                    var at = stack.last().pose().transformPosition(0, 0, 0, new org.joml.Vector3f());
                    double off = new Vec3(at.x, 1.5 - at.y, -at.z).scale(scale).distanceTo(attack.motion().sample(t).head());
                    worstHead = Math.max(worstHead, off);
                    check(off < HEAD, String.format(Locale.ROOT, "%s %s: the motion table's head stands %.4f blocks off %s's pivot at %.2f",
                            definition.species(), clip, off, definition.aimPath().getLast(), t));
                }
            }
        }
        check(breaths >= 2, "the breaths played over the gait are found: " + names);
        System.out.println(String.format(Locale.ROOT, "Layered breath checks passed: %d checks over %d breaths (%s), parts above the upper body "
                + "turned %.3f degrees at most, the table's head within %.4f blocks of the aim part's pivot", checks, breaths, names, worstTurn, worstHead));
    }
}
