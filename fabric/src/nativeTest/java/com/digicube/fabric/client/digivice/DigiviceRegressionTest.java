package com.digicube.fabric.client.digivice;

import com.digicube.Constants;
import com.digicube.digimon.CombatMark;
import com.digicube.digimon.CrackMark;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.FreezeMark;
import com.digicube.digimon.IceCombo;
import com.digicube.party.PartyMemberView;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Pins the Digivice's rules without a window: the Digispace island is the same every time and only lets Digimon stand
 * on open ground, the camera keeps the ground under the hand and never leaves the island, and the herd follows the
 * reserve while keeping everyone where they were. The marks guide names the right Digimon behind each mark, quotes
 * the game's numbers and plays each emblem through a run that ends. Given a directory, it also writes the painted
 * island and props there as PNG, so the art can be looked at without the game.
 */
public final class DigiviceRegressionTest {
    private static int checks;

    private DigiviceRegressionTest() {}

    public static void main(String[] args) throws Exception {
        // the island
        DigispaceWorld world = DigispaceWorld.generate();
        int[] first = world.paint(), second = DigispaceWorld.generate().paint();
        check(first.length == DigispaceWorld.IMAGE_WIDTH * DigispaceWorld.IMAGE_HEIGHT && Arrays.equals(first, second), "the island is painted the same every time");
        check(world.kindAt(-5, 10) == DigispaceWorld.VOID && world.kindAt(2, 2) == DigispaceWorld.VOID && world.kindAt(DigispaceWorld.WIDTH - 2, DigispaceWorld.HEIGHT - 2) == DigispaceWorld.VOID, "the net surrounds the island");
        check(world.kindAt(160, 128) == DigispaceWorld.WATER && !world.walkable(160, 128), "the big pond is water, and nobody stands in it");
        check(!world.walkableLine(100, 128, 220, 128) && !world.walkableLine(220, 128, 100, 128), "a line across the big pond is not a way, either way round");
        int ground = 0, water = 0, path = 0, sand = 0;
        for (int y = 0; y < DigispaceWorld.HEIGHT; y++) for (int x = 0; x < DigispaceWorld.WIDTH; x++) {
            byte kind = world.kindAt(x + 0.5, y + 0.5);
            if (kind == DigispaceWorld.GRASS) ground++; else if (kind == DigispaceWorld.WATER) water++; else if (kind == DigispaceWorld.PATH) path++; else if (kind == DigispaceWorld.SAND) sand++;
        }
        check(ground > 40000 && water > 2000 && path > 1500 && sand > 3000, "grass, two ponds, a path and a sandy rim are all there");
        long trees = world.props().stream().filter(prop -> prop.type() == DigispaceWorld.PropType.OAK || prop.type() == DigispaceWorld.PropType.OAK_PALE || prop.type() == DigispaceWorld.PropType.PINE).count();
        check(trees > 40 && world.props().stream().anyMatch(prop -> prop.type() == DigispaceWorld.PropType.CRYSTAL) && world.props().stream().anyMatch(prop -> prop.type() == DigispaceWorld.PropType.ROCK), "groves, rocks and crystals are planted");
        for (DigispaceWorld.Prop prop : world.props()) {
            check(world.kindAt(prop.x(), prop.y()) == DigispaceWorld.GRASS, "every prop stands on grass");
            check(prop.type().solid() != world.walkable(prop.x(), prop.y()), "a standing prop blocks its tile, a flower patch does not");
            check(prop.type().solid() == (DigispaceArt.of(prop.type()) != null), "standing props have art, flowers are part of the ground");
        }
        check(!world.glints().isEmpty() && world.glints().stream().allMatch(glint -> world.kindAt(glint.x(), glint.y()) == DigispaceWorld.WATER), "sparkles sit on water");
        DigispaceArt.Sprite oak = DigispaceArt.of(DigispaceWorld.PropType.OAK);
        check(oak.pixels().length == oak.width() * oak.height() && oak.pixels()[oak.anchorY() * oak.width() + oak.anchorX()] != 0, "a tree's anchor is the foot of its trunk");

        // the camera
        DigispaceCamera camera = new DigispaceCamera(43, 48, 390, 190);
        check(camera.zoom() == DigispaceCamera.DEFAULT_ZOOM && Math.abs(camera.scale() - 4 / 3F) < 1e-6, "it opens one step in from the middle");
        double handX = 120, handY = 90, beforeX = camera.worldX(handX), beforeY = camera.worldY(handY);
        check(camera.zoomBy(1, handX, handY) && Math.abs(camera.worldX(handX) - beforeX) < 1e-6 && Math.abs(camera.worldY(handY) - beforeY) < 1e-6, "zooming keeps the ground under the hand");
        check(Math.abs(camera.screenX(camera.worldX(300)) - 300) < 1e-6, "screen and world coordinates are inverses");
        camera.pan(camera.centerX(), camera.centerY(), 100000, 100000);
        check(camera.worldX(43) >= -1e-6 && camera.worldY(48) >= -1e-6, "the view cannot be dragged off the island's top left");
        camera.pan(camera.centerX(), camera.centerY(), -100000, -100000);
        check(camera.worldX(43 + 390) <= DigispaceWorld.WIDTH + 1e-6 && camera.worldY(48 + 190) <= DigispaceWorld.HEIGHT + 1e-6, "nor off its bottom right");
        while (camera.zoomBy(-1, -1, -1)) { /* all the way out */ }
        check(camera.zoom() == 0 && camera.centerX() == DigispaceWorld.WIDTH / 2.0 && camera.centerY() == DigispaceWorld.HEIGHT / 2.0, "zoomed all the way out the island fits and is centred");
        check(!camera.zoomBy(-1, -1, -1), "and there is no step further out");
        int steps = 0;
        while (camera.zoomBy(1, -1, -1)) steps++;
        check(steps == camera.zoomSteps() - 1 && !camera.contains(10, 10) && camera.contains(43, 48), "five zoom steps; the view is only the content area");

        // the herd
        DigispaceHerd herd = new DigispaceHerd(world);
        // Fixed ids: where a Digimon first stands depends only on its id, so a random one made this test flaky.
        UUID a = new UUID(0x5eed_0001L, 0xa11ce_0001L), b = new UUID(0x5eed_0002L, 0xa11ce_0002L), c = new UUID(0x5eed_0003L, 0xa11ce_0003L);
        herd.sync(List.of(new DigispaceHerd.Entry(a, true, false), new DigispaceHerd.Entry(b, false, true)));
        DigispaceHerd.Walker walkerA = herd.get(a), walkerB = herd.get(b);
        check(herd.walkers().size() == 2 && walkerA.spawn == 0 && world.walkable(walkerA.x, walkerA.y) && world.walkable(walkerB.x, walkerB.y), "the reserve is simply there when the Digivice opens, on open ground");
        double[] home = herd.home(a);
        check(home[0] == walkerA.x && home[1] == walkerA.y, "a Digimon's first spot depends only on who it is");
        double bx = walkerB.x, by = walkerB.y;
        for (int tick = 0; tick < 600; tick++) {
            herd.step(tick, null);
            check(world.walkable(walkerA.x, walkerA.y), "a wandering Digimon never leaves open ground");
        }
        check(walkerA.x != home[0] || walkerA.y != home[1], "an awake Digimon wanders");
        // The rule over many homes and herd places: every step of every walker, from 400 herds of four, stays on open ground.
        int wandered = 0;
        for (int seed = 0; seed < 400; seed++) {
            DigispaceHerd sweep = new DigispaceHerd(world);
            List<DigispaceHerd.Entry> entries = new java.util.ArrayList<>();
            for (int k = 0; k < 4; k++) entries.add(new DigispaceHerd.Entry(new UUID(seed * 7919L + k, ~(seed * 104729L) + k * 31L), k % 2 == 0, false));
            sweep.sync(entries);
            for (int tick = 0; tick < 600; tick++) {
                sweep.step(tick, null);
                for (DigispaceHerd.Walker walker : sweep.walkers()) if (!world.walkable(walker.x, walker.y))
                    throw new AssertionError(String.format("FAIL: a wandering Digimon never leaves open ground (herd %d, %s at %.2f, %.2f on tick %d, ground %d)",
                            seed, walker.id, walker.x, walker.y, tick, world.kindAt(walker.x, walker.y)));
            }
            for (DigispaceHerd.Walker walker : sweep.walkers()) if (walker.x != sweep.home(walker.id)[0] || walker.y != sweep.home(walker.id)[1]) wandered++;
        }
        check(wandered > 1400, "across 1600 walkers nearly all wander, none off open ground: " + wandered);
        check(walkerB.x == bx && walkerB.y == by && !walkerB.moving(), "a defeated one lies still");
        double heldX = walkerA.x, heldY = walkerA.y;
        for (int tick = 600; tick < 700; tick++) herd.step(tick, a);
        check(walkerA.x == heldX && walkerA.y == heldY, "a Digimon in the hand does not walk");
        herd.reserve(c, 250, 60);
        boolean open = world.walkable(250, 60);
        herd.sync(List.of(new DigispaceHerd.Entry(a, true, false), new DigispaceHerd.Entry(c, true, false)));
        DigispaceHerd.Walker walkerC = herd.get(c);
        check(herd.get(b) == null && herd.get(a) == walkerA && walkerA.x == heldX, "who joins the party leaves the island, everyone else keeps their spot");
        check(walkerC.spawn == DigispaceHerd.SPAWN_TICKS && (!open || walkerC.x == 250 && walkerC.y == 60), "who comes back is rebuilt where it was set down");
        herd.reserve(b, 160, 128);
        herd.sync(List.of(new DigispaceHerd.Entry(b, false, false)));
        check(world.walkable(herd.get(b).x, herd.get(b).y), "a spot in the water is not honoured");

        if (args.length > 0) {
            File out = new File(args[0]);
            out.mkdirs();
            write(new File(out, "terrain.png"), DigispaceWorld.IMAGE_WIDTH, DigispaceWorld.IMAGE_HEIGHT, first);
            for (DigispaceWorld.PropType type : DigispaceWorld.PropType.values()) {
                DigispaceArt.Sprite art = DigispaceArt.of(type);
                if (art != null) write(new File(out, type.name().toLowerCase(java.util.Locale.ROOT) + ".png"), art.width(), art.height(), art.pixels());
            }
        }
        try {
            marks();
            tree();
        } finally {
            Util.shutdownExecutors();
        }
        System.out.println("Digivice regression test passed (" + checks + " checks)");
    }

