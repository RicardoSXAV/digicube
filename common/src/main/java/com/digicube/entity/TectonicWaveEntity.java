package com.digicube.entity;

import com.digicube.digimon.DigimonAttack;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.*;
import java.util.*;

/** Fixed release point, synchronized native clock, and once-per-cast damage. Never changes terrain. */
public final class TectonicWaveEntity extends Entity {
    private static final EntityDataAccessor<Integer> AGE=SynchedEntityData.defineId(TectonicWaveEntity.class,EntityDataSerializers.INT);
    private static final java.util.List<EntityDataAccessor<Float>> HEIGHTS=java.util.stream.IntStream.range(0,6)
            .mapToObj(i->SynchedEntityData.defineId(TectonicWaveEntity.class,EntityDataSerializers.FLOAT)).toList();
    private DigimonEntity owner;
    private DigimonAttack attack;
    private final Set<UUID> hit=new HashSet<>();
    public TectonicWaveEntity(EntityType<? extends TectonicWaveEntity> type,Level level) { super(type,level); }
    public TectonicWaveEntity(ServerLevel level,DigimonEntity owner,DigimonAttack attack) {
        this(DCEntityTypes.TECTONIC_WAVE,level);this.owner=owner;this.attack=attack;
        setPos(owner.position());setYRot(owner.getYRot());
        float[] h=TectonicWave.ground(level,owner,position(),getYRot());
        for(int i=0;i<6;i++)entityData.set(HEIGHTS.get(i),h[i]);
    }
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(AGE,0);for(var h:HEIGHTS)b.define(h,TectonicWave.INVALID);
    }
    public float animationTick(float partial) {return TectonicWave.IMPACT+entityData.get(AGE)+partial;}
    public float height(int i) {return entityData.get(HEIGHTS.get(i));}
    @Override public boolean hurtServer(ServerLevel level,net.minecraft.world.damagesource.DamageSource source,float amount) {return false;}
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server))return;
        int age=entityData.get(AGE);int tick=TectonicWave.IMPACT+age;
        if(owner==null || !owner.isAlive() || tick>=TectonicWave.END){discard();return;}
        if(age==0)server.playSound(null,getX(),getY(),getZ(),SoundEvents.GENERIC_EXPLODE,SoundSource.NEUTRAL,1.4F,.65F);
        damageAt(server,tick);
        entityData.set(AGE,age+1);
    }
    /** Shared release hit ledger prevents the fist and overlapping spikes from multiplying damage. */
    void damageAt(ServerLevel server,int tick) {
        if(tick==TectonicWave.IMPACT) {
            AABB slam=TectonicWave.slamBox(attack.motion(),position(),getYRot());
            if(TectonicWave.clearVolume(server,this,slam)) {
                damageInside(server,slam,AttackGeometry.world(position(),attack.motion().sample(tick).head(),getYRot()));
            }
        }
        for(int i=0;i<6;i++) {
            float h=height(i);if(h==TectonicWave.INVALID)continue;
            // The exposed stone remains dangerous until it retracts. Underground frames have no volume.
            for(int step=0;step<4;step++) {
                AABB box=TectonicWave.world(i,tick+step*.25,position(),getYRot(),h);
                if(box.getYsize()<.05)continue;
                if(!TectonicWave.clearVolume(server,this,box)){for(int j=i;j<6;j++)entityData.set(HEIGHTS.get(j),TectonicWave.INVALID);break;}
                Vec3 from=AttackGeometry.world(position(),TectonicWave.base(i).add(0,h+.1,0),getYRot());
                final int spike=i;final double time=tick+step*.25;
                damageInside(server,box,from,victim->TectonicWave.intersects(spike,time,position(),getYRot(),h,victim.getBoundingBox()));
            }
        }
    }
    private void damageInside(ServerLevel server,AABB box,Vec3 from) {
        damageInside(server,box,from,victim->true);
    }
    private void damageInside(ServerLevel server,AABB box,Vec3 from,java.util.function.Predicate<LivingEntity> contact) {
        for(LivingEntity victim:server.getEntitiesOfClass(LivingEntity.class,box,
                e->e.isAlive() && e!=owner && !hit.contains(e.getUUID()) && owner.canAttack(e) && !owner.isAllyOf(e) && contact.test(e))) {
            if(server.clip(new ClipContext(from,victim.getBoundingBox().getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this)).getType()!=HitResult.Type.MISS)continue;
            if(owner.hitWithAttack(server,attack,victim))hit.add(victim.getUUID());
        }
    }
    protected void addAdditionalSaveData(ValueOutput output) {}
    protected void readAdditionalSaveData(ValueInput input) {discard();}
}
