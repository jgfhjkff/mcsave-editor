import com.nmsave.editor.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestLevelExtra {
    static int pass = 0, fail = 0;
    static void ok(String m) { pass++; System.out.println("PASS " + m); }
    static void no(String m) { fail++; System.out.println("FAIL " + m); }
    static void eq(Object a, Object b, String m) {
        if (a == null ? b == null : a.equals(b)) ok(m + " = " + a);
        else no(m + " 期望 " + b + " 实际 " + a);
    }

    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File dst = new File("/tmp/level_extra.dat");
        BedrockDb.writeFile(dst, BedrockDb.readFile(src));

        Map<String, Integer> rules = new LinkedHashMap<>();
        rules.put("showcoordinates", 0);
        rules.put("doentitydrops", 1);
        rules.put("dodaylightcycle", 1);
        int changed = LevelDat.applyAll(dst, 0, 3, false, true, false, true, 1000L,
                123456789012345L, 10, 64, 10, 1.0, 12000, 1.0, 12000, rules);
        System.out.println("changed=" + changed);
        if (changed == 18) ok("改动计数 = 18（7 世界参数 + 种子 + 3 出生点 + 4 天气 + 3 规则）"); else no("改动计数 期望 18 实际 " + changed);

        LevelDat.Info info = LevelDat.read(dst);
        eq(info.gameType, 0, "GameType");
        eq(info.difficulty, 3, "Difficulty");
        eq(info.cheats, false, "作弊关");
        eq(info.time, 1000L, "Time");
        eq(info.seed, 123456789012345L, "RandomSeed");
        eq(info.spawnX, 10, "SpawnX");
        eq(info.spawnY, 64, "SpawnY");
        eq(info.spawnZ, 10, "SpawnZ");
        eq(info.daylight, true, "daylightCycle");

        // 直接读原始键核对天气与规则
        byte[] d = BedrockDb.readFile(dst);
        Nbt.Compound c = Nbt.parse(d, 8).asCompound();
        eq(num(c, "rainLevel", 0), 1.0, "rainLevel(float)");
        eq(((Number) c.get("rainLevel").value).intValue(), 1, "rainLevel 仍是 float 类型 1.0");
        eq(c.get("rainLevel").type, 5, "rainLevel type=FLOAT");
        eq(num(c, "rainTime", 0), 12000.0, "rainTime");
        eq(num(c, "lightningLevel", 0), 1.0, "lightningLevel");
        eq(num(c, "lightningTime", 0), 12000.0, "lightningTime");
        eq(num(c, "showcoordinates", -1), 0.0, "showcoordinates 关");
        eq(num(c, "doentitydrops", -1), 1.0, "doentitydrops 开");
        eq(num(c, "dodaylightcycle", -1), 1.0, "dodaylightcycle 开");
        eq(c.get("showcoordinates").type, 1, "规则键类型仍是 BYTE");
        eq(c.size(), 115, "键总数不变（全为已存在键）");

        // 新增一个存档里没有的规则键，应补 byte
        File dst2 = new File("/tmp/level_extra2.dat");
        BedrockDb.writeFile(dst2, BedrockDb.readFile(src));
        Map<String, Integer> r2 = new LinkedHashMap<>();
        r2.put("fake_rule_for_test", 1);
        LevelDat.applyAll(dst2, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, r2);
        Nbt.Compound c2 = Nbt.parse(BedrockDb.readFile(dst2), 8).asCompound();
        eq(c2.size(), 116, "新规则键数量 +1");
        eq(c2.get("fake_rule_for_test").type, 1, "新键是 BYTE");
        eq(num(c2, "fake_rule_for_test", 0), 1.0, "新键值");

        // 火、雷关闭 → 晴
        File dst3 = new File("/tmp/level_extra3.dat");
        BedrockDb.writeFile(dst3, BedrockDb.readFile(src));
        LevelDat.applyAll(dst3, null, null, null, null, null, null, null,
                null, null, null, null, 0.0, 3000, 0.0, 3000, null);
        Nbt.Compound c3 = Nbt.parse(BedrockDb.readFile(dst3), 8).asCompound();
        eq(num(c3, "rainLevel", -1), 0.0, "晴 rainLevel 0");
        eq(num(c3, "rainTime", -1), 3000.0, "晴 rainTime 3000");

        System.out.println("\n结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    static double num(Nbt.Compound c, String k, double def) {
        Nbt.Tag t = c.get(k);
        return t != null && t.value instanceof Number ? ((Number) t.value).doubleValue() : def;
    }
}
