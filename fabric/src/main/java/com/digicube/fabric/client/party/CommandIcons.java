package com.digicube.fabric.client.party;

import com.digicube.fabric.client.party.CommandWheelReadout.Order;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The command wheel's 20 x 20 order icons, drawn from bitmaps like the level readout so
 * they tint freely: {@code #} is the body, {@code +} the accent and {@code -} a dark shade
 * of the accent.
 */
final class CommandIcons {
    private CommandIcons() {}

    static final int SIZE = 20;
    private static final String BLANK = "....................";

    private static final String[] CHEVRONS = {
            ".........##.........", "........####........", ".......######.......", "......###..###......", ".....###....###.....",
            "....###......###....", "...###...++...###...", "........++++........", ".......++++++.......", "......+++..+++......",
            ".....+++....+++.....", "....+++......+++....", "...+++...##...+++...", "........####........", ".......######.......",
            "......###..###......", ".....###....###.....", "....###......###....", "...###........###..."};
    private static final String[] ARROW = {
            ".........##.........", ".........##.........", ".........##.........", ".........##.........", ".....#...##...#.....",
            ".....##..##..##.....", "......##.##.##......", ".......######.......", "........####........", ".........##........."};
    private static final String[] DEVICE = {
            BLANK, "...++++++++++++++...", "..++++++++++++++++..", "..+++----------+++..", "..+++----------+++..",
            "..+++----------+++..", "..+++----------+++..", "..++++++++++++++++..", "..+++++++--+++++++..", "...++++++++++++++..."};

    private static final String[] STAND_STILL = {
            BLANK, ".......######.......", ".....##########.....", "....####....####....", "....###......###....",
            "...###..++++..###...", "...###..++++..###...", "...###..++++..###...", "....###......###....", "....####....####....",
            ".....####..####.....", "......########......", ".......######.......", "........####........", ".........##.........",
            BLANK, "....++++....++++....", "..++++++++++++++++..", "....++++++++++++....", BLANK};
    private static final String[] FOLLOW = {
            BLANK, BLANK, "..............##....", ".............####...", ".............####...",
            "..............##....", BLANK, ".............####...", ".+....+.....######..", ".++...++....######..",
            "..++...++...######..", "...++...++..######..", "..++...++....####...", ".++...++.....#..#...", ".+....+......#..#...",
            ".............#..#...", "............##..##..", BLANK, BLANK, BLANK};
    private static final String[] CANCEL_TARGET = {
            ".......######.......", ".....##..##..##.....", "....#....##....#....", "...#.....##.....#...", "..#..............#..",
            ".#................#.", ".#....++....++....#.", "#......++..++......#", "#.......++++.......#", "####.....++.....####",
            "####.....++.....####", "#.......++++.......#", "#......++..++......#", ".#....++....++....#.", ".#................#.",
            "..#..............#..", "...#.....##.....#...", "....#....##....#....", ".....##..##..##.....", ".......######......."};
    private static final String[] RECALL = concat(ARROW, DEVICE);
    private static final String[] SEND_OUT = concat(reversed(ARROW), DEVICE);
    private static final String[] DIGIVOLVE = spark(concat(CHEVRONS, new String[] {BLANK}));
    private static final String[] REVERT = concat(new String[] {BLANK}, reversed(CHEVRONS));

    static String[] rows(Order order) {
        return switch (order) {
            case STAND_STILL -> STAND_STILL;
            case FOLLOW -> FOLLOW;
            case CANCEL_TARGET -> CANCEL_TARGET;
            case RECALL -> RECALL;
            case SEND_OUT -> SEND_OUT;
            case DIGIVOLVE -> DIGIVOLVE;
            case REVERT -> REVERT;
        };
    }

    /** Draws the icon with horizontal runs merged, so a row costs a few fills rather than twenty. */
    static void draw(GuiGraphicsExtractor graphics, Order order, int x, int y, int body, int accent, int shade) {
        String[] rows = rows(order);
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int start = 0;
            while (start < line.length()) {
                char tone = line.charAt(start);
                int end = start + 1;
                while (end < line.length() && line.charAt(end) == tone) end++;
                int color = tone == '#' ? body : tone == '+' ? accent : tone == '-' ? shade : 0;
                if ((color >>> 24) != 0) graphics.fill(x + start, y + row, x + end, y + row + 1, color);
                start = end;
            }
        }
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static String[] reversed(String[] rows) {
        String[] all = new String[rows.length];
        for (int i = 0; i < rows.length; i++) all[i] = rows[rows.length - 1 - i];
        return all;
    }

    /** The four-point spark beside the top chevron: Digivolution adds, Revert does not. */
    private static String[] spark(String[] rows) {
        rows[0] = rows[0].substring(0, 16) + "+...";
        rows[1] = rows[1].substring(0, 15) + "+++..";
        rows[2] = rows[2].substring(0, 16) + "+...";
        return rows;
    }
}
