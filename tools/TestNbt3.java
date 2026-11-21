import com.nmsave.editor.*;
import java.io.File;
import java.util.List;

public class TestNbt3 {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, "ISO-8859-1");
            if (!k.startsWith("actorprefix") || e.value == null) continue;
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 10; i++) h.append(String.format("%02x ", e.value[i]));
            System.out.println("head=" + h + "len=" + e.value.length);
            int pos = 1;
            int nlen = (e.value[pos] & 0xFF) | ((e.value[pos + 1] & 0xFF) << 8);
            System.out.println("rootNameLen=" + nlen);
            pos += 2 + nlen;
            int t = e.value[pos] & 0xFF;
            System.out.println("firstChildType=" + t + " at pos=" + pos);
            Nbt.Tag root = Nbt.parse(e.value, 0);
            System.out.println("parsed size=" + root.asCompound().size());
            break;
        }
    }
}
