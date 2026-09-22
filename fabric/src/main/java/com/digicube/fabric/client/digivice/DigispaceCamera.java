package com.digicube.fabric.client.digivice;

/**
 * Where the Digispace view looks. The view is a rectangle in GUI units; the camera holds the world position at its
 * centre and one of a few zoom steps. Zooming keeps the ground under the hand where it is, and the view never leaves
 * the island: once the island fits, it is centred.
 */
public final class DigispaceCamera {
    /**
     * GUI units per world unit, as thirds: the mockup was drawn at three pixels per GUI unit, and a 16-unit Digimon
     * lands on whole multiples of its 32 px art at 2, 4, 6 and 8 pixels per unit.
     */
    static final int[] ZOOM_THIRDS = {2, 3, 4, 6, 8};
    public static final int DEFAULT_ZOOM = 2;

    private final int viewX, viewY, viewWidth, viewHeight;
    private int zoom = DEFAULT_ZOOM;
    private double centerX = DigispaceWorld.WIDTH / 2.0, centerY = DigispaceWorld.HEIGHT / 2.0;

    public DigispaceCamera(int viewX, int viewY, int viewWidth, int viewHeight) {
        this.viewX = viewX; this.viewY = viewY; this.viewWidth = viewWidth; this.viewHeight = viewHeight;
        clamp();
    }

    public int zoom() { return zoom; }
    public int zoomSteps() { return ZOOM_THIRDS.length; }
    public float scale() { return ZOOM_THIRDS[zoom] / 3.0F; }
    public double centerX() { return centerX; }
    public double centerY() { return centerY; }

    public double screenX(double worldX) { return viewX + viewWidth / 2.0 + (worldX - centerX) * scale(); }
    public double screenY(double worldY) { return viewY + viewHeight / 2.0 + (worldY - centerY) * scale(); }
    public double worldX(double screenX) { return (screenX - viewX - viewWidth / 2.0) / scale() + centerX; }
    public double worldY(double screenY) { return (screenY - viewY - viewHeight / 2.0) / scale() + centerY; }

    public boolean contains(double screenX, double screenY) {
        return screenX >= viewX && screenX < viewX + viewWidth && screenY >= viewY && screenY < viewY + viewHeight;
    }

    /** One step in ({@code direction} 1) or out (-1). The ground under ({@code anchorX}, {@code anchorY}) stays put when that point is in view. */
    public boolean zoomBy(int direction, double anchorX, double anchorY) {
        int next = Math.clamp(zoom + direction, 0, ZOOM_THIRDS.length - 1);
        if (next == zoom) return false;
        boolean anchored = contains(anchorX, anchorY);
        double ax = anchored ? anchorX : viewX + viewWidth / 2.0, ay = anchored ? anchorY : viewY + viewHeight / 2.0;
        double beforeX = worldX(ax), beforeY = worldY(ay);
        zoom = next;
        centerX += beforeX - worldX(ax);
        centerY += beforeY - worldY(ay);
        clamp();
        return true;
    }

    /** Dragging the ground: the view moves against the hand by the same distance on screen. */
    public void pan(double startCenterX, double startCenterY, double screenDeltaX, double screenDeltaY) {
        centerX = startCenterX - screenDeltaX / scale();
        centerY = startCenterY - screenDeltaY / scale();
        clamp();
    }

    private void clamp() {
        double halfWidth = viewWidth / 2.0 / scale(), halfHeight = viewHeight / 2.0 / scale();
        centerX = 2 * halfWidth >= DigispaceWorld.WIDTH ? DigispaceWorld.WIDTH / 2.0 : Math.clamp(centerX, halfWidth, DigispaceWorld.WIDTH - halfWidth);
        centerY = 2 * halfHeight >= DigispaceWorld.HEIGHT ? DigispaceWorld.HEIGHT / 2.0 : Math.clamp(centerY, halfHeight, DigispaceWorld.HEIGHT - halfHeight);
    }
}