    /** The marks guide, read from the bundled species. */
    private static void marks() {
        SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DigimonSpeciesBootstrap.registerBuiltIn();
        MarkGuide guide = new MarkGuide(DigimonSpeciesRegistry.all());
        check(guide.entries().size() == CombatMark.values().length && guide.entries().get(0).mark() == CombatMark.FREEZE, "every mark has an entry, in the guide's order");
        MarkGuide.Entry freeze = guide.get(CombatMark.FREEZE), burn = guide.get(CombatMark.BURN), crack = guide.get(CombatMark.CRACK);
        check(freeze.appliers().size() == 1 && freeze.appliers().getFirst().species().id().equals(Constants.id("garurumon")) && freeze.appliers().getFirst().attacks().size() == 2,
                "Freeze is Garurumon's, with two moves");
        check(burn.appliers().size() == 4 && burn.appliers().get(0).species().id().equals(Constants.id("agumon")) && burn.appliers().get(1).species().id().equals(Constants.id("greymon"))
                        && burn.appliers().get(2).species().id().equals(Constants.id("meramon")) && burn.appliers().get(2).attacks().size() == 2
                        && burn.appliers().get(3).species().id().equals(Constants.id("monochromon")) && burn.appliers().get(3).attacks().size() == 1,
                "Burn is Agumon's, Greymon's, Meramon's (both its moves) and Monochromon's (Volcano Strike), Rookie first");
        check(burn.shortest() == 60 && burn.longest() == 120 && freeze.shortest() == FreezeMark.FROZEN_TICKS && crack.longest() == CrackMark.CRACKED_TICKS, "a mark lasts what its moves say");
        java.util.function.BinaryOperator<String> span = (a, b) -> a + " to " + b;
        check(Arrays.equals(MarkGuide.numbers(burn, span), new Object[]{"3 to 6"}) && Arrays.equals(MarkGuide.numbers(freeze, span), new Object[]{"2.5", "4"}),
                "the sentence quotes seconds, as a span when moves differ");
        check(Arrays.equals(MarkGuide.numbers(guide.get(CombatMark.COLD), span), new Object[]{"40", "6"}) && Arrays.equals(MarkGuide.numbers(crack, span), new Object[]{"3", "25", "6"})
                && Arrays.equals(MarkGuide.numbers(guide.get(CombatMark.EXPOSED), span), new Object[]{"4", "30"}) && Arrays.equals(MarkGuide.numbers(guide.get(CombatMark.INKED), span), new Object[]{"3", "2"})
                && MarkGuide.numbers(guide.get(CombatMark.HELD), span).length == 0, "percentages and blocks come from the mechanics' own constants");

        for (MarkGuide.Entry entry : guide.entries()) {
            int length = MarkLife.length(entry);
            check(length > MarkLife.REST + MarkLife.PAUSE && MarkLife.at(entry, 0).draw() == MarkLife.Draw.NONE && MarkLife.at(entry, length - 1).draw() == MarkLife.Draw.NONE
                    && MarkLife.at(entry, length).draw() == MarkLife.at(entry, 0).draw(), entry.mark() + " runs between two rests and repeats");
            boolean anyEmblem = false, bounded = true;
            for (int tick = 0; tick < length; tick++) {
                MarkLife.Frame frame = MarkLife.at(entry, tick);
                anyEmblem |= frame.draw() != MarkLife.Draw.NONE;
                bounded &= frame.amount() >= 0 && frame.amount() <= 1 && frame.bar() >= 0 && frame.bar() <= 1 && frame.caption() != null;
            }
            check(anyEmblem && bounded, entry.mark() + " shows its emblem with amounts in range");
        }
        check(MarkLife.at(freeze, MarkLife.REST).draw() == MarkLife.Draw.BUILD && MarkLife.at(freeze, MarkLife.REST).amount() > .4F, "a bite fills nearly half the Freeze gauge at once");
        MarkLife.Frame cold = MarkLife.at(guide.get(CombatMark.COLD), MarkLife.REST + IceCombo.COLD_CHARGE_TICKS);
        check(cold.draw() == MarkLife.Draw.TIMER && cold.amount() == 1 && cold.caption().equals("slowed"), "Cold charges, then its timer starts full");
        check(MarkLife.at(crack, MarkLife.REST).caption().equals("hit") && (int) MarkLife.at(crack, MarkLife.REST).args()[0] == 1, "Crack counts its charges");
    }

