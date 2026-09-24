package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digivice.*;
import com.digicube.platform.Services;
import com.digicube.registry.DCEntityTypes;
import com.digicube.registry.DCItems;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Actual dedicated-server lifecycle tests, including a second-process disk reload. No client input. */
public final class DigiviceScenario {
    private static final UUID DISK_OWNER = UUID.fromString("6af0d9cd-dbf2-4e83-a1df-1e753111c189");
    private final List<DroppedDigivice> drops = new ArrayList<>();
    private ServerPlayer owner, stranger;
    private DroppedDigivice device, water, lava, abyss;
    private int tick, passed;
    private boolean done;
    private long armedAt = -1;
    private boolean checkedDark, checkedFade;
    private final com.google.gson.JsonArray trajectory = new com.google.gson.JsonArray();
    public void tick(ServerLevel level) {
        String name = System.getenv("DIGICUBE_SCENARIO");
        if (done || name == null || !name.startsWith("digivice_checks") || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            if (name.equals("digivice_checks_reload")) {
                if (tick++ == 0) for (int x = 3; x <= 4; x++) level.setChunkForced(x, 0, true);
                if (tick % 20 == 1) reload(level);
                return;
            }
            if (tick++ == 0) {
                for (int x = 3; x <= 4; x++) level.setChunkForced(x, 0, true);
                return;
            }
            if (tick == 20) setup(level);
            if (tick == 120) interaction(level);
            if (tick < 120 && device != null) {
                var sample = new com.google.gson.JsonArray(); sample.add(tick-20); sample.add(device.getY()-300); sample.add(device.pitch(1));
                sample.add(DroppedDigivice.beaconStrength(device.beaconAt(), level.getGameTime()));
                trajectory.add(sample);
                if (device.beaconAt() >= 0 && armedAt < 0) {
                    armedAt = device.beaconAt();
                    check(device.onGround() && Math.abs(device.pitch(1) + 90) < .25
                            && armedAt == level.getGameTime() + DroppedDigivice.BEACON_DELAY,
                            "beacon starts its delay only after the falling model settles");
                }
                if (armedAt < 0 || level.getGameTime() <= armedAt) {
                    if (DroppedDigivice.beaconStrength(device.beaconAt(), level.getGameTime()) != 0)
                        throw new IllegalStateException("beam visible during falling/settling/delay");
                    checkedDark = true;
                } else if (level.getGameTime() < armedAt + DroppedDigivice.BEACON_FADE) {
                    float alpha = DroppedDigivice.beaconStrength(armedAt, level.getGameTime());
                    if (!(alpha > 0 && alpha < 1)) throw new IllegalStateException("missing soft beam fade");
                    checkedFade = true;
                }
            }
            if (tick == 200) environment(level);
            if (tick == 6500) {
                check(!device.isRemoved() && !water.isRemoved() && !lava.isRemoved() && !abyss.isRemoved(), "no despawn after 6500 real server ticks");
                check(!device.isRemoved(), "hopper cannot collect the dropped device");
                var data = DigiviceSavedData.get(level.getServer());
                var encoded = DigiviceSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
                var decoded = DigiviceSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
                check(decoded.devices().equals(data.devices()), "ledger codec preserves owners, credentials, dimensions and positions");
                var disk = data.device(DISK_OWNER);
                if (disk == null) {
                    ItemStack stack = Digivices.issue(level.getServer(), DISK_OWNER);
                    spawn(level, stack, new Vec3(66, 301, 7));
                }
                level.getServer().saveEverything(false, true, true);
                finish(level);
            }
        } catch (Exception error) {
            done = true;
            Constants.LOG.error("[digivice] FAIL after {} cases", passed, error);
            level.getServer().halt(false);
        }
    }
    private void setup(ServerLevel level) {
        for (int x = 48; x <= 70; x++) for (int z = 0; z <= 12; z++) {
            level.setBlock(new BlockPos(x,299,z), Blocks.STONE.defaultBlockState(),3);
            for (int y=300;y<308;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        owner = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "DeviceOwner"));
        stranger = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "OtherTamer"));
        owner.setPos(50,301,3); stranger.setPos(50,301,3);
        owner.getInventory().clearContent(); stranger.getInventory().clearContent();
        ItemStack legacy = new ItemStack(DCItems.DIGIVICE);
        owner.getInventory().add(legacy); Digivices.reconcile(owner);
        check(Digivices.hasDevice(owner), "legacy inventory device bound to its player");
        check(Digivices.issue(level.getServer(), owner.getUUID()).isEmpty(), "one grant per owner");
        owner.getInventory().add(new ItemStack(DCItems.DIGIVICE)); Digivices.reconcile(owner);
        check(count(owner)==1, "creative or command extras rejected");
        owner.drop(new ItemStack(DCItems.DIGIVICE), false, true);
        check(DigiviceSavedData.get(level.getServer()).device(owner.getUUID()).drop().isEmpty(), "creative direct-drop cannot replace the owner's existing device");
        UUID foreignOwner = UUID.randomUUID();
        stranger.getInventory().add(Digivices.issue(level.getServer(), foreignOwner));
        check(!Digivices.hasDevice(stranger), "foreign container item cannot authorize Digivice actions");
        Digivices.reconcile(stranger);
        check(count(stranger)==0 && DigiviceSavedData.get(level.getServer()).device(foreignOwner).drop().isPresent(), "foreign container transfer returns the device with its original owner");
        ItemStack carried = owner.getInventory().removeItemNoUpdate(0);
        owner.drop(carried, false);
        var address = DigiviceSavedData.get(level.getServer()).device(owner.getUUID()).drop().orElseThrow();
        device = (DroppedDigivice) level.getEntity(address.entity()); drops.add(device);
        check(device != null && device.stack().getCount()==1, "ordinary player drop becomes dedicated entity");
        check(!level.addFreshEntity(new DroppedDigivice(level, carried, new Vec3(51,303,3),Vec3.ZERO)), "duplicate dropped credential rejected");
        water = spawn(level, Digivices.issue(level.getServer(), UUID.randomUUID()), new Vec3(56,304,5));
        lava = spawn(level, Digivices.issue(level.getServer(), UUID.randomUUID()), new Vec3(61,304,5));
        for (int center : new int[]{56,61}) for (int y=300;y<=305;y++) for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++)
            if(Math.abs(dx)==2 || Math.abs(dz)==2) level.setBlock(new BlockPos(center+dx,y,5+dz),Blocks.STONE.defaultBlockState(),3);
        for (int y=300;y<=304;y++) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            level.setBlock(new BlockPos(56+dx,y,5+dz),Blocks.WATER.defaultBlockState(),3);
            level.setBlock(new BlockPos(61+dx,y,5+dz),Blocks.LAVA.defaultBlockState(),3);
        }
        abyss = spawn(level, Digivices.issue(level.getServer(),UUID.randomUUID()), new Vec3(66,level.getMinY()-30,4));
        level.getServer().tickRateManager().requestGameToSprint(6700);
    }
    private void interaction(ServerLevel level) {
        check(checkedDark && checkedFade && DroppedDigivice.beaconStrength(device.beaconAt(), level.getGameTime()) == 1,
                "beam stays dark through the delay then fades to full strength");
        String evidence = System.getenv("DIGICUBE_EVIDENCE");
        if (evidence != null) try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(evidence,"trajectory.json"),trajectory.toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        boolean kept = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
        for (boolean keep : new boolean[]{false,true}) for (boolean vanishing : new boolean[]{false,true}) {
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY,keep,level.getServer());
            var mortal = new MortalPlayer(level);
            mortal.setPos(52,301,9);
            ItemStack deathDevice = Digivices.issue(level.getServer(),mortal.getUUID());
            if (vanishing) deathDevice.enchant(level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.VANISHING_CURSE),1);
            mortal.getInventory().add(deathDevice);
            mortal.getInventory().add(new ItemStack(Items.DIAMOND));
            mortal.hurtServer(level,level.damageSources().genericKill(),10000);
            var d = DigiviceSavedData.get(level.getServer()).device(mortal.getUUID());
            check(mortal.isDeadOrDying() && count(mortal)==(keep?1:0) && d.drop().isPresent()!=keep,
                    (keep?"real player death retains Digivice with keepInventory=true":"real player death leaves protected Digivice with keepInventory=false")
                            + (vanishing?" and Curse of Vanishing":""));
            check(mortal.getInventory().contains(s->s.is(Items.DIAMOND)) == keep,
                    "other items follow keepInventory=" + keep);
        }
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY,kept,level.getServer());
        check(device.onGround() && Math.abs(device.pitch(1)+90)<.1, "landed and settled face-up without spinning");
        device.playerTouch(stranger);
        check(!device.isRemoved() && count(stranger)==0, "other player cannot pick up owner's device");
        for(int i=0;i<owner.getInventory().getContainerSize();i++) owner.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        device.playerTouch(owner);
        check(!device.isRemoved(), "full inventory leaves physical device intact");
        owner.getInventory().clearContent();
        ItemStack stale = device.stack().copy();
        device.playerTouch(owner);
        check(device.isRemoved() && count(owner)==1 && Digivices.hasDevice(owner), "owner pickup succeeds");
        owner.getInventory().add(stale); Digivices.reconcile(owner);
        check(count(owner)==1, "stale cloned stack invalidated after pickup");
        owner.getInventory().dropAll();
        var address = DigiviceSavedData.get(level.getServer()).device(owner.getUUID()).drop().orElseThrow();
        device = (DroppedDigivice) level.getEntity(address.entity()); drops.add(device);
        check(device != null && count(owner)==0, "death inventory drop retains owner and unique device");
        var ownerView = com.digicube.fabric.digivice.FabricDigivices.snapshot(owner);
        check(ownerView.markers().stream().anyMatch(m -> m.entity().equals(device.getUUID()) && m.owned()), "locator identifies the owner's dropped device");
        check(com.digicube.fabric.digivice.FabricDigivices.snapshot(stranger).markers().stream().noneMatch(DigiviceLocatorPayload.Marker::owned), "other players' devices do not get an owned HUD marker");
        Vec3 original = owner.position(); owner.setPos(2000,301,3);
        check(com.digicube.fabric.digivice.FabricDigivices.snapshot(owner).markers().stream().anyMatch(DigiviceLocatorPayload.Marker::owned), "owner's direction marker persists beyond the beam's range");
        owner.setPos(original);
        replacements(level);
        for(var damage : List.of(level.damageSources().lava(),level.damageSources().cactus(),level.damageSources().explosion(null,null),level.damageSources().genericKill(),level.damageSources().fellOutOfWorld()))
            check(!device.hurtServer(level,damage,10000) && !device.isRemoved(), "immune to " + damage.getMsgId());
        device.kill(level);
        check(!device.isRemoved(), "ordinary kill command cannot remove the device");
        try(var problems = new net.minecraft.util.ProblemReporter.ScopedCollector(Constants.LOG)) {
            var output = TagValueOutput.createWithContext(problems,level.registryAccess());
            device.saveWithoutId(output);
            UUID id = device.getUUID();
            device.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            DroppedDigivice restored = new DroppedDigivice(DCEntityTypes.DROPPED_DIGIVICE,level);
            restored.load(TagValueInput.create(problems,level.registryAccess(),output.buildResult()));
            check(restored.getUUID().equals(id) && level.addFreshEntity(restored), "entity unload/reload retains identity, stack and custody");
            device = restored; drops.add(device);
        }
    }
    private void replacements(ServerLevel level) {
        var recipient = FakePlayer.get(level,new GameProfile(UUID.randomUUID(),"ReplacementTest"));
        recipient.setPos(52,301,9);
        var old = spawn(level,Digivices.issue(level.getServer(),recipient.getUUID()),recipient.position());
        ItemStack stale = old.stack().copy();
        var source = level.getServer().createCommandSourceStack().withEntity(recipient);
        level.getServer().getCommands().performPrefixedCommand(source,"give @s digicube:digivice");
        check(old.isRemoved() && count(recipient)==1 && Digivices.hasDevice(recipient), "actual /give replaces a ground device with one bound inventory device");
        check(!device.isRemoved(), "replacement leaves another owner's dropped device intact");
        check(!level.addFreshEntity(new DroppedDigivice(level,stale,recipient.position(),Vec3.ZERO)), "revoked unloaded device is rejected when its chunk loads");
        UUID credential = Digivices.token(recipient.getInventory().getItem(0));
        level.getServer().getCommands().performPrefixedCommand(source,"give @s digicube:digivice 64");
        check(count(recipient)==1 && !credential.equals(Digivices.token(recipient.getInventory().getItem(0))), "give count cannot create multiple devices; held device is replaced");
        level.getServer().getCommands().performPrefixedCommand(source,"give @s minecraft:diamond 3");
        check(recipient.getInventory().countItem(Items.DIAMOND)==3, "ordinary /give keeps vanilla behavior");
        recipient.getInventory().dropAll();
        var data = DigiviceSavedData.get(level.getServer());
        UUID fullOld = data.device(recipient.getUUID()).drop().orElseThrow().entity();
        for (int i=0;i<recipient.getInventory().getContainerSize();i++) recipient.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        level.getServer().getCommands().performPrefixedCommand(source,"give @s digicube:digivice");
        var fullNew = data.device(recipient.getUUID()).drop().orElseThrow();
        check(!fullOld.equals(fullNew.entity()) && level.getEntity(fullNew.entity()) instanceof DroppedDigivice
                && recipient.getInventory().getItem(0).getCount()==64, "full inventory replacement safely drops the new device without deleting other items");
    }
    private void environment(ServerLevel level) {
        check(water.getY()<300.3 && water.onGround(), "sinks through water to the floor");
        check(!lava.isRemoved() && lava.isInLava() && lava.getY()<300.3, "survives submerged in lava (y="+lava.getY()+")");
        check(!abyss.isRemoved() && abyss.getY()>=level.getMinY()+1 && abyss.getDeltaMovement().lengthSqr()==0, "void safety plane holds device above world floor");
        // A hopper beneath the actual entity must not collect the dedicated entity type.
        level.setBlock(device.blockPosition().below(),Blocks.HOPPER.defaultBlockState(),3);
    }
    private void reload(ServerLevel level) {
        var d = DigiviceSavedData.get(level.getServer()).device(DISK_OWNER);
        if(d==null || d.drop().isEmpty()) throw new IllegalStateException("missing disk device");
        var at = d.drop().orElseThrow();
        level.getChunkAt(BlockPos.containing(at.position()));
        // Entity chunks load asynchronously. Wait for the actual entity rather than mistaking the ledger for proof.
        device = (DroppedDigivice) level.getEntity(at.entity());
        if (device == null) { if(tick>200)throw new IllegalStateException("physical entity failed to load"); return; }
        check(true, "disk restart restores owner and saved locator address");
        check(DISK_OWNER.equals(Digivices.owner(device.stack())), "disk restart restores physical entity and bound stack");
        finish(level);
    }
    private DroppedDigivice spawn(ServerLevel level, ItemStack stack, Vec3 at) {
        var drop = new DroppedDigivice(level,stack,at,Vec3.ZERO);
        if(!level.addFreshEntity(drop)) throw new IllegalStateException("test spawn rejected");
        drops.add(drop); return drop;
    }
    private int count(ServerPlayer p) {
        int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(DCItems.DIGIVICE))n+=p.getInventory().getItem(i).getCount();return n;
    }
    private void check(boolean condition,String description) {
        if(!condition)throw new IllegalStateException(description);
        passed++; Constants.LOG.info("[digivice] PASS {}", description);
    }
    private void finish(ServerLevel level) {
        done=true; Constants.LOG.info("[digivice] RESULT {} cases passed",passed);
        for(int x=3;x<=4;x++)level.setChunkForced(x,0,false);
        level.getServer().halt(false);
    }
    private static final class MortalPlayer extends FakePlayer {
        private MortalPlayer(ServerLevel level) { super(level,new GameProfile(UUID.randomUUID(),"DeathTest")); }
        @Override public boolean isInvulnerableTo(ServerLevel level,net.minecraft.world.damagesource.DamageSource source) { return false; }
    }
}
