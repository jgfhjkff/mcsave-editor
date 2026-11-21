import com.nmsave.editor.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class Verify {
    public static void main(String[] a) throws Exception {
        File zip = new File(a[0]);
        File dir = new File("build/verify-ex");
        if (dir.exists()) for (File f : dir.listFiles()) f.delete();
        dir.mkdirs();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        Map<String, Integer> sizes = new TreeMap<>();
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            File f = new File(dir, e.getName());
            f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            byte[] b = new byte[8192]; int n;
            while ((n = zin.read(b)) > 0) fos.write(b, 0, n);
            fos.close();
            sizes.put(e.getName(), (int) f.length());
        }
        zin.close();
        System.out.println("=== zip 内容 " + sizes.size() + " 条目 ===");
        for (Map.Entry<String, Integer> en : sizes.entrySet()) System.out.println("  " + en.getKey() + "  " + en.getValue() + "B");

        File world = new File(dir, "EdKLTbzEXh8=");
        File db = new File(world, "db");
        byte[] cur = BedrockDb.readFile(new File(db, "CURRENT"));
        System.out.println("CURRENT 加密=" + BedrockDb.isEncrypted(cur) + " len=" + cur.length);
        String manifest = null;
        for (File f : db.listFiles()) if (f.getName().startsWith("MANIFEST")) manifest = f.getName();
        byte[] key = BedrockDb.isEncrypted(cur) ? BedrockDb.deriveKey(cur, manifest) : null;
        byte[] curPlain = BedrockDb.decrypt(cur, key);
        System.out.println("CURRENT 明文=" + new String(curPlain, "UTF-8").trim() + " (推导 key=" + new String(key, "ISO-8859-1") + ")");

        List<BedrockDb.Entry> all = BedrockDb.readAll(db, key, null);
        int actor = 0, digp = 0;
        for (BedrockDb.Entry en : all) {
            String k = new String(en.key, "ISO-8859-1");
            if (k.startsWith("actorprefix")) actor++;
            if (k.startsWith("digp")) digp++;
        }
        System.out.println("=== 回读 " + all.size() + " 键，actorprefix=" + actor + " digp=" + digp + " ===");

        byte[] levelDat = BedrockDb.readFile(new File(world, "level.dat"));
        System.out.println("level.dat len=" + levelDat.length);
        Nbt.Tag root = Nbt.parse(levelDat, 8);
        System.out.println("level.dat 根键数=" + root.asCompound().size() + " 键列表=" + root.asCompound().keySet());
    }
}
