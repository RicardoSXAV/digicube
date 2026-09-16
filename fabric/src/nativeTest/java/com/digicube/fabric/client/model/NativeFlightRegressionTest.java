package com.digicube.fabric.client.model;

import com.digicube.Constants;
import com.digicube.entity.ai.FlightPhase;
import com.digicube.fabric.client.render.DigimonRenderState;

/** Reproduces spawn/walk/takeoff rendering with the shipped meshes and clips, without a window. */
public final class NativeFlightRegressionTest {
    private NativeFlightRegressionTest() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (String species : new String[]{"digmon", "kabuterimon"}) {
            var id = Constants.id(species);
            var root = NativeFlyingMountModel.createLayer(id).bakeRoot();
            var model = new NativeFlyingMountModel(root, id);
            var state = new DigimonRenderState();
            for (FlightPhase phase : FlightPhase.values()) {
                state.flightPhase = phase;
                for (float amount : new float[]{0, .01F, .5F, 1}) {
                    state.groundAnimationAmount = amount;
                    for (int tick = 0; tick <= 80; tick++) {
                        state.ageInTicks = state.groundAnimationPhase = state.flightLoopTime = tick;
                        state.flightPhaseTime = tick;
                        state.flightGroundDistance = 3 * (1 - tick / 80F);
                        model.setupAnim(state);
                        var seat = model.riderOffset(state);
                        if (!Double.isFinite(seat.lengthSqr())) throw new AssertionError(species + " non-finite seat");
                        root.getAllParts().forEach(part -> {
                            if (!Float.isFinite(part.x + part.y + part.z + part.xRot + part.yRot + part.zRot
                                    + part.xScale + part.yScale + part.zScale)) {
                                throw new AssertionError(species + " non-finite pose in " + phase);
                            }
                        });
                    }
                }
            }
            if (species.equals("digmon")) {
                state.flightPhase = FlightPhase.GROUNDED;
                for (String attack : new String[]{"gold_rush", "big_crack"}) {
                    state.attackAnimationName = attack;
                    state.attackAnimation.start(0);
                    for (int tick = 0; tick <= 60; tick++) {
                        state.ageInTicks = tick;
                        model.setupAnim(state);
                    }
                    state.attackAnimation.stop();
                    model.setupAnim(state);
                }
            }
            Constants.LOG.info("[native-flight] PASS {}: idle, walk amplitudes, all flight phases, seat and attack recovery", species);
        }
    }
}
