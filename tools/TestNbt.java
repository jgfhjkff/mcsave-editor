import com.nmsave.editor.*;
import java.io.File;
import java.util.List;

public class TestNbt {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        System.out.println("encrypted=" + BedrockDb.isEncrypted(cur));
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        int n = 0;
        for (BedrockDb.Entry e : all) {
            String k = new String(e.key, "ISO-8859-1");
            if (!k.startsWith("actorprefix")) continue;
            if (e.value == null) continue;
            String head = "";
            for (int i = 0; i < Math.min(24, e.value.length); i++) head += String.format("%02x ", e.value[i]);
            System.out.println("key=" + k + " len=" + e.value.length + " head=" + head);
            try {
                Nbt.Tag root = Nbt.parse(e.value, 0);
                Nbt.Tag id = root.asCompound().get("identifier");
                System.out.println("  identifier=" + (id == null ? "null" : id.value));
            } catch (Throwable t) {
                System.out.println("  ERR: " + t);
            }
            if (++n >= 3) break;
        }
    }
}
