package com.klywo.hermeswatch.identity;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class AtomicIdentityStorageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void boundedRoundTripReplaceAndDelete() throws Exception {
        Path dir = folder.newFolder().toPath();
        AtomicIdentityStorage s = new AtomicIdentityStorage(dir);
        assertNull(s.read(64));
        byte[] value = new byte[64]; value[0] = 42;
        s.writeAtomic(value); assertArrayEquals(value, s.read(64));
        value[0] = 11; s.writeAtomic(value); assertArrayEquals(value, s.read(64));
        s.delete(); assertNull(s.read(64));
    }
    @Test public void oversizedFileIsRejected() throws Exception {
        Path dir = folder.newFolder().toPath();
        Files.write(dir.resolve("hgidentity.v1"), new byte[65]);
        try { new AtomicIdentityStorage(dir).read(64); fail(); } catch (IOException expected) { }
    }
    @Test public void failedAtomicMovePreservesOldFileAndCleansTemporary() throws Exception {
        Path dir = folder.newFolder().toPath();
        AtomicIdentityStorage normal = new AtomicIdentityStorage(dir);
        byte[] old = new byte[64]; old[1] = 7; normal.writeAtomic(old);
        AtomicIdentityStorage failing = new AtomicIdentityStorage(dir, (from, to) -> { throw new IOException(); });
        try { failing.writeAtomic(new byte[64]); fail(); } catch (IOException expected) { }
        assertArrayEquals(old, normal.read(64));
        try (Stream<Path> files = Files.list(dir)) { assertEquals(1, files.count()); }
    }
    @Test public void invalidWriteLengthCreatesNoFiles() throws Exception {
        Path dir = folder.newFolder().toPath();
        try { new AtomicIdentityStorage(dir).writeAtomic(new byte[65]); fail(); }
        catch (IOException expected) { }
        try (Stream<Path> files = Files.list(dir)) { assertEquals(0, files.count()); }
    }
    @Test public void unsupportedAtomicMoveIsBoundedAndCleansTemporary() throws Exception {
        Path dir = folder.newFolder().toPath();
        AtomicIdentityStorage failing = new AtomicIdentityStorage(dir,
                (from, to) -> { throw new UnsupportedOperationException("sensitive filesystem detail"); });
        DeviceIdentityStoreTest.TestCrypto crypto = new DeviceIdentityStoreTest.TestCrypto();
        DeviceIdentityStore store = new DeviceIdentityStore(failing, crypto, new java.security.SecureRandom());
        DeviceIdentityStoreTest.expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> store.create());
        assertNull(crypto.master);
        DeviceIdentityStoreTest.expect(DeviceIdentityStore.Error.ABSENT, () -> store.load());
        assertEquals(1, crypto.creations);
        try (Stream<Path> files = Files.list(dir)) { assertEquals(0, files.count()); }
    }
    @Test public void unsupportedAtomicReplacementPreservesOldFileAndCleansTemporary() throws Exception {
        Path dir = folder.newFolder().toPath();
        AtomicIdentityStorage normal = new AtomicIdentityStorage(dir);
        byte[] old = new byte[64]; old[1] = 7; normal.writeAtomic(old);
        AtomicIdentityStorage failing = new AtomicIdentityStorage(dir,
                (from, to) -> { throw new UnsupportedOperationException(); });
        try { failing.writeAtomic(new byte[64]); fail(); } catch (UnsupportedOperationException expected) { }
        assertArrayEquals(old, normal.read(64));
        try (Stream<Path> files = Files.list(dir)) { assertEquals(1, files.count()); }
    }
}
