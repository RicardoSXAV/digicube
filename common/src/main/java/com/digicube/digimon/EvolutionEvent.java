package com.digicube.digimon;

import net.minecraft.resources.Identifier;

/** Bounded tracked presentation event; late trackers derive elapsed time instead of restarting it. */
public record EvolutionEvent(Identifier source,Identifier target,long start,int duration,long sequence,boolean preview) {
    public String encode(){return source+"|"+target+"|"+start+"|"+duration+"|"+sequence+"|"+preview;}
    public static EvolutionEvent decode(String value) {
        if(value.isEmpty()||value.length()>512)return null;String[] p=value.split("\\|");if(p.length!=6)return null;
        try{var a=Identifier.tryParse(p[0]);var b=Identifier.tryParse(p[1]);int duration=Integer.parseInt(p[3]);
            if(p[0].isBlank()||p[1].isBlank()||a==null||b==null||Long.parseLong(p[4])<0||!EvolutionTimeline.validDuration(duration))return null;
            return new EvolutionEvent(a,b,Long.parseLong(p[2]),duration,Long.parseLong(p[4]),Boolean.parseBoolean(p[5]));
        }catch(NumberFormatException bad){return null;}
    }
}
