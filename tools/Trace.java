import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class Trace {
    byte[] d;
    int pos;
    List<String> path = new ArrayList<>();
    List<String> log = new ArrayList<>();

    Trace(byte[] d, int pos) { this.d = d; this.pos = pos; }

    int u8() { return d[pos++] & 0xFF; }
    int u16() { int v = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8); pos += 2; return v; }
    int i32() { int v = (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8) | ((d[pos + 2] & 0xFF) << 16) | ((d[pos + 3] & 0xFF) << 24); pos += 4; return v; }
    long i64() { long lo = i32() & 0xFFFFFFFFL; long hi = i32() & 0xFFFFFFFFL; return lo | (hi << 32); }
    String str() { int n = u16(); String s = new String(d, pos, n, java.nio.charset.StandardCharsets.UTF_8); pos += n; return s; }

    void note(int type, String name, int depth) {
        String line = String.format("t%-2d depth=%d pos=%d %s", type, depth, pos, name);
        log.add(line);
        if (log.size() > 60) log.remove(0);
    }

    void compound(int depth) {
        while (true) {
            int off = pos;
            int type = u8();
            if (type == 0) { note(0, "END", depth); return; }
            if (type > 12) { throw new IllegalStateException("bad type " + type + " @" + off); }
            String name = str();
            note(type, name, depth);
            payload(type, depth);
        }
    }

    void payload(int type, int depth) {
        switch (type) {
            case 1: pos += 1; return;
            case 2: pos += 2; return;
            case 3: case 5: pos += 4; return;
            case 4: case 6: pos += 8; return;
            case 7: { int n = i32(); pos += n; return; }
            case 8: { int n = u16(); pos += n; return; }
            case 9: {
                int et = u8();
                int n = i32();
                note(et, "LIST<" + n + ">", depth + 1);
                for (int i = 0; i < n; i++) payload(et, depth + 1);
                return;
            }
            case 10: compound(depth + 1); return;
            case 11: { int n = i32(); pos += 4 * n; return; }
            case 12: { int n = i32(); pos += 8 * n; return; }
            default: throw new IllegalStateException("bad payload type " + type + " @" + pos);
        }
    }

    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            if (!new String(e.key, "ISO-8859-1").equals("~local_player")) continue;
            Trace t = new Trace(e.value, 0);
            try {
                int rootType = t.u8();
                String rn = t.str();
                System.out.println("root type=" + rootType + " name='" + rn + "'");
                t.compound(0);
                System.out.println("TRACE OK, end pos=" + t.pos + " of " + e.value.length);
            } catch (Throwable ex) {
                System.out.println("TRACE FAIL: " + ex.getMessage());
                System.out.println("last events:");
                for (String s : t.log) System.out.println("   " + s);
            }
            break;
        }
    }
}
