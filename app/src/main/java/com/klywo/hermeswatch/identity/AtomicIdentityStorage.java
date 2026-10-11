package com.klywo.hermeswatch.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;

/** API 26 java.nio implementation. Use only in an app-private, non-backed-up directory. */
public final class AtomicIdentityStorage implements DeviceIdentityStore.Storage {
    interface AtomicMove { void move(Path from, Path to) throws IOException; }
    private final Path directory;
    private final Path target;
    private final AtomicMove move;
    public AtomicIdentityStorage(Path directory) {
        this(directory, (from, to) -> Files.move(from, to,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    AtomicIdentityStorage(Path directory, AtomicMove move) {
        this.directory = Objects.requireNonNull(directory);
        this.target = directory.resolve("hgidentity.v1");
        this.move = Objects.requireNonNull(move);
    }
    @Override public byte[] read(int maximum) throws IOException {
        if (maximum < 1 || maximum > DeviceIdentityStore.MAX_FILE_BYTES) throw new IOException("Invalid bound");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid file");
        byte[] bytes = new byte[maximum + 1];
        int used = 0;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            while (used < bytes.length) {
                int n = input.read(bytes, used, bytes.length - used);
                if (n < 0) break;
                used += n;
            }
        }
        if (used > maximum) throw new IOException("File exceeds bound");
        return Arrays.copyOf(bytes, used);
    }
    @Override public void writeAtomic(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != DeviceIdentityStore.MAX_FILE_BYTES)
            throw new IOException("Invalid record size");
        // Same directory/filesystem; atomic rename is mandatory, never a copy/delete fallback.
        Path temporary = Files.createTempFile(directory, ".hgidentity-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            move.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void delete() throws IOException { Files.deleteIfExists(target); }
}
