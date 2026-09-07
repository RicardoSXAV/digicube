package com.digicube.digimon;

/** Exact depletion, interrupted use, full-recharge gate and save/reload behavior. */
final class FuelRegressionTest {
    private FuelRegressionTest() {}

    static void run() {
        var timing = new AttackFuel(80, 120, 10);
        var tank = new FuelReserve(timing);
        require(tank.isReady() && tank.begin() && !tank.begin(), "one use at a time");
        for (int i = 0; i < 80; i++) {
            require(tank.consume(), "full four seconds of fuel");
            tank.tickRecharge();
        }
        require(!tank.consume() && tank.savedCharge() == 0, "no recharge while firing and no overdraw");
        tank.end();
        for (int i = 0; i < 119; i++) {
            tank.tickRecharge();
            require(!tank.isReady() && !tank.begin(), "must refill completely");
        }
        var restored = new FuelReserve(timing);
        restored.restore(tank.savedCharge(), tank.isRecharging());
        require(!restored.isReady(), "reload does not reset recharge");
        restored.tickRecharge();
        require(restored.isReady() && restored.begin(), "ready at exactly six seconds");
        for (int i = 0; i < 20; i++) restored.consume();
        tank.restore(restored.savedCharge(), restored.isRecharging());
        require(tank.isReady() && tank.begin(), "interrupted stream can resume without a cooldown before exhaustion");
        tank.end();
        for (int i = 0; i < 29; i++) tank.tickRecharge();
        require(tank.savedCharge() < 80 * 120, "partial recharge preserves the amount of fuel spent");
        tank.tickRecharge();
        require(tank.savedCharge() == 80 * 120, "one second spent needs 1.5 seconds to refill");
        require(!restored.isReady(), "individual tanks are independent");
        tank.begin();tank.end();
        require(tank.isReady(), "interrupted windup has not spent fuel");
        tank.restore(-1, false);require(tank.savedCharge() == 0 && !tank.isReady(), "negative saved fuel clamped");
        tank.restore(Integer.MAX_VALUE, true);require(tank.isReady(), "excess saved fuel clamped");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
