package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.digimon.CompoundAttacks;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.AttackStance;
import com.digicube.fabric.client.render.DigimonRenderState;
import net.minecraft.client.model.geom.ModelPart;

import java.util.ArrayList;
import java.util.List;

/**
 * A drawn weapon's stance and the strikes of a compound as the compiled NativeGroundModel draws them, on every model whose
 * catalog entry names a stance ({@code stances}; Leomon's Lion Sword):
 * <ul>
 * <li>the weapon's parts follow the stance every frame: the drawn ones (the hand prop) shown from the draw's swap to the
 *     sheathe's and hidden otherwise, the stowed ones (the hilt on the back) the other way round, over any clip;</li>
 * <li>a model that plays the stance's clips has the parts its look names, a draw and a sheathe as long as the stance's
 *     and looping holds;</li>
 * <li>the draw, the hold (walking and running), the sheathe, a strike chained into another, a pounce cast from a run and
 *     another move's strike while the weapon is out all pose without fault.</li>
 * </ul>
 */
public final class NativeStanceRegressionTest {
    private static int checks;

    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        int models = 0;
        for (var definition : NativeGroundModel.definitions().values()) {
            if (definition.stances().isEmpty()) continue;
            models++;
            var species = DigimonSpeciesRegistry.getOrThrow(definition.species());
            var root = definition.createLayer().bakeRoot();
            var model = new NativeGroundModel(root, definition);
            var animations = new NativeAnimationSet(root, definition.animation());
            var mesh = NativeModelGeometry.mesh(definition.geometry());
            for (var entry : definition.stances().entrySet()) {
                String move = entry.getKey();
                var attack = species.attacks().stream().filter(a -> a.id().getPath().equals(move)).findFirst().orElse(null);
                var compound = CompoundAttacks.get(attack);
                check(compound != null && compound.stance() != null, definition.species() + ": the catalog's stance " + move + " is a stance move of its sheet");
                var spec = compound.stance();
                List<ModelPart> drawn = new ArrayList<>(), stowed = new ArrayList<>();
                for (var p : mesh.parts()) {
                    ModelPart part = root;
                    for (String child : p.path()) part = part.getChild(child);
                    if (entry.getValue().drawn().stream().anyMatch(p.name()::startsWith)) drawn.add(part);
                    if (entry.getValue().stowed().stream().anyMatch(p.name()::startsWith)) stowed.add(part);
                }
                if (animations.has(move + "_draw")) {
                    check(!drawn.isEmpty() && !stowed.isEmpty(), move + ": a model that draws the weapon has its drawn and stowed parts");
                    check(Math.abs(animations.length(move + "_draw") - spec.draw()) < .01F
                            && animations.has(move + "_sheathe") && Math.abs(animations.length(move + "_sheathe") - spec.sheathe()) < .01F,
                            move + ": the draw and the sheathe last as long as the stance's");
                    check(animations.has(move + "_hold") && animations.loops(move + "_hold")
                            && (!animations.has(move + "_hold_run") || animations.loops(move + "_hold_run")), move + ": the holds loop");
                }
                var state = new DigimonRenderState();
                state.modelScale = species.body().modelScale();
                state.gaitShares = new float[]{1, 0, 0, 0};
                // the stance through its phases, standing and running
                for (var phase : AttackStance.Phase.values()) {
                    int length = switch (phase) { case DRAW -> spec.draw(); case HOLD -> 40; case SHEATHE -> spec.sheathe(); };
                    for (float t = 0; t <= length; t += .5F) for (float run : new float[]{0, .5F, 1}) {
                        state.stanceMove = move; state.stanceCompound = compound; state.stancePhase = phase; state.stanceTicks = t;
                        state.stanceDrawn = AttackStance.drawn(spec, phase, t);
                        state.groundAnimationAmount = run > 0 ? 1 : 0; state.groundRunAmount = run; state.groundAnimationPhase = t;
                        state.ageInTicks = t;
                        model.setupAnim(state);
                        for (ModelPart part : drawn) check(part.visible == state.stanceDrawn, move + ": the drawn parts out between the swaps (" + phase + " " + t + ")");
                        for (ModelPart part : stowed) check(part.visible != state.stanceDrawn, move + ": the stowed parts back otherwise (" + phase + " " + t + ")");
                    }
                }
                // no stance: the weapon stowed
                state.stanceMove = null; state.stanceCompound = null; state.stancePhase = null; state.stanceDrawn = false;
                model.setupAnim(state);
                for (ModelPart part : drawn) check(!part.visible, move + ": no stance, no weapon in the hand");
                for (ModelPart part : stowed) check(part.visible, move + ": no stance, the weapon on the back");
                // strikes while the weapon holds: its own forms (one chained into another, a pounce from a run and from the air),
                // and another move's (the hold keeps the weapon arm)
                state.stanceMove = move; state.stanceCompound = compound; state.stancePhase = AttackStance.Phase.HOLD; state.stanceTicks = 20;
                state.stanceDrawn = true;
                List<com.digicube.digimon.DigimonAttack> strikes = new ArrayList<>();
                for (var form : compound.forms()) strikes.add(form.attack());
                for (var other : species.attacks()) {
                    var c = CompoundAttacks.get(other);
                    if (c != null && c != compound) for (var form : c.forms()) strikes.add(form.attack());
                }
                for (var strike : strikes) for (boolean run : new boolean[]{false, true}) for (float t = 0; t < strike.durationTicks(); t += .5F) {
                    state.attackDefinition = strike; state.attackAnimationName = strike.id().getPath(); state.attackAnimation.start(0);
                    state.attackRun = run; state.attackAir = !run && t > 1;
                    state.chainFrom = compound.forms().getFirst().attack().id().getPath(); state.chainFromTime = 6; state.sinceChain = t;
                    state.ageInTicks = t;
                    model.setupAnim(state);
                    for (ModelPart part : drawn) check(part.visible, move + ": the weapon stays out through a strike");
                }
                state.attackAnimation.stop(); state.attackAnimationName = null; state.attackDefinition = null; state.chainFrom = null;
            }
        }
        check(models >= 1, "at least one model names a stance");
        System.out.println("PASS: stances hold their parts and pose through draw, hold, sheathe and strikes (" + checks + " checks, " + models + " models)");
    }
}
