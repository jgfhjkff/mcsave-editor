package com.nmsave.editor;
import com.nmsave.editor.*;

import java.io.File;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 验证：只改“一天中的时刻”时，已过的天数（Time/24000）必须保留。 */
public class TestTimeDay {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File work = new File("/tmp/ttd-work");
        Editor.deleteRecursivePublic(work);
        work.mkdirs();
        File inZip = Editor.zipWorld(dir, new File(work, "in.zip"));

        LevelDat.Info before = LevelDat.read(new File(dir, "level.dat"));
        System.out.println("原 Time = " + before.time + "，第 " + (before.time / 24000 + 1) + " 天");

        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.clearDigests = false;
        opt.timeOfDay = 6000; // 正午

        File outZip = new File(work, "out.zip");
        Editor.process(inZip, outZip, opt, new File(work, "w2"), null);

        // 解出 level.dat 再读
        File outDir = new File(work, "out");
        unzip(outZip, outDir);
        LevelDat.Info after = LevelDat.read(find(outDir, "level.dat"));
        System.out.println("新 Time = " + after.time + "，第 " + (after.time / 24000 + 1) + " 天");

        boolean dayOk = after.time / 24000 == before.time / 24000;
        boolean todOk = after.time % 24000 == 6000;
        System.out.println("天数保留: " + (dayOk ? "通过" : "失败"));
        System.out.println("时刻=6000: " + (todOk ? "通过" : "失败"));
        if (!(dayOk && todOk)) System.exit(1);
    }

    static File find(File dir, String name) {
        File[] fs = dir.listFiles();
        if (fs == null) return new File(dir, name);
        for (File f : fs) {
            if (f.isFile() && f.getName().equals(name)) return f;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = find(f, name);
                if (r.isFile()) return r;
            }
        }
        return new File(dir, name);
    }

    static void unzip(File zip, File dir) throws Exception {
        dir.mkdirs();
        ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zip));
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            if (e.isDirectory()) {
                new File(dir, e.getName()).mkdirs();
                continue;
            }
            File f = new File(dir, e.getName());
            f.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
        }
        zis.close();
    }
}
