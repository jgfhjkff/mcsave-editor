import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class TestPlayer2 {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            if (!new String(e.key, "ISO-8859-1").equals("~local_player")) continue;
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 40; i++) h.append(String.format("%02x ", e.value[i]));
            System.out.println("len=" + e.value.length + " head=" + h);
            for (int off : new int[]{0, 4, 8}) {
                try {
                    Nbt.Tag r = Nbt.parse(e.value, off);
                    System.out.println("parse@" + off + " ok, keys=" + (r.asCompound() == null ? -1 : r.asCompound().size()));
                } catch (Throwable t) {
                    System.out.println("parse@" + off + " FAIL: " + t);
                }
            }
            // 手动扫描根级子标签
            int pos = 3;
            for (int i = 0; i < 40 && pos < e.value.length; i++) {
                int type = e.value[pos] & 0xFF;
                pos++;
                if (type == 0) { System.out.println("  root END at " + (pos - 1)); break; }
                int nlen = (e.value[pos] & 0xFF) | ((e.value[pos + 1] & 0xFF) << 8);
                pos += 2;
                String nm = new String(e.value, pos, Math.min(nlen, 32), java.nio.charset.StandardCharsets.UTF_8);
                pos += nlen;
                System.out.println("  child t" + type + " name=" + nm + " nameLen=" + nlen + " @namepos " + (pos - nlen - 2));
                // 跳过 payload：只处理简单类型，其余直接退出扫描
                if (type == 1) pos += 1;
                else if (type == 2) pos += 2;
                else if (type == 3) pos += 4;
                else if (type == 4) pos += 8;
                else if (type == 5) pos += 4;
                else if (type == 6) pos += 8;
                else if (type == 8) { int sl = (e.value[pos] & 0xFF) | ((e.value[pos + 1] & 0xFF) << 8); pos += 2 + sl; }
                else { System.out.println("  (stop at complex type " + type + ")"); break; }
            }
            break;
        }
    }
}
