package com.nmsave.editor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * 网易/基岩版 LevelDB 读写内核（纯 Java，无 JNI）。
 *
 * 读：SSTable（footer -> index -> data blocks，块可能 raw-deflate 压缩）+ WAL（LevelDB 日志格式）。
 * 写：只写 MANIFEST + CURRENT + WAL，不生成 SSTable —— 完全绕开 Mojang/Mojang 系压缩类型差异。
 */
public class BedrockDb {

    public static final byte[] MAGIC = {(byte) 0x80, 0x1D, 0x30, 0x01};
    private static final int BLOCK_SIZE = 32768;

    /** 批处理编码：读源库时探测，写回时沿用（varint = 网易分支，4 字节 = 标准 LevelDB）。 */
    private static boolean detectedVarint = true;
    private static int cntVarintBatches, cntFixedBatches;

    // ------------------------------------------------------------------ CRC32C
    private static int[] crcTable;

    private static int[] crcTable() {
        if (crcTable == null) {
            int[] t = new int[256];
            for (int i = 0; i < 256; i++) {
                int c = i;
                for (int k = 0; k < 8; k++) {
                    c = ((c & 1) != 0) ? ((c >>> 1) ^ 0x82f63b78) : (c >>> 1);
                }
                t[i] = c;
            }
            crcTable = t;
        }
        return crcTable;
    }

    public static int crc32c(byte[] data) {
        return crc32c(data, 0, data.length);
    }

