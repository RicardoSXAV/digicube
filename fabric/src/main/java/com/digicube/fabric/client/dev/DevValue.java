package com.digicube.fabric.client.dev;

/**
 * The number behind one row of the developer panel. A toggle stores 0 or 1 and a choice its
 * option index, so every row kind reads and writes the same way. {@link #defaultValue} is what
 * the repository holds: a row whose value differs from it is shown as changed.
 */
public interface DevValue {
    double get();

    void set(double value);

    double defaultValue();

    /** The current value becomes the default: it has been written to the repository. */
    void commit();

    default boolean changed() {
        return Math.abs(get() - defaultValue()) > 1.0E-9;
    }

    /** A value that lives only in the panel; what an example row uses until it is wired to the game. */
    static DevValue local(double initial) {
        return new DevValue() {
            private double value = initial, standard = initial;

            @Override public double get() { return value; }
            @Override public void set(double next) { value = next; }
            @Override public double defaultValue() { return standard; }
            @Override public void commit() { standard = value; }
        };
    }
}
