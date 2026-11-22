package br.com.civicdrive;

/**
 * Estimativa local de marcha para Civic G8 2011 5AT.
 * NAO e posicao real PRND nem leitura do TCM: usa a relacao entre RPM e km/h.
 * As 5 relacoes, diferencial e pneu 205/55R16 vieram da ficha tecnica Honda.
 * Conversor de torque destravado, pneus diferentes e GPS atrasado podem gerar
 * relacoes intermediarias; nesses casos devolve marcha indisponivel.
 */
public final class GearEstimator {
    private static final double[] GEARS = {2.666, 1.534, 1.021, 0.720, 0.524};
    private static final double FINAL = 4.437;
    private static final double DIAMETER_M = (16d * 25.4d + 2d * 205d * 0.55d) / 1000d;
    private static final double WHEEL_RPM_PER_KMH = 1000d / (Math.PI * DIAMETER_M * 60d);
    private int candidate = 0, repeated = 0;
    private long lastAt = 0L;

    public static final class Result {
        public final int gear, confidence;
        public final String reason;
        private Result(int gear, int confidence, String reason) {
            this.gear = gear;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private Result unavailable(String reason) {
        candidate = 0;
        repeated = 0;
        return new Result(0, 0, reason);
    }

    /** Duas amostras estaveis; nunca adivinhar P/R/N/D nem marcha a carro parado. */
    public Result update(double rpm, double kmh, long timestamp) {
        if (timestamp <= 0L || lastAt == 0L || timestamp - lastAt > 12000L || timestamp < lastAt) {
            candidate = 0;
            repeated = 0;
        }
        lastAt = timestamp;
        if (!Double.isFinite(rpm) || rpm < 550d || rpm > 7000d)
            return unavailable("SEM RPM");
        if (!Double.isFinite(kmh) || kmh < 0d)
            return unavailable("SEM VELOC.");
        if (kmh < 12d)
            return unavailable("PARADO/BAIXA VEL.");
        if (kmh > 220d)
            return unavailable("VELOC. INVALIDA");

        double actual = rpm / kmh;
        int best = 0;
        double bestError = Double.MAX_VALUE, secondError = Double.MAX_VALUE;
        for (int i = 0; i < GEARS.length; i++) {
            double expected = GEARS[i] * FINAL * WHEEL_RPM_PER_KMH;
            double error = Math.abs(actual - expected) / expected;
            if (error < bestError) {
                secondError = bestError;
                bestError = error;
                best = i + 1;
            } else if (error < secondError) {
                secondError = error;
            }
        }
        // Tolera variacoes do conversor, sobretudo em velocidades urbanas.
        // Nunca usar um limite tao largo que duas marchas sejam indistinguiveis.
        double tolerance = kmh < 30d ? 0.32d : kmh < 65d ? 0.26d : 0.22d;
        if (bestError > tolerance)
            return unavailable("RELACAO INCERTA");
        if (secondError - bestError < 0.055d)
            return unavailable("ENTRE MARCHAS");

        int confidence = (int)Math.round(96d - bestError * 130d);
        confidence = Math.max(0, Math.min(98, confidence));
        if (confidence < 55)
            return unavailable("RELACAO INCERTA");

        if (candidate != best) {
            candidate = best;
            repeated = 1;
            // One reading is enough when the ratio is very close and well separated.
            // Otherwise keep the original second-sample safety confirmation.
            if (bestError <= 0.10d && secondError - bestError >= 0.14d)
                return new Result(best, confidence, "OK");
            return new Result(0, 0, "CALCULANDO");
        }
        repeated = Math.min(20, repeated + 1);
        if (repeated < 2)
            return new Result(0, 0, "CALCULANDO");
        return new Result(best, confidence, "OK");
    }
}
