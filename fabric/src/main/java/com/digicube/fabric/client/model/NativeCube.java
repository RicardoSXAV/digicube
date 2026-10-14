package com.digicube.fabric.client.model;

/**
 * A baked cube that can carry an authored native face; {@code MixinModelPartCube} gives the interface to every
 * {@code ModelPart.Cube}. A marked cube draws its own polygons, so a renderer that rebuilds cubes from their
 * constructor box (Sodium's entity path) cannot swap the face for the placeholder box's top.
 */
public interface NativeCube {
    /** Draw this cube from its installed polygons from now on. */
    void digicube$markNative();
}
