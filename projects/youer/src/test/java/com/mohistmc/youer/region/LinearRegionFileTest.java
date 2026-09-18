package com.mohistmc.youer.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinearRegionFileTest {
    private static final long SIGNATURE = 0xc3ff13183cca9d9aL;
    private static final RegionStorageInfo INFO = new RegionStorageInfo("test", null, "chunk");

    @AfterAll
    static void stopFlusher() {
        LinearRegionFileFlusher.shutdown();
    }

    @Test
    void incrementalRoundTripAndTailRecovery(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("r.0.0.linear");
        ChunkPos pos = new ChunkPos(3, 7);
        byte[] first = "first payload".getBytes();
        byte[] second = "second payload with more bytes".getBytes();

        try (LinearRegionFile region = new LinearRegionFile(INFO, path, directory, false)) {
            write(region, pos, first);
            region.flush();
            assertArrayEquals(first, read(region, pos));
            write(region, pos, second);
            region.flush();
            assertArrayEquals(second, read(region, pos));
        }

        Files.write(path, new byte[] {1, 2, 3, 4, 5}, StandardOpenOption.APPEND);
        long damagedSize = Files.size(path);
        try (LinearRegionFile recovered = new LinearRegionFile(INFO, path, directory, false)) {
            assertArrayEquals(second, read(recovered, pos));
            assertTrue(Files.size(path) < damagedSize);
            recovered.clear(pos);
            recovered.flush();
        }
        try (LinearRegionFile reopened = new LinearRegionFile(INFO, path, directory, false)) {
            assertNull(reopened.getChunkDataInputStream(pos));
        }
    }

    @Test
    void migratesV1WithoutLosingChunks(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("r.0.0.linear");
        ChunkPos pos = new ChunkPos(1, 2);
        byte[] payload = "legacy-v1-payload".getBytes();
        writeV1(path, pos, payload);

        try (LinearRegionFile migrated = new LinearRegionFile(INFO, path, directory, false)) {
            assertArrayEquals(payload, read(migrated, pos));
        }

        byte[] prefix = Files.readAllBytes(path);
        assertEquals(2, Byte.toUnsignedInt(prefix[8]));
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(candidate -> candidate.getFileName().toString().contains(".v1.") && candidate.getFileName().toString().endsWith(".bak")));
        }
    }

    @Test
    void mergesNewerAnvilChunksAndArchivesSource(@TempDir Path directory) throws Exception {
        Path linearPath = directory.resolve("r.0.0.linear");
        Path anvilPath = directory.resolve("r.0.0.mca");
        ChunkPos pos = new ChunkPos(4, 5);

        try (LinearRegionFile linear = new LinearRegionFile(INFO, linearPath, directory, false)) {
            write(linear, pos, "linear".getBytes());
            linear.flush();
        }
        try (RegionFile anvil = new RegionFile(INFO, anvilPath, directory, false)) {
            try (DataOutputStream output = anvil.getChunkDataOutputStream(pos)) {
                output.write("anvil-newer".getBytes());
            }
            anvil.flush();
        }
        Files.setLastModifiedTime(anvilPath, FileTime.fromMillis(Files.getLastModifiedTime(linearPath).toMillis() + 2_000L));

        try (LinearRegionFile linear = new LinearRegionFile(INFO, linearPath, directory, false)) {
            assertEquals(1, linear.mergeFromAnvil(anvilPath, 0, 0));
            assertArrayEquals("anvil-newer".getBytes(), read(linear, pos));
        }
        assertFalse(Files.exists(anvilPath));
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(candidate -> candidate.getFileName().toString().contains(".mca.merged.") && candidate.getFileName().toString().endsWith(".bak")));
        }
    }

    private static void write(LinearRegionFile region, ChunkPos pos, byte[] payload) throws IOException {
        try (DataOutputStream output = region.getChunkDataOutputStream(pos)) {
            output.write(payload);
        }
    }

    private static byte[] read(LinearRegionFile region, ChunkPos pos) throws IOException {
        try (DataInputStream input = region.getChunkDataInputStream(pos)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static void writeV1(Path path, ChunkPos pos, byte[] payload) throws IOException {
        int slot = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
        ByteArrayOutputStream compressedBody = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(compressedBody, 1);
             DataOutputStream body = new DataOutputStream(zstd)) {
            for (int index = 0; index < 1024; index++) {
                body.writeInt(index == slot ? payload.length : 0);
                body.writeInt(index == slot ? 1234 : 0);
            }
            body.write(payload);
        }

        byte[] compressed = compressedBody.toByteArray();
        ByteBuffer header = ByteBuffer.allocate(32);
        header.putLong(SIGNATURE);
        header.put((byte) 1);
        header.putLong(1234L);
        header.put((byte) 1);
        header.putShort((short) 1);
        header.putInt(compressed.length);
        header.put(new byte[8]);
        try (var output = Files.newOutputStream(path)) {
            output.write(header.array());
            output.write(compressed);
            output.write(ByteBuffer.allocate(Long.BYTES).putLong(SIGNATURE).array());
        }
    }
}
