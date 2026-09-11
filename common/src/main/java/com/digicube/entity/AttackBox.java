package com.digicube.entity;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** An authored cuboid, retaining its orientation instead of damaging empty broad-phase corners. */
public record AttackBox(Vec3 center, Vec3 x, Vec3 y, Vec3 z) {
    public AttackBox world(Vec3 feet, float yaw, double height) {
        float angle = (float)-Math.toRadians(yaw);
        return new AttackBox(center.yRot(angle).add(feet).add(0,height,0),
                x.yRot(angle), y.yRot(angle), z.yRot(angle));
    }

    public AABB bounds() {
        Vec3 extent = new Vec3(Math.abs(x.x)+Math.abs(y.x)+Math.abs(z.x),
                Math.abs(x.y)+Math.abs(y.y)+Math.abs(z.y), Math.abs(x.z)+Math.abs(y.z)+Math.abs(z.z));
        return new AABB(center.subtract(extent), center.add(extent));
    }

    /** Separating-axis test between the rotated cuboid and a world-aligned entity/block box. */
    public boolean intersects(AABB box) {
        Vec3 delta = box.getCenter().subtract(center);
        Vec3 extent = new Vec3(box.getXsize()*.5,box.getYsize()*.5,box.getZsize()*.5);
        Vec3[] world = {new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};
        Vec3[] axes = {x,y,z};
        for (Vec3 axis : world) if (separated(axis,delta,extent)) return false;
        for (Vec3 axis : axes) {
            if (separated(axis,delta,extent)) return false;
            for (Vec3 other : world) if (separated(axis.cross(other),delta,extent)) return false;
        }
        return true;
    }

    private boolean separated(Vec3 axis, Vec3 delta, Vec3 extent) {
        if (axis.lengthSqr()<1e-16) return false;
        double reach = Math.abs(x.dot(axis))+Math.abs(y.dot(axis))+Math.abs(z.dot(axis))
                + extent.x*Math.abs(axis.x)+extent.y*Math.abs(axis.y)+extent.z*Math.abs(axis.z);
        return Math.abs(delta.dot(axis))>reach+1e-8;
    }
}