    /** The digivolution tree: the beta families, room on the sheet, and the choice, the binding and the news as a partner sees them. */
    private static void tree() {
        var koromon = Constants.id("koromon"); var agumon = Constants.id("agumon"); var greymon = Constants.id("greymon");
        var meramon = Constants.id("meramon"); var seadramon = Constants.id("seadramon");
        for (String root : List.of("koromon", "tsunomon", "pukamon", "mochimon")) {
            var all = EvolutionTree.layout(Constants.id(root), 0, 0, null).all();
            check(all.size() == 7 && all.stream().allMatch(n -> EvolutionTree.family(n.id()).equals(Constants.id(root))), root + " family has every form of the beta tree");
        }
        check(EvolutionTree.parent(meramon).id().equals(agumon) && EvolutionTree.parent(meramon).level() == 20 && EvolutionTree.parent(agumon).level() == 10
                && EvolutionTree.family(greymon).equals(koromon) && EvolutionTree.ancestor(koromon, meramon) && !EvolutionTree.ancestor(meramon, agumon), "the family's steps and their levels");
        boolean roomy = true, contained = true, spread = true;
        for (String root : List.of("koromon", "tsunomon", "pukamon", "mochimon")) for (var open : EvolutionTree.kids(Constants.id(root))) {
            var layout = EvolutionTree.layout(Constants.id(root), 0, 0, open.id());
            // a form takes its box, its name under it and, folded, the note under that
            for (var a : layout.all()) for (var b : layout.all()) if (a != b && a.column() == b.column() && a.y() <= b.y() && b.y() - a.y() < EvolutionTree.extent(a) + 2) roomy = false;
            for (var n : layout.all()) if (n.y() < 9 || n.y() + EvolutionTree.extent(n) > EvolutionTree.HEIGHT || n.x() + EvolutionTree.NODE > EvolutionTree.WIDTH) contained = false;
            var champions = layout.all().stream().filter(n -> n.column() == 2).toList();
            if (champions.size() == 2 && Math.abs(champions.get(1).y() - champions.get(0).y()) != 2 * EvolutionTree.SPREAD) spread = false;
        }
        check(roomy && contained && spread, "one branch open at a time: no form, name or note meets another, all inside the sheet, Champions 80 apart");
        check(EvolutionTree.openBranch(null, seadramon, koromon, null).equals(Constants.id("betamon")) && EvolutionTree.openBranch(null, koromon, koromon, null).equals(agumon), "a species tree opens its own branch");

        PartyMemberView young = partner(agumon, 19, 0, "", 0, "RESTING", ""), ready = partner(agumon, 20, 0, "", 0, "RESTING", "");
        check(!EvolutionTree.offers(young) && EvolutionTree.ready(young).isEmpty() && !EvolutionTree.news(young, 0), "L19: nothing to choose, no news");
        check(EvolutionTree.ready(ready).equals(List.of(greymon, meramon)) && EvolutionTree.chooses(ready) && EvolutionTree.news(ready, 0), "L20, unbound: Greymon and Meramon are a choice, and news");
        check(EvolutionTree.fresh(ready, 1).equals(java.util.Set.of(meramon)) && !EvolutionTree.news(partner(agumon, 20, 0, "", 3, "RESTING", ""), 0), "looked at here or on the server, a form is news no more");
        check(EvolutionTree.readyMask(ready) == 3 && EvolutionTree.focus(ready, EvolutionTree.fresh(ready, 0)).equals(greymon), "the choice opens on the first form");
        PartyMemberView bound = partner(agumon, 22, 0, "digicube:meramon", 3, "RESTING", "");
        check(EvolutionTree.ready(bound).equals(List.of(meramon)) && EvolutionTree.offers(bound) && !EvolutionTree.chooses(bound) && !EvolutionTree.news(bound, 0), "bound to Meramon: no choice, no news, Meramon still ready");
        check(EvolutionTree.blocked(bound, greymon) && !EvolutionTree.blocked(bound, meramon) && !EvolutionTree.blocked(bound, seadramon) && EvolutionTree.focus(bound, java.util.Set.of()).equals(meramon),
                "the other Champion is blocked, another Rookie's forms are not; its tree opens on its form");
        check(!EvolutionTree.offers(partner(agumon, 20, -1, "", 0, "RESTING", "")), "a Digimon in the Digispace cannot digivolve");
        PartyMemberView evolved = partner(meramon, 22, 0, "digicube:meramon", 3, "EVOLVED", "digicube:agumon");
        check(EvolutionTree.rookie(evolved).equals(agumon) && EvolutionTree.blocked(evolved, greymon) && EvolutionTree.reached(evolved, agumon) && EvolutionTree.ready(evolved).isEmpty(), "as Meramon it keeps its Rookie and its line");

        java.util.function.Predicate<net.minecraft.resources.Identifier> all = id -> true, none = id -> false;
        var NONE = com.digicube.fabric.client.party.CommandWheelReadout.Reason.NONE;
        check(EvolutionTree.sentence(ready, agumon, all, NONE).key().equals("choose") && EvolutionTree.sentence(ready, agumon, all, NONE).amber(), "its own form asks for a choice");
        check(EvolutionTree.sentence(ready, meramon, all, NONE).key().equals("digivolve") && EvolutionTree.sentence(bound, meramon, all, NONE).key().equals("digivolve_chosen"), "a ready form says digivolve, the chosen one says so");
        check(EvolutionTree.sentence(bound, greymon, all, NONE).key().equals("blocked") && EvolutionTree.sentence(bound, agumon, all, NONE).key().equals("bound"), "the blocked form names the chosen one");
        check(EvolutionTree.sentence(partner(agumon, 20, -1, "", 0, "RESTING", ""), meramon, all, NONE).key().equals("ready_reserve")
                && EvolutionTree.sentence(ready, meramon, all, com.digicube.fabric.client.party.CommandWheelReadout.Reason.COOLDOWN).key().equals("ready_but"), "ready, but in the Digispace or cooling down");
        var blind = EvolutionTree.sentence(ready, meramon, none, NONE);
        check(blind.key().equals("blind") && !blind.record() && EvolutionTree.sentence(young, greymon, none, NONE).record(), "a form not met yet can be picked blind; elsewhere it says how to record it");
        check(EvolutionTree.sentence(null, meramon, all, NONE).key().equals("digivolves_from") && EvolutionTree.sentence(young, greymon, all, NONE).key().equals("digivolves_at_one"), "a species' sentence, and the levels still to go");

        // A Baby II grows into one of its family's Rookies at level 10: the same choice, no DigiSoul.
        var betamon = Constants.id("betamon");
        PartyMemberView baby = partner(koromon, 9, 0, "", 0, "RESTING", ""), grown = partner(koromon, 10, 0, "", 0, "RESTING", "");
        check(!EvolutionTree.offers(baby) && EvolutionTree.sentence(baby, agumon, all, NONE).key().equals("grows_at_one"), "L9: one level to its growth");
        check(EvolutionTree.ready(grown).equals(List.of(agumon, betamon)) && EvolutionTree.chooses(grown) && EvolutionTree.news(grown, 0), "L10: Agumon and Betamon are a choice, and news");
        check(EvolutionTree.sentence(grown, koromon, all, NONE).key().equals("choose_growth") && EvolutionTree.sentence(grown, betamon, all, NONE).key().equals("grow")
                && EvolutionTree.sentence(grown, betamon, none, NONE).key().equals("blind_growth"), "its growth is worded as one");
        check(EvolutionTree.scope(grown).containsAll(List.of(koromon, agumon, betamon, greymon, seadramon)), "a Baby II's whole family is still ahead of it");
        var modules = com.digicube.fabric.client.party.CommandWheelReadout.modules(partner(koromon, 10, 0, "", 0, "RESTING", "", 0), 0, true);
        check(modules[com.digicube.fabric.client.party.CommandWheelReadout.BOTTOM_RIGHT].enabled(), "growth needs no DigiSoul on the wheel");
        check(com.digicube.fabric.client.party.CommandWheelReadout.modules(baby, 0, false)[com.digicube.fabric.client.party.CommandWheelReadout.BOTTOM_RIGHT].reason()
                == com.digicube.fabric.client.party.CommandWheelReadout.Reason.NEEDS_LEVEL, "below level 10 the wheel says it needs the level");
    }

    private static PartyMemberView partner(net.minecraft.resources.Identifier species, int level, int slot, String line, int noticed, String phase, String origin) {
        return partner(species, level, slot, line, noticed, phase, origin, 3600);
    }

    private static PartyMemberView partner(net.minecraft.resources.Identifier species, int level, int slot, String line, int noticed, String phase, String origin, int soul) {
        return new PartyMemberView(UUID.randomUUID(), species, "", 40, 40, level, 0, slot, slot >= 0, 0, soul, phase, 0, false, origin, false, 0, 0,
                false, false, false, 0, -1, line, noticed);
    }

    private static void write(File file, int width, int height, int[] argb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        ImageIO.write(image, "png", file);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAIL: " + what);
    }
}
