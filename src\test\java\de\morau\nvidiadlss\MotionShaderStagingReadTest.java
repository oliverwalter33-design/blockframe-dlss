package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class MotionShaderStagingReadTest {
    @Test
    void readsDirectlyIntoAReusableBufferAndFlipsIt() throws Exception {
        byte[] expected = new byte[] {
            3, 2, 35, 7, 11, 13, 17
        };
        ByteBuffer staging = ByteBuffer.allocateDirect(32);

        assertTrue(
            MotionVectorGenerator.readShaderIntoFixedBuffer(
                new ByteArrayInputStream(expected),
                staging
            )
        );

        byte[] actual = new byte[staging.remaining()];
        staging.get(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void acceptsAResourceThatExactlyFillsTheFixedBlock()
        throws Exception {
        byte[] expected = new byte[32];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte)(index * 3);
        }
        ByteBuffer staging = ByteBuffer.allocateDirect(expected.length);

        assertTrue(
            MotionVectorGenerator.readShaderIntoFixedBuffer(
                new ByteArrayInputStream(expected),
                staging
            )
        );
        byte[] actual = new byte[staging.remaining()];
        staging.get(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void reportsOversizeSoTheCallerCanReopenTheDirectFallback()
        throws Exception {
        byte[] oversized = new byte[33];
        ByteBuffer staging = ByteBuffer.allocateDirect(32);

        assertFalse(
            MotionVectorGenerator.readShaderIntoFixedBuffer(
                new ByteArrayInputStream(oversized),
                staging
            )
        );
    }

    @Test
    void propagatesLoadFailureForTheBorrowReleaseFinallyPath() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic shader read failure");
            }

            @Override
            public int read(byte[] bytes, int offset, int length)
                throws IOException {
                throw new IOException("synthetic shader read failure");
            }
        };

        assertThrows(
            IOException.class,
            () -> MotionVectorGenerator.readShaderIntoFixedBuffer(
                failing,
                ByteBuffer.allocateDirect(32)
            )
        );
    }
}
