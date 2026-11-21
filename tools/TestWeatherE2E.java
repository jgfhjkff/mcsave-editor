import com.nmsave.editor.*;
import java.io.File;

public class TestWeatherE2E {
    public static void main(String[] a) throws Exception {
        File world = new File(a[0]);
        File work = new File("/tmp/e2e_work");
        rm(work);
        work.mkdirs();

        Editor.Options opt = new Editor.Options();
        opt.purgeEntities = false;
        opt.weather = 3;            // 雷雨
        opt.weatherMinutes = 30;
        opt.time = 18000L;
        opt.seed = -4172420867104890325L;
        opt.spawnX = 128; opt.spawnY = 70; opt.spawnZ = -35;
        opt.rules.put("showcoordinates", 1);
        opt.rules.put("domobspawning", 0);

        File out = new File("/tmp/e2e_out_world");
        rm(out);
        out.mkdirs();
        Editor.Stats st = Editor.processDir(world, out, opt, work, null);
        System.out.println("处理完成，库键数=" + st.keptCount);

        File lv = new File(out, "level.dat");
        LevelDat.Info info = LevelDat.read(lv);
        Nbt.Compound c = Nbt.parse(BedrockDb.readFile(lv), 8).asCompound();

        check("Time=18000", info.time == 18000L, info.time);
        check("Seed", info.seed == -4172420867104890325L, info.seed);
        check("Spawn 128/70/-35", info.spawnX == 128 && info.spawnY == 70 && info.spawnZ == -35,
                info.spawnX + "," + info.spawnY + "," + info.spawnZ);
        check("rainLevel=1", num(c, "rainLevel") == 1.0, num(c, "rainLevel"));
        check("rainTime=30*1200=36000", num(c, "rainTime") == 36000.0, num(c, "rainTime"));
        check("lightningLevel=1", num(c, "lightningLevel") == 1.0, num(c, "lightningLevel"));
        check("lightningTime=36000", num(c, "lightningTime") == 36000.0, num(c, "lightningTime"));
        check("showcoordinates=1", num(c, "showcoordinates") == 1.0, num(c, "showcoordinates"));
        check("domobspawning=0", num(c, "domobspawning") == 0.0, num(c, "domobspawning"));
        check("levelname 仍在", !info.levelName.isEmpty(), info.levelName);
        check("db 文件存在", new File(out, "db").isDirectory() && new File(out, "db").listFiles().length > 0,
                new File(out, "db").listFiles().length);
        check("world_icon 搬过去", new File(out, "world_icon.jpeg").isFile(), "-");
        check("其它文件保留", new File(out, "levelname.txt").isFile(), "-");
    }

    static void rm(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) rm(x);
        f.delete();
    }

    static double num(Nbt.Compound c, String k) {
        Nbt.Tag t = c.get(k);
        return t != null && t.value instanceof Number ? ((Number) t.value).doubleValue() : -999;
    }

    static int pass = 0, fail = 0;
    static void check(String m, boolean cond, Object actual) {
        if (cond) { pass++; System.out.println("PASS " + m); }
        else { fail++; System.out.println("FAIL " + m + " → " + actual); }
    }
}
