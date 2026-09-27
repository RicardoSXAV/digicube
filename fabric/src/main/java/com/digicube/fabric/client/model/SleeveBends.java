package com.digicube.fabric.client.model;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Sleeves that bend as one piece over a joint ({@code bends} in {@code ground_models.json}). A sleeve on the upper bone
 * and one on the lower bone, of one rectangular section, are both cut square at the joint's pivot; every frame, after
 * the pose, the ring of vertices at each cut is moved onto the plane halving the angle between the two bones, a mitred
 * joint. Both sleeves share each ring point, so a trouser leg bends at the knee and the hip with no gap, no step and no
 * patch buried under it, at any angle. On the inner side of a deep bend a corner slides back along the shorter
 * sleeve's edge instead of turning it inside out (the sleeves fold into the crease there), and the ring's other points
 * stay on the straight edges between the corners, so the seam stays closed. A twist of the lower bone about its length
 * is taken up by the lower sleeve. Sleeves run along their bone's length (the part's y axis) and are never keyed.
 */
public final class SleeveBends {
    /**
     * One bent joint: {@code joint} leads from the root to the lower bone, {@code upper} is a sleeve on its parent (cut
     * square at the joint's pivot), {@code lower} a sleeve on the lower bone (cut square at its origin).
     */
    public record Bend(List<String> joint, String upper, String lower) {}

    /** A model's bends with their ring vertices found once, posed on every frame by {@link #apply}. */
    public static final class Rig {
        private final List<Joint> joints;
        private Rig(List<Joint> joints) { this.joints = joints; }
    }

    /** A ring vertex: where it sits in a polygon, its rest texture coordinates, and its place across the cut. */
    private record Ring(ModelPart.Vertex[] polygon, int index, float u, float v, float x, float z) {}
    private record Joint(ModelPart joint, ModelPart upper, ModelPart lower, List<Ring> upperRing, List<Ring> lowerRing,
                         float upperLength, float lowerLength, float[] section) {}

    /** Model pixels from the cut within which a vertex is on it, and how much sleeve a deep bend always leaves. */
    private static final float ON_CUT = .01F, KEEP = 1;
    /** The mitre's cosine floor: a joint folded beyond about 150 degrees stops lengthening the outer corner. */
    private static final float MIN_COSINE = .25F;

    private SleeveBends() {}

    /**
     * Find the ring vertices of every bend in the baked hierarchy, at rest.
     * @param root the model's root, with its native surfaces installed
     * @param bends the catalog's bends
     * @return the rig to pose each frame
     */
    public static Rig rig(ModelPart root, List<Bend> bends) {
        var joints = new ArrayList<Joint>();
        for (var bend : bends) {
            ModelPart parent = root, joint = root;
            for (int i = 0; i < bend.joint().size(); i++) {
                if (i == bend.joint().size() - 1) parent = joint;
                joint = joint.getChild(bend.joint().get(i));
            }
            ModelPart upper = parent.getChild(bend.upper()), lower = joint.getChild(bend.lower());
            PartPose rest = joint.getInitialPose();
            var upperRing = new ArrayList<Ring>(); var lowerRing = new ArrayList<Ring>();
            // Upper sleeve: the cut lies on the parent's plane through the pivot, across the parent's y axis.
            float upperLength = ring(upper, matrix(upper.getInitialPose()), rest.y(), rest.x(), rest.z(), -1, upperRing);
            // Lower sleeve: the cut lies on the lower bone's own plane through its origin.
            float lowerLength = ring(lower, matrix(lower.getInitialPose()), 0, 0, 0, 1, lowerRing);
            if (upperRing.isEmpty() || lowerRing.isEmpty()) throw new IllegalArgumentException("A bent sleeve needs a cut at its joint: " + bend);
            // The section's corners, x and z either side of the pivot, from both rings.
            float[] section = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (var r : upperRing) section(section, r);
            for (var r : lowerRing) section(section, r);
            if (!(section[1] - section[0] > ON_CUT && section[3] - section[2] > ON_CUT)) throw new IllegalArgumentException("A bent sleeve needs a section: " + bend);
            joints.add(new Joint(joint, upper, lower, List.copyOf(upperRing), List.copyOf(lowerRing), upperLength, lowerLength, section));
        }
        return new Rig(List.copyOf(joints));
    }

    private static void section(float[] s, Ring r) {
        s[0] = Math.min(s[0], r.x); s[1] = Math.max(s[1], r.x); s[2] = Math.min(s[2], r.z); s[3] = Math.max(s[3], r.z);
    }

    /**
     * Collect a sleeve's vertices on the cut (the plane y = {@code cut} of the bone's frame) and return how far the
     * sleeve runs from it along {@code side} (-1 up the bone, 1 down it), the shortest of its faces that touch the cut.
     */
    private static float ring(ModelPart sleeve, Matrix4f toBone, float cut, float pivotX, float pivotZ, int side, List<Ring> out) {
        float[] length = {Float.POSITIVE_INFINITY};
        sleeve.visit(new com.mojang.blaze3d.vertex.PoseStack(), (pose, path, index, cube) -> {
            if (!path.isEmpty()) return; // the sleeve's own faces, not a child's
            for (var polygon : cube.polygons) {
                var vertices = polygon.vertices();
                boolean touches = false; float far = Float.POSITIVE_INFINITY;
                for (int i = 0; i < vertices.length; i++) {
                    var p = toBone.transformPosition(vertices[i].x(), vertices[i].y(), vertices[i].z(), new Vector3f());
                    if (Math.abs(p.y - cut) < ON_CUT) {
                        touches = true;
                        out.add(new Ring(vertices, i, vertices[i].u(), vertices[i].v(), p.x - pivotX, p.z - pivotZ));
                    } else far = Math.min(far, (p.y - cut) * side);
                }
                if (touches) length[0] = Math.min(length[0], far);
            }
        });
        return length[0];
    }

