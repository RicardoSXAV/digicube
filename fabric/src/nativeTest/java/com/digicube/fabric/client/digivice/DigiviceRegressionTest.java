package com.digicube.fabric.client.digivice;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Pins the Digivice's rules without a window: the Digispace island is the same every time and only lets Digimon stand
 * on open ground, the camera keeps the ground under the hand and never leaves the island, and the herd follows the
 * reserve while keeping everyone where they were. Given a directory, it also writes the painted island and props there
 * as PNG, so the art can be looked at without the game.
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
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
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
        System.out.println("Digivice regression test passed (" + checks + " checks)");
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
