package telegram.files;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.*;
import telegram.files.repository.FileRecord;

import java.util.List;

public class FileCreateRaceTest {

    static Vertx vertx = Vertx.vertx();

    static FileRecord raceRecord = new FileRecord(
            100, "race_unique_id", 1, 1, 100, 0, 1, false, 1024, 0,
            "file", "application/octet-stream", "race_test.bin", null, null, null, null, null,
            FileRecord.DownloadStatus.idle.name(), FileRecord.TransferStatus.idle.name(),
            0, null, null, 0, 0, 0, null, null, null
    );

    @BeforeAll
    static void setUpAll() {
        Future<String> future = vertx.deployVerticle(new DataVerticle());
        MessyUtils.await(future);
    }

    @AfterAll
    static void tearDownAll() {
        DataVerticleTest.clear(vertx);
    }

    @Test
    @DisplayName("Concurrent createIfNotExist for same uniqueId should not fail")
    void concurrentCreateIfNotExist_neitherFails() {
        Future<Boolean> first = DataVerticle.fileRepository.createIfNotExist(raceRecord);
        Future<Boolean> second = DataVerticle.fileRepository.createIfNotExist(raceRecord);

        CompositeFuture both = MessyUtils.await(Future.all(first, second));

        boolean firstResult = both.resultAt(0);
        boolean secondResult = both.resultAt(1);

        Assertions.assertTrue(
                firstResult || secondResult,
                "At least one createIfNotExist should return true (created)"
        );

        FileRecord stored = MessyUtils.await(
                DataVerticle.fileRepository.getByUniqueId(raceRecord.uniqueId())
        );
        Assertions.assertNotNull(stored, "Record must exist after concurrent creates");
        Assertions.assertEquals(raceRecord.uniqueId(), stored.uniqueId());
    }

    @Test
    @DisplayName("Multiple concurrent createIfNotExist calls all succeed without exception")
    void multipleCreateIfNotExist_allSucceed() {
        FileRecord rec = new FileRecord(
                200, "multi_race_unique", 1, 1, 200, 0, 1, false, 512, 0,
                "file", "text/plain", "multi_race.txt", null, null, null, null, null,
                FileRecord.DownloadStatus.idle.name(), FileRecord.TransferStatus.idle.name(),
                0, null, null, 0, 0, 0, null, null, null
        );

        List<Future<Boolean>> futures = List.of(
                DataVerticle.fileRepository.createIfNotExist(rec),
                DataVerticle.fileRepository.createIfNotExist(rec),
                DataVerticle.fileRepository.createIfNotExist(rec),
                DataVerticle.fileRepository.createIfNotExist(rec),
                DataVerticle.fileRepository.createIfNotExist(rec)
        );

        CompositeFuture all = MessyUtils.await(Future.all(futures));

        long createdCount = 0;
        for (int i = 0; i < futures.size(); i++) {
            Boolean result = all.resultAt(i);
            if (Boolean.TRUE.equals(result)) createdCount++;
        }

        Assertions.assertTrue(createdCount >= 1, "At least one call should report created=true");

        FileRecord stored = MessyUtils.await(
                DataVerticle.fileRepository.getByUniqueId(rec.uniqueId())
        );
        Assertions.assertNotNull(stored);
    }
}
