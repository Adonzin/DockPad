package com.adonzin.dockpad.service;

/**
 * How long DockPad holds a controller's input: from the moment the kernel stamped a report
 * to the moment DockPad has passed it on to the virtual controller. Also how often the
 * controller sends reports (its polling rate, which Bluetooth largely decides).
 */
final class LatencyStats {
    private final long[] delays = new long[512]; // microseconds
    private int count, next;
    private long windowStart, windowReports;
    private final int[] rates = new int[30]; // reports per second, last 30 busy seconds
    private int rateNext;
    boolean monotonic;

    synchronized void report(long eventMicros) {
        long now = monotonic ? System.nanoTime() / 1000 : System.currentTimeMillis() * 1000;
        long d = now - eventMicros;
        if (d >= 0 && d < 1_000_000) {
            delays[next] = d;
            next = (next + 1) % delays.length;
            if (count < delays.length) count++;
        }
        if (windowStart == 0) windowStart = now;
        windowReports++;
        long span = now - windowStart;
        if (span >= 1_000_000) {
            int rate = (int) (windowReports * 1_000_000 / span);
            if (rate >= 20) { // only seconds where the controller was being used
                rates[rateNext] = rate;
                rateNext = (rateNext + 1) % rates.length;
            }
            windowStart = now;
            windowReports = 0;
        }
    }

    synchronized void reset() {
        count = next = 0;
        windowStart = windowReports = 0;
        java.util.Arrays.fill(rates, 0);
    }

    synchronized String summary() {
        if (count == 0) return "no input measured yet (move a stick)";
        long sum = 0, worst = 0;
        long[] sorted = new long[count];
        for (int i = 0; i < count; i++) {
            sum += delays[i];
            worst = Math.max(worst, delays[i]);
            sorted[i] = delays[i];
        }
        java.util.Arrays.sort(sorted);
        long p95 = sorted[Math.min(count - 1, (int) (count * 0.95))];
        int peak = 0;
        for (int r : rates) peak = Math.max(peak, r);
        return String.format(java.util.Locale.US, "DockPad's delay %s avg, %s at worst (95%% under %s)%s · %s",
                ms(sum / count), ms(worst), ms(p95), monotonic ? "" : " (rough)",
                peak > 0 ? "sends up to " + peak + " reports/s" : "report rate: move a stick for a second");
    }

    private static String ms(long us) {
        return String.format(java.util.Locale.US, "%.1f ms", us / 1000.0);
    }
}
