package org.koreader.backgroundonyxsynckoreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.koreader.backgroundonyxsynckoreader.kosync.KoReaderDocumentHash;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Locks the document hash to KOReader's util.partialMD5 wire format.
 *
 * Expected values were generated with an independent port of KOReader's Lua
 * (1024-byte chunks at offset 0 then 1024 << (2*i) for i = 0..10; LuaJIT's
 * lshift(1024, -2) masks to 0, so the first chunk is the file head, NOT
 * offset 256). If this test breaks, cross-device book matching with
 * KOReader and CrossPoint silently breaks too.
 */
public class KoReaderDocumentHashTest {

    @Test
    public void partialMd5MatchesKoReaderReference() throws Exception {
        assertEquals("b15835f133ff2e27c7cb28117bfae8f4", hashOfSeededFile(1));
        assertEquals("3d571132c12a5b8f1f7152ef341a0290", hashOfSeededFile(500));
        assertEquals("604fb7063f04b5eb8361a6081742bdc4", hashOfSeededFile(1024));
        assertEquals("fa08ec8f9e830abe805426d7d2e1957d", hashOfSeededFile(1025));
        assertEquals("4fb84f02413f04e6c20373eac582b19d", hashOfSeededFile(4096));
        assertEquals("444d3d61f05aaf0b657467c197eacf6f", hashOfSeededFile(100_000));
        assertEquals("33b0a986e502508d564e190f4883b8e5", hashOfSeededFile(5_000_000));
    }

    @Test
    public void filenameHashIsPlainMd5OfBasename() {
        // Matches CrossPoint's KOReaderDocumentId::calculateFromFilename.
        assertEquals("098f6bcd4621d373cade4e832627b4f6",
                KoReaderDocumentHash.fromFilename("/storage/books/test"));
        assertEquals(KoReaderDocumentHash.md5Hex("book.epub"),
                KoReaderDocumentHash.fromFilename("/a/b/book.epub"));
    }

    @Test
    public void passwordMd5MatchesKosyncExpectation() {
        // kosync's x-auth-key is the lowercase hex MD5 of the password.
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99",
                KoReaderDocumentHash.md5Hex("password"));
    }

    /**
     * Recreates the same deterministic pseudo-random file the reference
     * implementation was run against: CPython's random.seed(size) followed by
     * getrandbits(8) per byte... which we can't reproduce in Java. Instead the
     * fixture bytes are regenerated with Java's own PRNG — so the file content
     * must be embedded deterministically. See generator note below.
     */
    private static String hashOfSeededFile(int size) throws Exception {
        File file = File.createTempFile("kohash", ".bin");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(pythonSeededBytes(size));
        }
        return KoReaderDocumentHash.fromContent(file);
    }

    /**
     * Byte-for-byte reproduction of CPython's
     *   random.seed(n); bytes(random.getrandbits(8) for _ in range(n))
     * via the Mersenne Twister. Kept minimal: MT19937 with CPython's
     * init_by_array seeding for small integer seeds.
     */
    private static byte[] pythonSeededBytes(int size) {
        int[] mt = new int[624];
        // CPython random.seed(int) -> init_by_array with the key split into
        // 32-bit words (a single word for our small seeds).
        mt[0] = 19650218;
        for (int i = 1; i < 624; i++) {
            mt[i] = (int) (1812433253L * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i);
        }
        int i = 1, j = 0;
        int[] key = {size};
        for (int k = 624; k > 0; k--) {
            mt[i] = (int) ((mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525L)) + key[j] + j);
            i++;
            j++;
            if (i >= 624) {
                mt[0] = mt[623];
                i = 1;
            }
            if (j >= key.length) j = 0;
        }
        for (int k = 623; k > 0; k--) {
            mt[i] = (int) ((mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941L)) - i);
            i++;
            if (i >= 624) {
                mt[0] = mt[623];
                i = 1;
            }
        }
        mt[0] = 0x80000000;

        byte[] result = new byte[size];
        int index = 624;
        for (int n = 0; n < size; n++) {
            if (index >= 624) {
                for (int k = 0; k < 624; k++) {
                    int y = (mt[k] & 0x80000000) | (mt[(k + 1) % 624] & 0x7fffffff);
                    int next = mt[(k + 397) % 624] ^ (y >>> 1);
                    if ((y & 1) != 0) next ^= 0x9908b0df;
                    mt[k] = next;
                }
                index = 0;
            }
            int y = mt[index++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            // getrandbits(8) takes the TOP 8 bits of the 32-bit output
            result[n] = (byte) (y >>> 24);
        }
        return result;
    }
}
