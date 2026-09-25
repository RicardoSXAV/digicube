package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.digivice.*;
import com.digicube.platform.Services;
import com.digicube.registry.DCItems;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Real item-use, recipe and custody paths on a dedicated server, including unloaded remote custody. */
public final class RecallScenario {
    private static final UUID DISK_OWNER=UUID.fromString("72a520da-9f31-47d1-9420-653da169b3db");
    private ServerPlayer diskPlayer;
    private UUID diskEntity;
    private int tick, checks;
    private boolean done;
    public void tick(ServerLevel level) {
        String scenario=System.getenv("DIGICUBE_SCENARIO");
        if(done || scenario==null || !scenario.startsWith("recall_checks")
                || level.dimension()!=Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment())return;
        try {
            if(scenario.equals("recall_checks_reload")) {reload(level);return;}
            if(tick++==0) {
                // Generate both source chunks before allowing their entity sections time to become accessible.
                level.setChunkForced(3,0,true);level.getChunkAt(new BlockPos(55,300,3));
                var nether=level.getServer().getLevel(Level.NETHER);
                nether.setChunkForced(3,0,true);nether.getChunkAt(new BlockPos(55,100,3));return;
            }
            if(tick<20)return;
            run(level);prepareDisk(level);done=true;
            Constants.LOG.info("[recall] RESULT {} checks passed",checks);
            level.setChunkForced(3,0,false);level.getServer().getLevel(Level.NETHER).setChunkForced(3,0,false);level.getServer().halt(false);
        } catch(Exception error) {
            done=true;Constants.LOG.error("[recall] FAIL after {} checks",checks,error);level.getServer().halt(false);
        }
    }
    private void run(ServerLevel level) {
        var data=DigiviceSavedData.get(level.getServer());
        for(int x=48;x<64;x++)for(int z=0;z<8;z++)level.setBlock(new BlockPos(x,299,z),Blocks.STONE.defaultBlockState(),3);
        var recipe=level.getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,Constants.id("recall_chip"))).orElseThrow();
        var input=CraftingInput.of(3,3,List.of(ItemStack.EMPTY,new ItemStack(Items.GOLD_NUGGET),ItemStack.EMPTY,
                new ItemStack(Items.COPPER_INGOT),new ItemStack(Items.REDSTONE),new ItemStack(Items.COPPER_INGOT),
                ItemStack.EMPTY,new ItemStack(Items.COPPER_INGOT),ItemStack.EMPTY));
        check(recipe.value() instanceof ShapedRecipe shaped && shaped.matches(input,level)
                && shaped.assemble(input).is(DCItems.RECALL_CHIP),"actual loaded recipe crafts one Recall Chip");
        var noDrop=player(level);
        check(use(noDrop,InteractionHand.MAIN_HAND)==InteractionResult.FAIL && noDrop.getMainHandItem().getCount()==1,
                "no device means no chip consumed");
        noDrop.getInventory().setItem(1,Digivices.issue(level.getServer(),noDrop.getUUID()));
        check(use(noDrop,InteractionHand.MAIN_HAND)==InteractionResult.FAIL && noDrop.getMainHandItem().is(DCItems.RECALL_CHIP),
                "held device cannot be duplicated with a chip");
        lost(level);
        partners(level);
        stored(level);
        for(String kind:List.of("near","far","buried","lava","water","void","nether")) {
            var p=player(level);var stack=Digivices.issue(level.getServer(),p.getUUID());
            stack.set(DataComponents.CUSTOM_NAME,Component.literal("Keepsake "+kind));
            ServerLevel source=kind.equals("nether")?level.getServer().getLevel(Level.NETHER):level;
            var drop=new DroppedDigivice(source,stack,new Vec3(55,kind.equals("nether")?100:300,3),Vec3.ZERO);
            check(source.addFreshEntity(drop),kind+": bound source exists");
            if(source.getEntity(drop.getUUID())!=drop)throw new IllegalStateException(kind+": test source chunk is not entity-accessible yet");
            if(kind.equals("buried"))source.setBlock(drop.blockPosition(),Blocks.STONE.defaultBlockState(),3);
            if(kind.equals("lava"))source.setBlock(drop.blockPosition(),Blocks.LAVA.defaultBlockState(),3);
            if(kind.equals("water"))source.setBlock(drop.blockPosition(),Blocks.WATER.defaultBlockState(),3);
            if(kind.equals("void")) {drop.setPos(55,source.getMinY()+1,3);Digivices.remember(drop,source);}
            ItemStack stale=drop.stack().copy();
            if(kind.equals("far")) {
                // Actual entity unload followed by its authoritative saved distant address, with no chunk ticket.
                drop.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                var d=data.device(p.getUUID());
                data.put(new DigiviceSavedData.Device(d.owner(),d.token(),Optional.of(new DigiviceSavedData.Drop(
                        drop.getUUID(),source.dimension().identifier(),new Vec3(10050,-30,3),-1))));
            }
            var hand=kind.equals("water")?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
            var savedAddress=data.device(p.getUUID()).drop().orElseThrow();
            var journey=RecallJourney.plan(savedAddress.position().distanceTo(p.position()),source==level,RecallChip.flyRange(p));
            if(hand==InteractionHand.OFF_HAND) {p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND));p.setItemInHand(hand,new ItemStack(DCItems.RECALL_CHIP));}
            check(use(p,hand) instanceof InteractionResult.Success && p.getItemInHand(hand).is(DCItems.DIGIVICE)
                    && Digivices.usable(p,p.getItemInHand(hand)),kind+": item use delivers owned Digivice into the used hand");
            check(drop.isRemoved() && data.device(p.getUUID()).drop().isEmpty(),kind+": source and locator withdrawn");
            check(p.getItemInHand(hand).getHoverName().getString().equals("Keepsake "+kind),kind+": item components preserved");
            check(!source.addFreshEntity(new DroppedDigivice(source,stale,new Vec3(57,300,3),Vec3.ZERO)),kind+": stale unloaded source cannot reappear");
            for(int i=1;i<journey.ticks();i++)p.getCooldowns().tick();
            check(p.getCooldowns().isOnCooldown(p.getItemInHand(hand)),kind+": use stays locked until the distance-based journey finishes");
            p.getCooldowns().tick();
            check(!p.getCooldowns().isOnCooldown(p.getItemInHand(hand)),kind+": use unlocks at the journey end");
            source.setBlock(new BlockPos(55,300,3),Blocks.AIR.defaultBlockState(),3);
        }
        var foreign=player(level);var target=player(level);var foreignDrop=drop(level,foreign);
        check(use(target,InteractionHand.MAIN_HAND)==InteractionResult.FAIL && !foreignDrop.isRemoved(),"cannot recall another player's device");
        var full=player(level);var fullDrop=drop(level,full);
        for(int i=1;i<full.getInventory().getContainerSize();i++)full.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        full.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DCItems.RECALL_CHIP,3));
        check(use(full,InteractionHand.MAIN_HAND)==InteractionResult.FAIL && !fullDrop.isRemoved()
                && full.getMainHandItem().getCount()==3,"full inventory with remaining chips fails without any mutation");
        full.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DCItems.RECALL_CHIP));
        check(use(full,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && full.getMainHandItem().is(DCItems.DIGIVICE)
                && full.getInventory().getItem(1).getCount()==64,"last chip works even with every other slot full");
        var stacked=player(level);drop(level,stacked);stacked.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DCItems.RECALL_CHIP,4));
        check(use(stacked,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && stacked.getInventory().countItem(DCItems.RECALL_CHIP)==3,
                "exactly one chip consumed; remaining chips stored safely");
        check(!(use(stacked,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success),"repeat use cannot duplicate a recalled device");
        var creative=player(level);drop(level,creative);creative.getAbilities().instabuild=true;
        check(use(creative,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && creative.getInventory().countItem(DCItems.RECALL_CHIP)==1,
                "creative recall preserves its chip in a free slot");
        var gameModePlayer=player(level);drop(level,gameModePlayer);
        check(gameModePlayer.gameMode.useItem(gameModePlayer,level,gameModePlayer.getMainHandItem(),InteractionHand.MAIN_HAND) instanceof InteractionResult.Success
                && Digivices.hasDevice(gameModePlayer),"vanilla game-mode item-use pipeline retains the returned Digivice");
        check(net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker().interact(gameModePlayer,level,InteractionHand.MAIN_HAND)==InteractionResult.FAIL,
                "holding right-click cannot open the Digivice UI during arrival");
        var spectator=player(level);var specDrop=drop(level,spectator);spectator.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        check(!(use(spectator,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success) && !specDrop.isRemoved(),"spectators cannot recall");
        // A death after the visual starts follows normal device custody; there is no unsaved in-flight state.
        Digivices.beforeDeath(stacked);stacked.getInventory().dropAll();Digivices.afterDeath(stacked);
        check(data.device(stacked.getUUID()).drop().isPresent(),"death during visual journey leaves a protected device");
        var ops=RegistryOps.create(NbtOps.INSTANCE,level.registryAccess());
        var encoded=DigiviceSavedData.CODEC.encodeStart(ops,data).getOrThrow();
        var restored=DigiviceSavedData.CODEC.parse(ops,encoded).getOrThrow();
        check(restored.devices().equals(data.devices()) && ItemStack.matches(restored.snapshot(full.getUUID()),data.snapshot(full.getUUID())),
                "saved ledger round trip preserves custody and recalled item components");
        // Old saves without snapshot fields continue to load.
        ((net.minecraft.nbt.CompoundTag)encoded).remove("snapshots");
        check(DigiviceSavedData.CODEC.parse(ops,encoded).getOrThrow().devices().equals(data.devices()),"pre-chip ledger migration accepted");
    }
    /** Whatever made the device stop existing (a chest, a cleared inventory, a burnt shulker box), a new one can be had. */
    private void lost(ServerLevel level) {
        var server=level.getServer();var data=DigiviceSavedData.get(server);
        var lost=player(level);var original=Digivices.issue(server,lost.getUUID());
        original.set(DataComponents.CUSTOM_NAME,Component.literal("Lost keepsake"));
        lost.getInventory().setItem(1,original);Digivices.reconcile(lost);
        ItemStack stored=lost.getInventory().removeItemNoUpdate(1).copy();
        check(!Digivices.hasDevice(lost) && data.device(lost.getUUID()).drop().isEmpty(),"lost: the device is neither held nor dropped");
        var journey=RecallJourney.plan(1000,false,RecallChip.flyRange(lost));
        check(use(lost,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && Digivices.usable(lost,lost.getMainHandItem())
                && lost.getMainHandItem().getHoverName().getString().equals("Lost keepsake"),"lost: the chip summons the device back as it was last seen");
        for(int i=1;i<journey.ticks();i++)lost.getCooldowns().tick();
        check(lost.getCooldowns().isOnCooldown(lost.getMainHandItem()),"lost: use stays locked until it has fallen into the hand");
        lost.getCooldowns().tick();
        check(!lost.getCooldowns().isOnCooldown(lost.getMainHandItem()),"lost: use unlocks as it lands");
        lost.getInventory().setItem(2,stored);Digivices.reconcile(lost);
        check(lost.getInventory().countItem(DCItems.DIGIVICE)==1 && Digivices.hasDevice(lost),"lost: the old copy found later in a chest is dead");
        lost.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(DCItems.RECALL_CHIP));
        check(use(lost,InteractionHand.OFF_HAND)==InteractionResult.FAIL && Digivices.hasDevice(lost),"lost: no second device while one is held");

        var cleared=player(level);cleared.getInventory().setItem(1,Digivices.issue(server,cleared.getUUID()));
        cleared.getInventory().clearContent();Digivices.reconcile(cleared);
        cleared.getInventory().setItem(4,new ItemStack(DCItems.DIGIVICE));Digivices.reconcile(cleared);
        check(Digivices.hasDevice(cleared) && cleared.getInventory().getItem(4).is(DCItems.DIGIVICE),"creative tab: a new Digivice replaces a cleared inventory's");
        cleared.getInventory().setItem(5,new ItemStack(DCItems.DIGIVICE));Digivices.reconcile(cleared);
        check(cleared.getInventory().countItem(DCItems.DIGIVICE)==1,"creative tab: a second one beside the device is still rejected");
        var grounded=player(level);var groundDrop=drop(level,grounded);
        grounded.getInventory().setItem(4,new ItemStack(DCItems.DIGIVICE));Digivices.reconcile(grounded);
        check(Digivices.hasDevice(grounded) && groundDrop.isRemoved() && data.device(grounded.getUUID()).drop().isEmpty(),
                "creative tab: a new Digivice replaces one lying on the ground, whose drop is withdrawn");
    }
    /** Partners live in the Digivice: without it they go back to the Digispace, and none come out. */
    private void partners(ServerLevel level) {
        var server=level.getServer();var party=com.digicube.party.PartySavedData.get(server);
        var tamer=player(level);tamer.getInventory().setItem(1,Digivices.issue(server,tamer.getUUID()));
        var member=com.digicube.party.PartyManager.give(tamer,digimon(level));
        check(member.active() && com.digicube.party.PartyManager.deployed(party,member),"partners: out in the world with the Digivice");
        int now=server.getTickCount();
        check(!com.digicube.party.PartyManager.checkDevice(tamer,now),"partners: the device is with its tamer");
        var device=tamer.getInventory().removeItemNoUpdate(1);
        check(com.digicube.party.PartyManager.checkDevice(tamer,now) && !member.active() && !com.digicube.party.PartyManager.deployed(party,member),
                "partners: they go back to the Digispace the moment it is put down");
        var late=com.digicube.party.PartyManager.give(tamer,digimon(level));
        check(!late.active() && !com.digicube.party.PartyManager.deployed(party,late),"partners: one given meanwhile waits in the Digispace");
        tamer.getInventory().setItem(1,device);
        check(!com.digicube.party.PartyManager.checkDevice(tamer,now+1) && com.digicube.party.PartyManager.select(tamer,member.id(),0).isEmpty()
                && com.digicube.party.PartyManager.deployed(party,member),"partners: with the Digivice back they can go out again");
        // Creative: the inventory's cursor is the client's, and its report keeps the partners out while the device is dragged.
        tamer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var dragged=tamer.getInventory().removeItemNoUpdate(1);
        DigiviceCursorPayload.handle(tamer,new DigiviceCursorPayload(Digivices.token(dragged)));
        check(!com.digicube.party.PartyManager.checkDevice(tamer,now+10) && com.digicube.party.PartyManager.deployed(party,member),
                "creative: a Digivice on the creative cursor is still with its tamer");
        DigiviceCursorPayload.handle(tamer,new DigiviceCursorPayload(DigiviceRecallPayload.NO_TOKEN));
        check(com.digicube.party.PartyManager.checkDevice(tamer,now+20) && com.digicube.party.PartyManager.deployed(party,member),
                "creative: a moment for the cursor report to arrive");
        check(com.digicube.party.PartyManager.checkDevice(tamer,now+20+com.digicube.party.PartyManager.CREATIVE_GRACE_TICKS)
                && !com.digicube.party.PartyManager.deployed(party,member),"creative: then the partners go in too");
        tamer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        DigiviceCursorPayload.handle(tamer,new DigiviceCursorPayload(Digivices.token(dragged)));
        check(!Digivices.hasDevice(tamer),"survival never trusts a cursor report");
    }
    /** A device kept in the world is taken from there, and flies from there: its journey starts at that place. */
    private void stored(ServerLevel level) {
        var server=level.getServer();
        var kept=new java.util.ArrayList<Entity>();
        for(String kind:List.of("chest","hopper","shulker box in a barrel","item frame","armour stand","ender chest")) {
            var p=player(level);var stack=Digivices.issue(server,p.getUUID());
            stack.set(DataComponents.CUSTOM_NAME,Component.literal("Stored "+kind));
            p.getInventory().setItem(1,stack);Digivices.reconcile(p);
            var device=p.getInventory().removeItemNoUpdate(1);
            Vec3 source=null;java.util.function.BooleanSupplier taken;
            switch(kind) {
                case "chest" -> {
                    var chest=container(level,new BlockPos(52,300,6),Blocks.CHEST);chest.setItem(13,device);
                    source=Vec3.atCenterOf(new BlockPos(52,300,6));taken=()->chest.getItem(13).isEmpty();
                }
                case "hopper" -> {
                    var hopper=container(level,new BlockPos(70,300,3),Blocks.HOPPER);hopper.setItem(2,device);
                    source=Vec3.atCenterOf(new BlockPos(70,300,3));taken=()->hopper.getItem(2).isEmpty();
                }
                case "shulker box in a barrel" -> {
                    var barrel=container(level,new BlockPos(54,300,0),Blocks.BARREL);
                    var box=new ItemStack(Items.SHULKER_BOX);
                    box.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND),device)));
                    barrel.setItem(0,box);
                    source=Vec3.atCenterOf(new BlockPos(54,300,0));
                    taken=()->{
                        var contents=barrel.getItem(0).get(DataComponents.CONTAINER);
                        return barrel.getItem(0).is(Items.SHULKER_BOX) && contents!=null && contents.allItemsCopyStream().noneMatch(s->s.is(DCItems.DIGIVICE))
                                && contents.allItemsCopyStream().anyMatch(s->s.is(Items.DIAMOND));
                    };
                }
                case "item frame" -> {
                    var frame=new net.minecraft.world.entity.decoration.ItemFrame(level,new BlockPos(51,301,8),net.minecraft.core.Direction.SOUTH);
                    frame.setItem(device);level.addFreshEntity(frame);kept.add(frame);
                    source=frame.position().add(0,frame.getBbHeight()*.5,0);taken=()->frame.getItem().isEmpty();
                }
                case "armour stand" -> {
                    var stand=new net.minecraft.world.entity.decoration.ArmorStand(level,56.5,300,1.5);
                    stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,device);level.addFreshEntity(stand);kept.add(stand);
                    source=stand.position().add(0,stand.getBbHeight()*.5,0);taken=()->stand.getMainHandItem().isEmpty();
                }
                default -> {
                    p.getEnderChestInventory().setItem(0,device);taken=()->p.getEnderChestInventory().getItem(0).isEmpty();
                }
            }
            var journey=source==null?RecallJourney.plan(1000,false,RecallChip.flyRange(p)):RecallJourney.plan(source.distanceTo(p.position()),true,RecallChip.flyRange(p));
            check(use(p,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && Digivices.usable(p,p.getMainHandItem())
                    && p.getMainHandItem().getHoverName().getString().equals("Stored "+kind),kind+": the chip takes the stored device into the hand");
            check(taken.getAsBoolean(),kind+": it is no longer where it was kept");
            for(int i=1;i<journey.ticks();i++)p.getCooldowns().tick();
            check(p.getCooldowns().isOnCooldown(p.getMainHandItem()),kind+": its journey starts where it was kept");
            p.getCooldowns().tick();
            check(!p.getCooldowns().isOnCooldown(p.getMainHandItem()),kind+": and ends in the hand");
        }
        kept.forEach(Entity::discard);
        // Kept beyond the search, it falls from the sky, and the copy left behind is gone the moment anyone opens its chest.
        var far=player(level);far.getInventory().setItem(1,Digivices.issue(server,far.getUUID()));Digivices.reconcile(far);
        var device=far.getInventory().removeItemNoUpdate(1);
        var farChest=container(level,new BlockPos(250,300,3),Blocks.CHEST);farChest.setItem(0,device);
        check(use(far,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success && Digivices.usable(far,far.getMainHandItem())
                && !farChest.getItem(0).isEmpty(),"far away: a device kept beyond the search is summoned from the sky");
        var viewer=player(level);
        viewer.containerMenu=net.minecraft.world.inventory.ChestMenu.threeRows(1,viewer.getInventory(),farChest);
        Digivices.reconcile(viewer);
        check(farChest.getItem(0).isEmpty() && Digivices.hasDevice(far),"far away: the copy left in the chest is gone the moment anyone opens it");
        viewer.containerMenu=viewer.inventoryMenu;
    }
    private static net.minecraft.world.level.block.entity.BaseContainerBlockEntity container(ServerLevel level,BlockPos pos,net.minecraft.world.level.block.Block block) {
        level.setBlock(pos,block.defaultBlockState(),3);
        var container=(net.minecraft.world.level.block.entity.BaseContainerBlockEntity)level.getBlockEntity(pos);
        container.clearContent();
        return container;
    }
    private static com.digicube.entity.DigimonEntity digimon(ServerLevel level) {
        var digimon=com.digicube.registry.DCEntityTypes.DIGIMON.create(level,net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        digimon.initializeAs(com.digicube.digimon.DigimonSpeciesRegistry.getOrThrow(Constants.id("agumon")),10);
        digimon.setPos(50,300,3);
        return digimon;
    }
    private ServerPlayer player(ServerLevel level) {
        var player=FakePlayer.get(level,new GameProfile(UUID.randomUUID(),"RecallTest"));
        player.getInventory().clearContent();player.getInventory().setSelectedSlot(0);player.setPos(50,300,3);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DCItems.RECALL_CHIP));return player;
    }
    private void prepareDisk(ServerLevel level) {
        var p=FakePlayer.get(level,new GameProfile(DISK_OWNER,"RecallDisk"));p.getInventory().clearContent();
        Digivices.replace(p,new ItemStack(DCItems.DIGIVICE));
        var stack=p.getInventory().removeItemNoUpdate(0);stack.set(DataComponents.CUSTOM_NAME,Component.literal("Distant keepsake"));
        level.getChunkAt(new BlockPos(10050,300,3));
        var drop=new DroppedDigivice(level,stack,new Vec3(10050,300,3),Vec3.ZERO);
        check(level.addFreshEntity(drop),"10,000-block physical source prepared for independent process reload");
        level.getServer().saveEverything(false,true,true);
    }
    private void reload(ServerLevel level) {
        var data=DigiviceSavedData.get(level.getServer());
        if(tick++==0) {
            var saved=data.device(DISK_OWNER);var address=saved.drop().orElseThrow();diskEntity=address.entity();
            check(level.getEntity(diskEntity)==null && !level.getChunkSource().hasChunk(628,0),"restart begins with distant source chunk unloaded");
            diskPlayer=FakePlayer.get(level,new GameProfile(DISK_OWNER,"RecallDisk"));diskPlayer.getInventory().clearContent();diskPlayer.setPos(50,300,3);
            diskPlayer.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(DCItems.RECALL_CHIP));
            check(use(diskPlayer,InteractionHand.MAIN_HAND) instanceof InteractionResult.Success
                    && diskPlayer.getMainHandItem().getHoverName().getString().equals("Distant keepsake"),"recall across a real disk restart preserves the distant stack");
            check(!level.getChunkSource().hasChunk(628,0),"recall did not load the source chunk");
            level.setChunkForced(628,0,true);
            return;
        }
        if(tick<100)return;
        check(level.getChunkSource().hasChunk(628,0) && level.getEntity(diskEntity)==null,"loading the revoked source later does not duplicate the device");
        check(Digivices.hasDevice(diskPlayer),"recalled device remains valid after source loads");
        level.setChunkForced(628,0,false);done=true;
        Constants.LOG.info("[recall-reload] RESULT {} checks passed",checks);level.getServer().halt(false);
    }
    private DroppedDigivice drop(ServerLevel level,ServerPlayer player) {
        var drop=new DroppedDigivice(level,Digivices.issue(level.getServer(),player.getUUID()),new Vec3(55,300,3),Vec3.ZERO);
        if(!level.addFreshEntity(drop))throw new IllegalStateException("source rejected");return drop;
    }
    private InteractionResult use(ServerPlayer player,InteractionHand hand) {
        return player.gameMode.useItem(player,player.level(),player.getItemInHand(hand),hand);
    }
    private void check(boolean condition,String description) {
        if(!condition)throw new IllegalStateException(description);
        checks++;Constants.LOG.info("[recall] PASS {}",description);
    }
}
