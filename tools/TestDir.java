import com.nmsave.editor.*;

import java.io.File;

/** 桌面自测：目录级 scanDir / processDir 是否正确。 */
public class TestDir {
    public static void main(String[] a) throws Exception {
        File world = new File(a[0]);
        File out = new File(a[1]);
        File work = new File(a[2]);

        Editor.Stats s1 = Editor.scanDir(world, work, null);
        System.out.println("输入目录扫描: name=" + s1.worldName + " keys=" + s1.keptCount
                + " entities=" + s1.entityCount + " digp=" + s1.digestCount
                + " encrypted=" + s1.encrypted + " pos=" + (s1.playerPos == null ? "null" : s1.playerPos[0] + "," + s1.playerPos[1] + "," + s1.playerPos[2])
                + " mainhand=" + s1.mainhand);

        Editor.Options o = new Editor.Options();
        o.purgeEntities = true;
        o.clearDigests = true;
        Editor.Stats s2 = Editor.processDir(world, out, o, work, null);
        System.out.println("输出: purged=" + s2.purged + " keys=" + s2.keptCount);

        Editor.Stats s3 = Editor.scanDir(out, new File(work, "re"), null);
        System.out.println("回读: name=" + s3.worldName + " keys=" + s3.keptCount
                + " entities=" + s3.entityCount + " digp=" + s3.digestCount
                + " level.dat存在=" + new File(out, "level.dat").isFile()
                + " 其它文件=" + out.list().length);
        if (s3.entityCount != 0 || s3.digestCount != 0) throw new RuntimeException("清理不干净");
        if (a.length > 3) {
            File writeBack = new File("build/wbcopy");
            Editor.copyDir(out, writeBack, null);
            System.out.println("copyDir ok: " + writeBack.list().length + " 项");
        }
        System.out.println("OK");
    }
}
