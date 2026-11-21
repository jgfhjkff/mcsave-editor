import com.nmsave.editor.*;
import java.io.File;
import java.util.*;

public class Dump {
    public static void main(String[] a) throws Exception {
        File db = new File(a[0]);
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, "MANIFEST-000338") : null;
        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        for (BedrockDb.Entry e : all) {
            if (!new String(e.key, "ISO-8859-1").equals("~local_player")) continue;
            int from = Integer.parseInt(a[1]), to = Integer.parseInt(a[2]);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < Math.min(to, e.value.length); i++) sb.append(String.format("%02x", e.value[i]));
            System.out.println(sb);
            break;
        }
    }
}
