package org.koreader.backgroundonyxsynckoreader.kosync;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/**
 * KOReader document identity hashes, byte-compatible with KOReader's
 * util.partialMD5() and with CrossPoint's KOReaderDocumentId.
 *
 * Binary mode reads 1024 bytes at offset 0, then at 1024 << (2*i) for
 * i = 0..10 (1KB, 4KB, 16KB, ... 1GB), skipping offsets past EOF, and
 * MD5s the concatenation. Note: KOReader's lshift(1024, -2) for i = -1
 * evaluates to 0 in LuaJIT, NOT 256 — the first chunk is the file head.
 * A mismatch here silently breaks book matching across devices, so do
 * not "fix" this to 256.
 *
 * Filename mode is MD5 of the bare filename, matching CrossPoint's
 * filename match method.
 */
public final class KoReaderDocumentHash {

    private static final int CHUNK_SIZE = 1024;

    private KoReaderDocumentHash() {
    }

    /** Partial-MD5 content hash. Returns null if the file cannot be read. */
    public static String fromContent(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[CHUNK_SIZE];
            long fileSize = raf.length();
            for (int i = -1; i <= 10; i++) {
                long offset = i < 0 ? 0 : ((long) CHUNK_SIZE) << (2 * i);
                if (offset >= fileSize) {
                    break;
                }
                raf.seek(offset);
                int read = raf.read(buffer, 0, (int) Math.min(CHUNK_SIZE, fileSize - offset));
                if (read > 0) {
                    md5.update(buffer, 0, read);
                }
            }
            return toHex(md5.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** MD5 of the bare filename (everything after the last '/'). */
    public static String fromFilename(String path) {
        int slash = path.lastIndexOf('/');
        String filename = slash >= 0 ? path.substring(slash + 1) : path;
        if (filename.isEmpty()) {
            return null;
        }
        return md5Hex(filename);
    }

    /** Lowercase hex MD5 of a UTF-8 string (also used for the kosync password). */
    public static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return toHex(md5.digest(input.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new AssertionError(e); // MD5 and UTF-8 always exist
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
