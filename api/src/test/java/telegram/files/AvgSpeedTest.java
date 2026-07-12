package telegram.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvgSpeedTest {
    private AvgSpeed avgSpeed;

    private static final int TEST_INTERVAL = 100; // 100 seconds for testing

    private static final int SMOOTHING_WINDOW = 3;

    @BeforeEach
    void setUp() {
        avgSpeed = new AvgSpeed(TEST_INTERVAL, SMOOTHING_WINDOW);
    }

    @Test
    void testInitialState() {
        assertEquals(0, avgSpeed.getSpeed());
        assertEquals(0, avgSpeed.getMedianSpeed());
        assertEquals(0, avgSpeed.getMaxSpeed());
        assertEquals(0, avgSpeed.getMinSpeed());
    }

    @Test
    void testConstantSpeed() {
        // Simulate constant download speed of 100 bytes per second
        long baseTime = System.currentTimeMillis();


        avgSpeed.update(0L, baseTime);
        avgSpeed.update(1000L, baseTime + 10000); // 100 bytes/sec for 10 seconds
        avgSpeed.update(2000L, baseTime + 20000); // 100 bytes/sec for another 10 seconds

        AvgSpeed.SpeedStats stats = avgSpeed.getSpeedStats();
        assertEquals(100, stats.avgSpeed(), 1.0, "Average speed should be 100 bytes/sec");
    }

    @Test
    void testSpeedWithPause() {
        long baseTime = System.currentTimeMillis();


        // Download at 100 bytes/sec
        avgSpeed.update(1L, baseTime);
        avgSpeed.update(1001L, baseTime + 10000); // 100 bytes/sec

        // Pause for 10 seconds
        avgSpeed.update(1000L, baseTime + 20000);

        // Resume at 100 bytes/sec
        avgSpeed.update(2000L, baseTime + 30000);

        // Average speed should be: 2000 bytes / 30 seconds = 66.67 bytes/sec
        assertEquals(67, avgSpeed.getSpeed(), 1.0,
                "Speed should account for pause period");
    }

    @Test
    void testDownloadRestart() {
        long baseTime = System.currentTimeMillis();


        // Initial download
        avgSpeed.update(1L, baseTime);
        avgSpeed.update(1001L, baseTime + 10000); // 100 bytes/sec

        // Restart download
        avgSpeed.update(1L, baseTime + 15000);
        avgSpeed.update(501L, baseTime + 20000); // 100 bytes/sec after restart

        AvgSpeed.SpeedStats stats = avgSpeed.getSpeedStats();
        assertTrue(stats.maxSpeed() >= 100,
                "Max speed should capture the highest speed segment");
    }

    @Test
    void testSpeedFluctuation() {
        long baseTime = System.currentTimeMillis();


        avgSpeed.update(0L, baseTime);
        avgSpeed.update(500L, baseTime + 5000);    // 100 bytes/sec
        avgSpeed.update(2500L, baseTime + 10000);  // 400 bytes/sec
        avgSpeed.update(3000L, baseTime + 15000);  // 100 bytes/sec

        AvgSpeed.SpeedStats stats = avgSpeed.getSpeedStats();
        assertTrue(stats.maxSpeed() >= 300,
                "Max speed should capture high speed period");
        assertTrue(stats.minSpeed() == 250,
                "Min speed should capture low speed period");
    }

    @Test
    void testDataSmoothing() {
        long baseTime = System.currentTimeMillis();


        avgSpeed.update(0L, baseTime);
        avgSpeed.update(1000L, baseTime + 10000);   // 100 bytes/sec
        avgSpeed.update(2000L, baseTime + 20000);   // 100 bytes/sec
        avgSpeed.update(7000L, baseTime + 30000);   // 500 bytes/sec (spike)
        avgSpeed.update(8000L, baseTime + 40000);   // 100 bytes/sec

        AvgSpeed.SpeedStats stats = avgSpeed.getSpeedStats();
        assertTrue(stats.avgSpeed() < 300,
                "Smoothed speed should be less than the spike value");
    }

    @Test
    void testOldDataRemoval() {
        long baseTime = System.currentTimeMillis();


        // Add initial points
        avgSpeed.update(0L, baseTime);
        avgSpeed.update(1000L, baseTime + 10000); // 100 bytes/sec

        // Add point beyond interval
        // Note: Need to move forward by interval milliseconds plus a buffer
        avgSpeed.update(2000L, baseTime + (TEST_INTERVAL * 1000L) + 20000);

        long speed = avgSpeed.getSpeed();
        assertTrue(speed < 50,
                "Speed should be very low when most points are outside the interval");
    }

    @Test
    void testMedianCalculation() {
        long baseTime = System.currentTimeMillis();


        // Create a sequence of very distinct speeds for clear median
        avgSpeed.update(0L, baseTime);
        avgSpeed.update(1000L, baseTime + 5000);   // 200 bytes/sec
        avgSpeed.update(1500L, baseTime + 10000);  // 100 bytes/sec
        avgSpeed.update(3500L, baseTime + 15000);  // 400 bytes/sec

        long medianSpeed = avgSpeed.getMedianSpeed();
        assertTrue(medianSpeed >= 100 && medianSpeed <= 400,
                "Median speed should be between the lowest and highest speeds");
    }

    @Test
    void testExtremeCases() {
        long baseTime = System.currentTimeMillis();

        // Test with zero time difference
        avgSpeed.update(0L, baseTime);
        avgSpeed.update(100L, baseTime);
        assertEquals(0, avgSpeed.getSpeed(),
                "Speed should be 0 when time difference is 0");

        // Test with large downloads
        avgSpeed.update(0L, baseTime + 1000);
        avgSpeed.update(500000L, baseTime + 6000); // 100,000 bytes/sec
        assertTrue(avgSpeed.getSpeed() > 0,
                "Speed should be calculated correctly with large numbers");
    }

    @Test
    void shouldShowDifferentSpeedsOverTime() {
        // Given
        AvgSpeed avgSpeed = new AvgSpeed(60); // 1 minute interval
        long baseTime = System.currentTimeMillis();

        // When - simulate download over 2 minutes with varying speeds
        // First 30 seconds - constant speed
        for (int i = 0; i < 30; i++) {
            avgSpeed.update(1024L * 1024L * i, // 1MB increments
                    baseTime + (i * 1000L)); // 1 second intervals
        }

        // Record speeds at 30 second mark
        AvgSpeed.SpeedStats stats30s = avgSpeed.getSpeedStats();

        // Next 30 seconds - faster speed
        for (int i = 30; i < 60; i++) {
            avgSpeed.update(1024L * 1024L * (i * 2), // 2MB increments
                    baseTime + (i * 1000L)); // 1 second intervals
        }

        // Record speeds at 60 second mark
        AvgSpeed.SpeedStats stats60s = avgSpeed.getSpeedStats();

        // Another 30 seconds - slower speed
        for (int i = 60; i < 90; i++) {
            avgSpeed.update(1024L * 1024L * (i + 60), // slower increments
                    baseTime + (i * 1000L)); // 1 second intervals
        }

        // Record speeds at 90 second mark
        AvgSpeed.SpeedStats stats90s = avgSpeed.getSpeedStats();

        // 30s with big speed changes
        for (int i = 90; i < 120; i++) {
            avgSpeed.update(1024L * 1024L * (i * 4), // 4MB increments
                    baseTime + (i * 1000L)); // 1 second intervals
        }

        // Record speeds at 120 second mark
        AvgSpeed.SpeedStats stats120s = avgSpeed.getSpeedStats();

        // Then -- assert the documented monotonic relationships across the four phases.
        // Phase 1 (0-30s): 1MB/s cumulative steps. Phase 2 (30-60s): 2MB steps -> strictly faster.
        // Phase 3 (60-90s): slower increments. Phase 4 (90-120s): 4MB steps -> fastest.

        // Every snapshot records real, positive throughput once data is flowing.
        assertTrue(stats30s.avgSpeed() > 0, "30s window must show positive average speed");
        assertTrue(stats60s.avgSpeed() > 0, "60s window must show positive average speed");
        assertTrue(stats120s.avgSpeed() > 0, "120s window must show positive average speed");

        // maxSpeed is a running high-water mark: it can never decrease as faster segments arrive.
        assertTrue(stats60s.maxSpeed() >= stats30s.maxSpeed(),
                "maxSpeed must be monotonically non-decreasing (30s -> 60s)");
        assertTrue(stats90s.maxSpeed() >= stats60s.maxSpeed(),
                "maxSpeed must be monotonically non-decreasing (60s -> 90s)");
        assertTrue(stats120s.maxSpeed() >= stats90s.maxSpeed(),
                "maxSpeed must be monotonically non-decreasing (90s -> 120s)");

        // The 4MB-step final phase is the fastest segment overall, so the final high-water mark
        // must strictly exceed the first-phase (1MB-step) high-water mark.
        assertTrue(stats120s.maxSpeed() > stats30s.maxSpeed(),
                "Fastest phase (4MB steps) must push maxSpeed above the first phase (1MB steps)");

        // The 2MB-step phase produces a higher window average than the 1MB-step phase.
        assertTrue(stats60s.avgSpeed() > stats30s.avgSpeed(),
                "Doubling the per-second increment must raise the windowed average speed");

        // Interval metadata is preserved unchanged across snapshots.
        assertEquals(60, stats30s.interval(), "SpeedStats must carry the configured interval");
        assertEquals(60, stats120s.interval(), "SpeedStats must carry the configured interval");
    }
}
