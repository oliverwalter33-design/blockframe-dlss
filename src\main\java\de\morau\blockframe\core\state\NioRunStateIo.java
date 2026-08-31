package de.morau.blockframe.core.state;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** NIO implementation with no-follow reads and a process-lifetime try-lock. */
final class NioRunStateIo implements RunStateIo {
    @Override
    public void ensureDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory);
        }
        BasicFileAttributes attributes = Files.readAttributes(
            directory,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException(
                "run-state location is not a physical directory"
            );
        }
    }

    @Override
    public LockHandle tryAcquire(Path lockFile) throws IOException {
        if (
            Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(lockFile)
        ) {
            throw new IOException("run-state lock path is a symbolic link");
        }
        Set<OpenOption> options = Set.of(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        );
        FileChannel channel = FileChannel.open(lockFile, options);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException conflict) {
                channel.close();
                return null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new ChannelLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    @Override
    public byte[] readBounded(Path path, int maximumBytes)
        throws IOException {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException(
                "maximumBytes must be positive"
            );
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Set<OpenOption> options = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        );
        ByteBuffer buffer = ByteBuffer.allocate(maximumBytes + 1);
        try (FileChannel channel = FileChannel.open(path, options)) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
            if (buffer.position() > maximumBytes) {
                throw new IOException("run-state slot exceeds size limit");
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            if (!buffer.hasRemaining() && channel.read(extra) >= 0) {
                throw new IOException("run-state slot exceeds size limit");
            }
        }
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    @Override
    public void writeForced(Path path, byte[] content) throws IOException {
        Set<OpenOption> options = Set.of(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        );
        try (FileChannel channel = FileChannel.open(path, options)) {
            ByteBuffer source = ByteBuffer.wrap(content);
            while (source.hasRemaining()) {
                channel.write(source);
            }
            channel.force(true);
        }
    }

    @Override
    public void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw unsupported;
        }
    }

    @Override
    public void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private record ChannelLock(
        FileChannel channel,
        FileLock lock
    ) implements LockHandle {
        private ChannelLock {
            if (channel == null || lock == null) {
                throw new NullPointerException("channel and lock");
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                this.lock.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                this.channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
