package com.digicube.fabric.client.dev;

/**
 * A section body drawn by hand instead of as rows, for a tool that is not a list of numbers.
 * It draws inside the card the panel gives it and registers its own controls on the canvas.
 */
public interface DevBody {
    int height();

    void draw(DevCanvas canvas, int x, int y, int width);

    /** The collapsed header's summary, in place of the row count. */
    String summary();
}
