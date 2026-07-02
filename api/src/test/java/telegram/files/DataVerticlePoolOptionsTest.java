package telegram.files;

import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the shared connection-pool contract (BL-03): the idle timeout must be
 * 300000 MILLISECONDS (5 minutes), not the Vert.x default of SECONDS which would
 * make 300000 mean ~3.5 days and effectively never reap idle connections.
 */
class DataVerticlePoolOptionsTest {

    @Test
    void idleTimeoutIsFiveMinutesInMilliseconds() {
        PoolOptions options = DataVerticle.buildPoolOptions();

        assertEquals(300000, options.getIdleTimeout());
        assertEquals(TimeUnit.MILLISECONDS, options.getIdleTimeoutUnit(),
                "idle-timeout unit must be MILLISECONDS so 300000 means 5 minutes, not ~3.5 days");
        // the cleaner period is already milliseconds; keep them consistent
        assertEquals(300000, options.getPoolCleanerPeriod());
    }

    @Test
    void maxSizeIsBumpedForDownloadBursts() {
        // BL-09: raised 3 -> 5 after checking Saturn PG headroom, to reduce
        // acquisition-timeout risk when download-burst writes contend with HTTP queries.
        assertEquals(5, DataVerticle.buildPoolOptions().getMaxSize());
    }
}
