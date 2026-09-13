package com.digicube.entity;

import java.util.Collection;

/** A level that knows which Digimon currently carry extra hit parts; implemented onto {@code Level} by mixin. */
public interface PartedLevel {
    /** Remember a Digimon with parts; idempotent, and removed entities drop out on the next query. */
    void digicube$track(DigimonEntity digimon);

    /** Every live tracked Digimon in this level. */
    Collection<DigimonEntity> digicube$parted();
}
