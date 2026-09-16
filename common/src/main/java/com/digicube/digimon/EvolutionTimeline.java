package com.digicube.digimon;

/** Shared event timing. The long preset absorbs data before the original transformation begins. */
public record EvolutionTimeline(int duration, float leadIn, float shedStart, float shedEnd, float morphStart,
                                float morphEnd, float skinEnd, boolean returning) {
    public static final EvolutionTimeline LONG = new EvolutionTimeline(220,60,72,100,116,160,196,false);
    public static final EvolutionTimeline SHORT = new EvolutionTimeline(32,0,2,8,11,20,28,false);
    public static final EvolutionTimeline RETURN = new EvolutionTimeline(16,0,0,3,4,10,14,true);
    // Existing tracked/saved events may still carry the previous long duration.
    private static final EvolutionTimeline LEGACY_ABSORPTION = new EvolutionTimeline(230,70,82,110,126,170,206,false);
    private static final EvolutionTimeline LEGACY_LONG = new EvolutionTimeline(160,0,12,40,56,100,136,false);
    public static EvolutionTimeline of(int duration) {
        return switch(duration) { case 16 -> RETURN; case 32 -> SHORT; case 160 -> LEGACY_LONG; case 230 -> LEGACY_ABSORPTION; default -> LONG; };
    }
    public static boolean validDuration(int duration) { return duration==16||duration==32||duration==160||duration==230||duration==LONG.duration; }
    public boolean longForm() { return !returning && duration!=SHORT.duration; }
    public float bodyTick(float tick) { return tick-leadIn; }
    public static float smooth(float v){v=Math.clamp(v,0,1);return v*v*v*(v*(v*6-15)+10);}
    public float shed(float tick){return smooth((tick-shedStart)/(shedEnd-shedStart));}
    public float morph(float tick){return smooth((tick-morphStart)/(morphEnd-morphStart));}
    public float skin(float tick){return smooth((tick-morphEnd)/(skinEnd-morphEnd));}
}
