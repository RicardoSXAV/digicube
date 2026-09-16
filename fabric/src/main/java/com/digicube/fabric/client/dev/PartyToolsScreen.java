package com.digicube.fabric.client.dev;

import com.digicube.dev.*;
import com.digicube.digimon.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Plain developer tooling, UUID selection spans deployed and reserve partners. */
final class PartyToolsScreen extends Screen {
    private final DevClient client;
    private MemberPicker members;
    private SpeciesDropdown target;
    private EditBox level;
    private int x,y;
    private record Member(UUID id,String label) {}
    private final class MemberPicker extends Dropdown<Member> {
        MemberPicker(List<Member> options){super(PartyToolsScreen.this.font,x,y,270,18,Component.literal("Partner"),options,null,m->{});}
        @Override protected String label(Member member){return member.label;}
    }
    PartyToolsScreen(DevClient client){super(Component.literal("Party testing"));this.client=client;}
    @Override protected void init() {
        x=Math.max(8,(width-288)/2);y=Math.max(22,(height-208)/2);
        var options=client.state().getListOrEmpty(DevState.PARTY).compoundStream().filter(t->t.contains("uuid")).map(t->new Member(UUID.fromString(t.getStringOr("uuid","")),t.getStringOr("name","?")+" · Lv "+t.getIntOr("level",1)+" · "+(t.getIntOr("slot",-1)<0?"reserve":"slot "+(t.getIntOr("slot",-1)+1))+" · "+t.getStringOr("uuid","").substring(0,8))).toList();
        members=addRenderableWidget(new MemberPicker(options));
        level=addRenderableWidget(new EditBox(font,x+126,y+24,38,18,Component.literal("Set level")));level.setMaxLength(2);level.setValue("20");
        button("+1",0,24,38,()->act("level_add",1));button("+5",42,24,38,()->act("level_add",5));
        button("Set level",170,24,100,()->{try{act("level",Integer.parseInt(level.getValue()));}catch(NumberFormatException ignored){}});
        button("Fill DigiSoul",0,47,132,()->act("charge",Progression.DIGISOUL_CAPACITY));button("Empty DigiSoul",138,47,132,()->act("charge",0));
        button("Evolve",0,70,132,()->act("evolve",0));button("Revert",138,70,132,()->act("revert",0));
        target=addRenderableWidget(new SpeciesDropdown(font,x,y+98,270,18,DigimonSpeciesRegistry.get(client.species()).orElse(null),s->{}));
        button("Preview long",0,121,88,()->act("preview",com.digicube.digimon.EvolutionTimeline.LONG.duration()));button("Short",92,121,86,()->act("preview",32));button("Return",182,121,88,()->act("preview",16));
        button("Choose selected Rookie as origin",0,144,270,()->act("origin",0));
        button("Back",0,185,70,()->minecraft.gui.setScreen(new DevPanelScreen(client)));
    }
    private void button(String label,int dx,int dy,int w,Runnable action){addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x+dx,y+dy,w,18).build());}
    private void act(String operation,int value) {
        if(members.selected()==null)return;var args=client.speciesArgs();args.putString("member",members.selected().id.toString());args.putString("operation",operation);args.putInt("value",value);
        if(target.selected()!=null)args.putString("target",target.selected().id().toString());client.send(DevActions.PARTY_TOOL,args);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float t) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float t) {
        g.fill(x-6,y-18,x+278,y+207,0xec101820);g.text(font,title,x,y-13,0xffffffff,false);
        var chosen=members.selected();
        if(chosen!=null)client.state().getListOrEmpty(DevState.PARTY).compoundStream().filter(tag->tag.getStringOr("uuid","").equals(chosen.id.toString())).findFirst().ifPresent(tag->{
            g.text(font,tag.getStringOr("phase","")+" · soul "+tag.getIntOr("soul",0)+" · Lv "+tag.getIntOr("level",1)+" XP "+tag.getIntOr("xp",0),x,y+168,0xffa9dcff,false);
        });
        g.text(font,font.plainSubstrByWidth(client.reply(),186),x+80,y+190,0xffffd79a,false);
        super.extractRenderState(g,mx,my,t);members.extractPopup(g,mx,my,t);target.extractPopup(g,mx,my,t);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice){return members.handleClick(e,twice)||target.handleClick(e,twice)||super.mouseClicked(e,twice);}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){return members.handleScroll(x,y,h,v)||target.handleScroll(x,y,h,v)||super.mouseScrolled(x,y,h,v);}
    @Override public boolean isPauseScreen(){return false;}
}