    public static int crc32c(byte[] data, int off, int len) {
        int[] t = crcTable();
        int c = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            c = t[(c ^ data[off + i]) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

    private static int maskCrc(int crc) {
        return ((crc >>> 15) | (crc << 17)) + 0xa282ead8;
    }

    // ------------------------------------------------------------------ 加解密
    public static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < 4) return false;
        for (int i = 0; i < 4; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    private static byte[] xor(byte[] body, byte[] key) {
        byte[] out = new byte[body.length];
        for (int i = 0; i < body.length; i++) out[i] = (byte) (body[i] ^ key[i % key.length]);
        return out;
    }

    /** 由 CURRENT 密文 + MANIFEST 文件名反推密钥（与原存档一致的推导方式）。 */
    public static byte[] deriveKey(byte[] currentFile, String manifestName) {
        byte[] plain = (manifestName + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[currentFile.length - 4];
        System.arraycopy(currentFile, 4, body, 0, body.length);
        byte[] k = new byte[body.length];
        for (int i = 0; i < body.length; i++) k[i] = (byte) (body[i] ^ plain[i % plain.length]);
        // 16 字节且前后半相同 -> 取 8 字节
        if (k.length == 16) {
            boolean same = true;
            for (int i = 0; i < 8; i++) if (k[i] != k[i + 8]) { same = false; break; }
            if (same) {
                byte[] s = new byte[8];
                System.arraycopy(k, 0, s, 0, 8);
                return s;
            }
        }
        return k;
    }

    public static byte[] encrypt(byte[] plain, byte[] key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 4);
        for (byte b : MAGIC) out.write(b);
        byte[] enc = xor(plain, key);
        for (byte b : enc) out.write(b);
        return out.toByteArray();
    }

    public static byte[] decrypt(byte[] enc, byte[] key) {
        if (!isEncrypted(enc)) return enc;
        byte[] body = new byte[enc.length - 4];
        System.arraycopy(enc, 4, body, 0, body.length);
        return xor(body, key);
    }

    // ------------------------------------------------------------------ 键值条目
    public static class Entry {
        public byte[] key;
        public byte[] value;   // null = 已删除
        public long seq;

        public Entry(byte[] k, byte[] v, long s) { key = k; value = v; seq = s; }
    }

    /** 读取一个数据库目录里的全部键值（表 + 日志合并，按最高序号取值）。 */
    public static List<Entry> readAll(File dbDir, byte[] key, List<String> logSink) throws Exception {
        cntVarintBatches = 0;
        cntFixedBatches = 0;
        Map<ByteKey, Entry> latest = new HashMap<>();
        File[] files = dbDir.listFiles();
        if (files == null) throw new IOException("db 目录为空");

        for (File f : files) {
            String name = f.getName();
            if (!(name.endsWith(".ldb") || name.endsWith(".sst")) || f.length() == 0) continue;
            byte[] raw = readFile(f);
            byte[] plain = (key != null) ? decrypt(raw, key) : raw;
            int n = readTable(plain, latest);
            if (logSink != null) logSink.add("表 " + name + " -> " + n + " 条");
        }
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".log") || f.length() == 0) continue;
            byte[] raw = readFile(f);
            byte[] plain = (key != null) ? decrypt(raw, key) : raw;
            int n = readLog(plain, latest);
            if (logSink != null) logSink.add("日志 " + name + " -> " + n + " 条");
        }
        detectedVarint = cntVarintBatches >= cntFixedBatches;
        if (logSink != null) logSink.add("批处理编码: " + (detectedVarint ? "varint" : "4字节")
                + "（varint " + cntVarintBatches + " 批 / 4字节 " + cntFixedBatches + " 批）");
        return new ArrayList<>(latest.values());
    }

    private static void putLatest(Map<ByteKey, Entry> latest, ByteKey key, byte[] value, long seq) {
        Entry e = latest.get(key);
        if (e == null || seq > e.seq || (seq == e.seq && e.value == null && value != null)) {
            latest.put(key, new Entry(key.bytes, value, seq));
        }
    }

    private static void putLatest(Map<ByteKey, Entry> latest, byte[] key, byte[] value, long seq) {
        putLatest(latest, new ByteKey(key), value, seq);
    }

    /** 以字节内容作为键的包装类（避免 hash 碰撞丢数据）。 */
    public static final class ByteKey {
        public final byte[] bytes;
        private final int h;
        public ByteKey(byte[] b) {
            bytes = b;
            int x = 1;
            for (byte v : b) x = x * 31 + v;
            h = x;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ByteKey)) return false;
            ByteKey k = (ByteKey) o;
            if (k.bytes.length != bytes.length) return false;
            for (int i = 0; i < bytes.length; i++) if (bytes[i] != k.bytes[i]) return false;
            return true;
        }
        @Override public int hashCode() { return h; }
    }

    // ------------------------------------------------------------------ SSTable
    private static int readTable(byte[] d, Map<ByteKey, Entry> out) throws Exception {
        if (d.length < 48) return 0;
        byte[] footer = new byte[48];
        System.arraycopy(d, d.length - 48, footer, 0, 48);
        long magic = le64(footer, 40);
        if (magic != 0xdb4775248b80fb57L) return 0; // 不是标准表

        int[] p = {0};
        long metaOff = varint64(footer, p);
        long metaSize = varint64(footer, p);
        long idxOff = varint64(footer, p);
        long idxSize = varint64(footer, p);

        byte[] indexBlock = readBlock(d, (int) idxOff, (int) idxSize);
        if (indexBlock == null) return 0;

        int count = 0;
        for (Object[] kv : parseBlock(indexBlock)) {
            byte[] val = (byte[]) kv[1];
            int[] q = {0};
            long bOff = varint(val, q);
            long bSize = varint(val, q);
            byte[] block = readBlock(d, (int) bOff, (int) bSize);
            if (block == null) continue;
            for (Object[] e : parseBlock(block)) {
                byte[] ikey = (byte[]) e[0];
                byte[] value = (byte[]) e[1];
                if (ikey.length < 8) continue;
                byte[] ukey = new byte[ikey.length - 8];
                System.arraycopy(ikey, 0, ukey, 0, ukey.length);
                long trailer = le64(ikey, ikey.length - 8);
                long seq = trailer >>> 8;
                int type = (int) (trailer & 0xFF);
                putLatest(out, ukey, type == 0 ? null : value, seq);
                count++;
            }
        }
        return count;
    }

    /** 读一个块：先尝试 raw-deflate 解压，失败则按未压缩处理。 */
    private static byte[] readBlock(byte[] d, int off, int size) {
        if (off < 0 || size <= 0 || off + size > d.length) return null;
        byte[] block = new byte[size];
        System.arraycopy(d, off, block, 0, size);
        byte[] inflated = inflate(block, true);
        if (inflated != null) return inflated;
        inflated = inflate(block, false);
        if (inflated != null) return inflated;
        return block;
    }

    private static byte[] inflate(byte[] data, boolean raw) {
        Inflater inf = new Inflater(raw);
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2 + 64);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
            if (out.size() == 0) return null;
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** 解析块内条目（前缀压缩 + 尾部 restart 数组）。 */
    private static List<Object[]> parseBlock(byte[] body) {
        List<Object[]> result = new ArrayList<>();
        if (body.length < 4) return result;
        int numRestarts = le32(body, body.length - 4);
        int end = body.length - 4 - 4 * numRestarts;
        if (end <= 0 || end > body.length) end = body.length - 4;
        int[] p = {0};
        byte[] key = new byte[0];
        while (p[0] < end) {
            int shared = varint(body, p);
            int nonShared = varint(body, p);
            int vlen = varint(body, p);
            if (p[0] + nonShared + vlen > body.length) break;
            byte[] k = new byte[Math.min(shared, key.length) + nonShared];
            if (shared > 0 && shared <= key.length) System.arraycopy(key, 0, k, 0, shared);
            System.arraycopy(body, p[0], k, Math.min(shared, key.length), nonShared);
            p[0] += nonShared;
            byte[] v = new byte[vlen];
            System.arraycopy(body, p[0], v, 0, vlen);
            p[0] += vlen;
            key = k;
            result.add(new Object[]{k, v});
        }
        return result;
    }

    // ------------------------------------------------------------------ WAL 日志
    private static int readLog(byte[] d, Map<ByteKey, Entry> out) throws Exception {
        int count = 0;
        int off = 0;
        ByteArrayOutputStream pending = null;
        while (off + 7 <= d.length) {
            int crc = le32(d, off);
            int len = (d[off + 4] & 0xFF) | ((d[off + 5] & 0xFF) << 8);
            int type = d[off + 6] & 0xFF;
            if (type == 0 && len == 0) { // 块尾填充
                off = ((off / BLOCK_SIZE) + 1) * BLOCK_SIZE;
                pending = null;
                continue;
            }
            if (off + 7 + len > d.length) break;
            byte[] payload = new byte[len];
            System.arraycopy(d, off + 7, payload, 0, len);
            off += 7 + len;

            if (type == 1) {
                count += applyBatch(payload, out);
                pending = null;
            } else if (type == 2) {
                pending = new ByteArrayOutputStream();
                pending.write(payload, 0, payload.length);
            } else if (type == 3) {
                if (pending != null) pending.write(payload, 0, payload.length);
            } else if (type == 4) {
                if (pending != null) {
                    pending.write(payload, 0, payload.length);
                    count += applyBatch(pending.toByteArray(), out);
                    pending = null;
                }
            }
        }
        return count;
    }

    /** 解析一个 WriteBatch。网易分支用 varint 编码长度，标准 LevelDB（国际版）用 4 字节长度，自动判别。 */
    private static int applyBatch(byte[] batch, Map<ByteKey, Entry> out) {
        int nFixed = parseBatch(batch, true, out, false);
        int nVar = parseBatch(batch, false, out, false);
        if (nFixed < 0 && nVar < 0) return 0;
        if (nFixed >= 0 && (nVar < 0 || nFixed >= nVar)) {
            cntFixedBatches++;
            return parseBatch(batch, true, out, true);
        }
        cntVarintBatches++;
        return parseBatch(batch, false, out, true);
    }

    /** commit=false 只试解析（不写 out），合法返回条数，非法返回 -1。 */
    private static int parseBatch(byte[] batch, boolean fixedLen, Map<ByteKey, Entry> out, boolean commit) {
        if (batch.length < 12) return -1;
        long seq = le64(batch, 0);
        int count = le32(batch, 8);
        if (count <= 0 || count > 1000000) return -1;
        int[] p = {12};
        int applied = 0;
        for (int i = 0; i < count; i++) {
            if (p[0] >= batch.length) return -1;
            int tag = batch[p[0]++] & 0xFF;
            if (tag != 0 && tag != 1) return -1;
            int klen;
            if (fixedLen) {
                if (p[0] + 4 > batch.length) return -1;
                klen = (int) le32(batch, p[0]);
                p[0] += 4;
            } else {
                klen = varint(batch, p);
            }
            if (klen < 0 || p[0] + klen > batch.length) return -1;
            byte[] k = new byte[klen];
            System.arraycopy(batch, p[0], k, 0, klen);
            p[0] += klen;
            byte[] v = null;
            if (tag != 0) {
                int vlen;
                if (fixedLen) {
                    if (p[0] + 4 > batch.length) return -1;
                    vlen = (int) le32(batch, p[0]);
                    p[0] += 4;
                } else {
                    vlen = varint(batch, p);
                }
                if (vlen < 0 || p[0] + vlen > batch.length) return -1;
                v = new byte[vlen];
                System.arraycopy(batch, p[0], v, 0, vlen);
                p[0] += vlen;
            }
            if (commit) putLatest(out, k, v, seq + i);
            applied++;
        }
        return applied;
    }

    // ------------------------------------------------------------------ 写出数据库
    /**
     * 写一个"只有 WAL 的数据库"：MANIFEST + CURRENT + 000003.log。
     * 不生成 SSTable，避免块压缩格式差异。
     */
    public static void writeDb(File dir, List<Entry> entries, byte[] encKey) throws Exception {
        writeDb(dir, entries, encKey, detectedVarint);
    }

    public static void writeDb(File dir, List<Entry> entries, byte[] encKey, boolean varintLen) throws Exception {
        dir.mkdirs();
        long lastSeq = 0;
        for (Entry e : entries) if (e.seq > lastSeq) lastSeq = e.seq;
        if (lastSeq <= 0) lastSeq = entries.size();

        // 1) 把条目拼成若干 WriteBatch（每批 <= 1MB），序号连续递增
        List<byte[]> batches = new ArrayList<>();
        List<Entry> chunk = new ArrayList<>();
        int size = 0;
        long seq = 1;
        for (Entry e : entries) {
            int add = 12 + e.key.length + (e.value == null ? 0 : e.value.length);
            if (size + add > 1024 * 1024 && !chunk.isEmpty()) {
                batches.add(buildBatch(seq, chunk, varintLen));
                seq += chunk.size();
                chunk.clear();
                size = 0;
            }
            chunk.add(e);
            size += add;
        }
        if (!chunk.isEmpty()) {
            batches.add(buildBatch(seq, chunk, varintLen));
            seq += chunk.size();
        }

        // 2) WAL
        byte[] wal = buildLog(batches);
        writeFile(new File(dir, "000003.log"), wal);

        // 3) MANIFEST：一条 VersionEdit
        long logNumber = 3, nextFile = 4;
        long maxSeq = seq - 1;
        byte[] edit = buildVersionEdit(logNumber, nextFile, maxSeq);
        byte[] manifest = buildLog(java.util.Collections.singletonList(edit));
        writeFile(new File(dir, "MANIFEST-000002"), manifest);

        // 4) CURRENT
        byte[] current = "MANIFEST-000002\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeFile(new File(dir, "CURRENT"), current);

        // 5) 加密（与网易一致：头 4 字节 + XOR）
        if (encKey != null) {
            encryptFile(new File(dir, "000003.log"), encKey);
            encryptFile(new File(dir, "MANIFEST-000002"), encKey);
            encryptFile(new File(dir, "CURRENT"), encKey);
        }
    }

    private static void encryptFile(File f, byte[] key) throws Exception {
        byte[] plain = readFile(f);
        writeFile(f, encrypt(plain, key));
    }

    /**
     * 网易分支的 WriteBatch 用 varint 写 key/value 长度；标准 LevelDB（国际版）用 4 字节小端。
     * varintLen=true 走网易写法，false 走标准写法。
     */
    private static byte[] buildBatch(long seq, List<Entry> items, boolean varintLen) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putLong(out, seq);
        putInt(out, items.size());
        for (Entry e : items) {
            if (e.value == null) {
                out.write(0);
                writeLen(out, e.key.length, varintLen);
                for (byte b : e.key) out.write(b);
            } else {
                out.write(1);
                writeLen(out, e.key.length, varintLen);
                for (byte b : e.key) out.write(b);
                writeLen(out, e.value.length, varintLen);
                for (byte b : e.value) out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static void writeLen(ByteArrayOutputStream out, int len, boolean varintLen) {
        if (varintLen) {
            writeVarint(out, len);
        } else {
            out.write(len & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 24) & 0xFF);
        }
    }

    private static byte[] buildVersionEdit(long logNumber, long nextFile, long lastSeq) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, 1);
        byte[] cmp = "leveldb.BytewiseComparator".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(out, cmp.length);
        for (byte b : cmp) out.write(b);
        writeVarint(out, 2);
        writeVarint(out, logNumber);
        writeVarint(out, 9);       // prev log number（该分支会写，固定 0）
        writeVarint(out, 0);
        writeVarint(out, 3);
        writeVarint(out, nextFile);
        writeVarint(out, 4);
        writeVarint(out, lastSeq);
        return out.toByteArray();
    }

    private static byte[] buildLog(List<byte[]> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int blockOffset = 0;
        for (byte[] data : records) {
            int pos = 0;
            boolean begin = true;
            while (true) {
                int left = BLOCK_SIZE - blockOffset;
                if (left < 8) {
                    for (int i = 0; i < left; i++) out.write(0);
                    blockOffset = 0;
                    left = BLOCK_SIZE;
                }
                int frag = Math.min(left - 7, data.length - pos);
                boolean end = pos + frag >= data.length;
                int type;
                if (begin && end) type = 1;
                else if (begin) type = 2;
                else if (end) type = 4;
                else type = 3;

                byte[] piece = new byte[frag];
                System.arraycopy(data, pos, piece, 0, frag);
                byte[] crcInput = new byte[frag + 1];
                crcInput[0] = (byte) type;
                System.arraycopy(piece, 0, crcInput, 1, frag);
                int crc = maskCrc(crc32c(crcInput));
                out.write(crc & 0xFF);
                out.write((crc >> 8) & 0xFF);
                out.write((crc >> 16) & 0xFF);
                out.write((crc >> 24) & 0xFF);
                out.write(frag & 0xFF);
                out.write((frag >> 8) & 0xFF);
                out.write(type);
                for (byte b : piece) out.write(b);

                blockOffset += 7 + frag;
                pos += frag;
                begin = false;
                if (end) break;
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 工具
    public static byte[] readFile(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int p = 0;
            while (p < buf.length) {
                int n = in.read(buf, p, buf.length - p);
                if (n <= 0) break;
                p += n;
            }
        } finally {
            in.close();
        }
        return buf;
    }

    public static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static long le64(byte[] b, int off) {
        long lo = le32(b, off) & 0xFFFFFFFFL;
        long hi = le32(b, off + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void putInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void putLong(ByteArrayOutputStream out, long v) {
        putInt(out, (int) (v & 0xFFFFFFFFL));
        putInt(out, (int) ((v >> 32) & 0xFFFFFFFFL));
    }

    static int varint(byte[] b, int[] p) {
        int result = 0, shift = 0;
        while (true) {
            int x = b[p[0]++] & 0xFF;
            result |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) break;
        }
        return result;
    }

    private static long varint64(byte[] b, int[] p) {
        long result = 0;
        int shift = 0;
        while (true) {
            int x = b[p[0]++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }


    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) { out.write((int) value); return; }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    static class IOException extends Exception {
        IOException(String m) { super(m); }
    }
}