    /**
     * Mitre every bend on the pose this frame has: each corner of the section goes where its sleeve's edge meets the
     * plane halving the joint, and every ring point between the corners on the straight edge they span; shared by both
     * sleeves.
     */
    public static void apply(Rig rig) {
        if (rig == null) return;
        for (var j : rig.joints) {
            var joint = j.joint;
            var turn = new Quaternionf().rotationZYX(joint.zRot, joint.yRot, joint.xRot);
            var along = turn.transform(new Vector3f(0, 1, 0));
            var half = new Vector3f(0, 1, 0).add(along);
            if (half.lengthSquared() < 1.0E-6F) continue;
            half.normalize();
            float cosine = Math.max(half.y, MIN_COSINE);
            var inverseTurn = new Quaternionf(turn).conjugate();
            var scale = new Vector3f(joint.xScale, joint.yScale, joint.zScale);
            var toUpper = matrix(j.upper.storePose()).invert();
            var toLower = matrix(j.lower.storePose()).invert();
            float[] s = j.section;
            Vector3f[] corners = {corner(s[0], s[2], half, cosine, turn, inverseTurn, scale, j), corner(s[1], s[2], half, cosine, turn, inverseTurn, scale, j),
                    corner(s[0], s[3], half, cosine, turn, inverseTurn, scale, j), corner(s[1], s[3], half, cosine, turn, inverseTurn, scale, j)};
            for (var r : j.upperRing) {
                var p = point(r, s, corners);
                // In the parent's frame, then the upper sleeve's own.
                var local = toUpper.transformPosition(p.x + joint.x, p.y + joint.y, p.z + joint.z, new Vector3f());
                r.polygon[r.index] = new ModelPart.Vertex(local.x, local.y, local.z, r.u, r.v);
            }
            for (var r : j.lowerRing) {
                var p = point(r, s, corners);
                // Back into the lower bone's frame, then the lower sleeve's own.
                inverseTurn.transform(p).div(scale);
                var local = toLower.transformPosition(p, new Vector3f());
                r.polygon[r.index] = new ModelPart.Vertex(local.x, local.y, local.z, r.u, r.v);
            }
        }
    }

    /** A ring point between the section's corners (bilinear, so on the straight edge two corners span). */
    private static Vector3f point(Ring r, float[] s, Vector3f[] c) {
        float a = (r.x - s[0]) / (s[1] - s[0]), b = (r.z - s[2]) / (s[3] - s[2]);
        Vector3f near = new Vector3f(c[0]).lerp(c[1], a), far = new Vector3f(c[2]).lerp(c[3], a);
        return near.lerp(far, b);
    }

    /** A corner of the section across the cut, relative to the pivot in the parent's frame. */
    private static Vector3f corner(float x, float z, Vector3f half, float cosine, Quaternionf turn, Quaternionf inverseTurn, Vector3f scale, Joint j) {
        // The upper sleeve's edge through the corner runs along the parent's y axis: it meets the halving plane here.
        float t = -(x * half.x + z * half.z) / cosine;
        t = Math.max(t, -(j.upperLength - KEEP));
        var p = new Vector3f(x, t, z);
        // Inside a deep bend the point may lie past the end of the lower sleeve: slide it back along the lower edge.
        var q = inverseTurn.transform(new Vector3f(p)).div(scale);
        float limit = j.lowerLength - KEEP;
        if (q.y > limit) { q.y = limit; turn.transform(q.mul(scale), p); }
        return p;
    }

    private static Matrix4f matrix(PartPose pose) {
        return new Matrix4f().translation(pose.x(), pose.y(), pose.z()).rotateZYX(pose.zRot(), pose.yRot(), pose.xRot())
                .scale(pose.xScale(), pose.yScale(), pose.zScale());
    }

    /**
     * Parse the catalog's {@code bends}.
     * @param config one model's entry
     * @return its bends, none without the key
     */
    public static List<Bend> read(com.google.gson.JsonObject config) {
        if (!config.has("bends")) return List.of();
        var bends = new ArrayList<Bend>();
        for (var item : config.getAsJsonArray("bends")) {
            var o = item.getAsJsonObject();
            var joint = new ArrayList<String>(); o.getAsJsonArray("joint").forEach(n -> joint.add(n.getAsString()));
            if (joint.size() < 2) throw new IllegalArgumentException("A bend's joint needs a parent: " + joint);
            bends.add(new Bend(List.copyOf(joint), o.get("upper").getAsString(), o.get("lower").getAsString()));
        }
        return List.copyOf(bends);
    }
}
