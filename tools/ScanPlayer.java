import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class ScanPlayer {
    static byte[] d;
    static int pos;

    static int u8() { return d[pos++] & 0xFF; }
    static int u16() { int v = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8); pos += 2; return v; }
    static int i32() { int v = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8) | ((d[pos + 2] & 0xFF) << 16) | ((d[pos + 3] & 0xFF) << 24); pos += 4; return v; }

    static void skip(int type) {
        switch (type) {
            case 1: pos += 1; return;
            case 2: pos += 2; return;
            case 3: case 5: pos += 4; return;
            case 4: case 6: pos += 8; return;
            case 7: { int n = i32(); if (n < 0 || pos + n > d.length) throw new IllegalStateException("bytearray n=" + n); pos += n; return; }
            case 8: { int n = u16(); if (pos + n > d.length) throw new IllegalStateException("string n=" + n + " @" + pos); pos += n; return; }
            case 9: {
                int et = u8(); int n = i32();
                for (int i = 0; i < n; i++) skip(et);
                return;
            }
            case 10: compound(); return;
            case 11: { int n = i32(); pos += 4 * n; return; }
            case 12: { int n = i32(); pos += 8 * n; return; }
            default: throw new IllegalStateException("bad type " + type + " @" + (pos - 1));
        }
    }

    static int depth = 0;

    static void compound() {
        while (true) {
            int type = u8();
            if (type == 0) return;
            int nlen = u16();
            String name = new String(d, pos, nlen, java.nio.charset.StandardCharsets.UTF_8);
            pos += nlen;
            int bodyStart = pos;
            boolean show = nlen > 0 && depth == 0;
            if (show) System.out.printf("t%-2d %-30s payload@%d", type, name, bodyStart);
            depth++;
            skip(type);
            depth--;
            if (show) System.out.printf("  end@%d len=%d%n", pos, pos - bodyStart);
        }
    }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            if (!new String(e.key, "ISO-8859-1").equals("~local_player")) continue;
            d = e.value; pos = 0;
            System.out.println("value len=" + d.length);
            u8(); u16(); // root type + name
            try {
                compound();
                System.out.println("SCAN OK end=" + pos);
            } catch (Throwable t) {
                System.out.println("SCAN STOP: " + t.getMessage() + " at pos " + pos);
            }
            break;
        }
    }
}
