package com.nmsave.editor;

import java.io.File;

/** level.dat 读写：8 字节头（4 字节版本 + 4 字节长度）+ 小端 NBT 根复合。 */
public class LevelDat {

    public static class Info {
        public String levelName = "";
        public String version = "";
        public int gameType = -1;
        public int difficulty = -1;
        public boolean cheats;
        public boolean pvp = true;
        public boolean keepInventory;
        public boolean daylight = true;
        public long seed;
        public long time;
        public int spawnX, spawnY, spawnZ;
        public boolean hasPlayer;
        public int keys;
    }

    public static Info read(File levelDat) throws Exception {
        Info info = new Info();
        byte[] data = BedrockDb.readFile(levelDat);
        if (data.length < 12) return info;
        Nbt.Tag root = Nbt.parse(data, 8);
        Nbt.Compound c = root.asCompound();
        if (c == null) return info;
        info.keys = c.size();
        info.hasPlayer = c.containsKey("Player");

        Nbt.Tag t = c.get("LevelName");
        if (t != null && t.value instanceof String) info.levelName = (String) t.value;

        t = c.get("lastOpenedWithVersion");
        if (t != null && t.value instanceof Nbt.ListTag) {
            Nbt.ListTag ls = (Nbt.ListTag) t.value;
            StringBuilder sb = new StringBuilder();
            for (Nbt.Tag it : ls.items) {
                if (it.value instanceof Number) {
                    if (sb.length() > 0) sb.append('.');
                    sb.append(((Number) it.value).intValue());
                }
            }
            info.version = sb.toString();
        }

        t = c.get("GameType");
        if (t != null) info.gameType = (int) num(t);
        t = c.get("Difficulty");
        if (t != null) info.difficulty = (int) num(t);
        t = c.get("commandsEnabled");
        if (t != null) info.cheats = num(t) != 0;
        t = c.get("pvp");
        if (t != null) info.pvp = num(t) != 0;
        t = c.get("keepinventory");
        if (t != null) info.keepInventory = num(t) != 0;
        t = c.get("daylightCycle");
        if (t != null) info.daylight = num(t) != 0;
        t = c.get("RandomSeed");
        if (t != null) info.seed = asLong(t);
        t = c.get("Time");
        if (t != null) info.time = asLong(t);
        t = c.get("SpawnX");
        if (t != null) info.spawnX = (int) num(t);
        t = c.get("SpawnY");
        if (t != null) info.spawnY = (int) num(t);
        t = c.get("SpawnZ");
        if (t != null) info.spawnZ = (int) num(t);
        return info;
    }

    /** long 直接取，避免经 double 丢精度（种子是 19 位整数）。 */
    private static long asLong(Nbt.Tag t) {
        return t.value instanceof Number ? ((Number) t.value).longValue() : 0L;
    }

    private static double num(Nbt.Tag t) {
        return t.value instanceof Number ? ((Number) t.value).doubleValue() : 0;
    }

    /**
     * 修改世界参数；传 null 表示不改。只改已存在的键，并沿用其原有类型，避免类型不匹配导致客户端拒绝。
     * @return 实际修改的字段数
     */
    public static int apply(File levelDat, Integer gameType, Integer difficulty, Boolean cheats,
                            Boolean pvp, Boolean keepInventory, Boolean daylight, Long time) throws Exception {
        return applyAll(levelDat, gameType, difficulty, cheats, pvp, keepInventory, daylight, time,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 一次写入所有世界参数；传 null 表示不改。只改已存在的键并沿用原类型；
     * 游戏规则键（byte）不存在时按 byte 新建。weather 已在外部换算成 level/time。
     */
    public static int applyAll(File levelDat, Integer gameType, Integer difficulty, Boolean cheats,
                               Boolean pvp, Boolean keepInventory, Boolean daylight, Long time,
                               Long seed, Integer spawnX, Integer spawnY, Integer spawnZ,
                               Double rainLevel, Integer rainTime,
                               Double lightningLevel, Integer lightningTime,
                               java.util.Map<String, Integer> byteRules) throws Exception {
        byte[] data = BedrockDb.readFile(levelDat);
        if (data.length < 12) return 0;
        Nbt.Tag root = Nbt.parse(data, 8);
        Nbt.Compound c = root.asCompound();
        if (c == null) return 0;

        int changed = 0;
        if (gameType != null && setNum(c, "GameType", gameType)) changed++;
        if (difficulty != null && setNum(c, "Difficulty", difficulty)) changed++;
        if (cheats != null && setNum(c, "commandsEnabled", cheats ? 1 : 0)) changed++;
        if (pvp != null && setNum(c, "pvp", pvp ? 1 : 0)) changed++;
        if (keepInventory != null && setNum(c, "keepinventory", keepInventory ? 1 : 0)) changed++;
        if (daylight != null && setNum(c, "daylightCycle", daylight ? 1 : 0)) changed++;
        if (time != null && setNum(c, "Time", time)) changed++;
        if (seed != null && setNum(c, "RandomSeed", seed)) changed++;
        if (spawnX != null && setNum(c, "SpawnX", spawnX)) changed++;
        if (spawnY != null && setNum(c, "SpawnY", spawnY)) changed++;
        if (spawnZ != null && setNum(c, "SpawnZ", spawnZ)) changed++;
        if (rainLevel != null && setFloat(c, "rainLevel", rainLevel)) changed++;
        if (rainTime != null && setNum(c, "rainTime", rainTime)) changed++;
        if (lightningLevel != null && setFloat(c, "lightningLevel", lightningLevel)) changed++;
        if (lightningTime != null && setNum(c, "lightningTime", lightningTime)) changed++;
        if (byteRules != null) {
            for (java.util.Map.Entry<String, Integer> e : byteRules.entrySet()) {
                if (setRule(c, e.getKey(), e.getValue())) changed++;
            }
        }

        if (changed == 0) return 0;
        write(levelDat, data, root);
        return changed;
    }

    /** 从根复合中移除 Player（旧玩家存档数据）。 */
    public static boolean stripPlayer(File levelDat) throws Exception {
        byte[] data = BedrockDb.readFile(levelDat);
        if (data.length < 12) return false;
        Nbt.Tag root = Nbt.parse(data, 8);
        Nbt.Compound c = root.asCompound();
        if (c == null || c.remove("Player") == null) return false;
        write(levelDat, data, root);
        return true;
    }

    private static void write(File levelDat, byte[] headerSource, Nbt.Tag root) throws Exception {
        byte[] payload = Nbt.writeRoot(root);
        byte[] out = new byte[8 + payload.length];
        System.arraycopy(headerSource, 0, out, 0, 4); // 保留原文件版本号
        out[4] = (byte) (payload.length & 0xFF);
        out[5] = (byte) ((payload.length >> 8) & 0xFF);
        out[6] = (byte) ((payload.length >> 16) & 0xFF);
        out[7] = (byte) ((payload.length >> 24) & 0xFF);
        System.arraycopy(payload, 0, out, 8, payload.length);
        BedrockDb.writeFile(levelDat, out);
    }

    /** 浮点字段（rainLevel / lightningLevel 是 float）：沿用原类型。 */
    private static boolean setFloat(Nbt.Compound c, String key, double v) {
        Nbt.Tag t = c.get(key);
        if (t == null) return false;
        switch (t.type) {
            case Nbt.TAG_FLOAT: t.value = (float) v; return true;
            case Nbt.TAG_DOUBLE: t.value = v; return true;
            case Nbt.TAG_INT: t.value = (int) v; return true;
            case Nbt.TAG_LONG: t.value = (long) v; return true;
            default: return false;
        }
    }

    /** 游戏规则键：已存在则沿用原类型改写，不存在则新建 byte（基岩版规则全是 byte）。 */
    private static boolean setRule(Nbt.Compound c, String key, int v) {
        Nbt.Tag t = c.get(key);
        if (t != null) return setNum(c, key, v);
        c.put(key, new Nbt.Tag(Nbt.TAG_BYTE, key, (byte) v));
        return true;
    }

    private static boolean setNum(Nbt.Compound c, String key, long v) {
        Nbt.Tag t = c.get(key);
        if (t == null) return false;
        switch (t.type) {
            case Nbt.TAG_BYTE: t.value = (byte) v; return true;
            case Nbt.TAG_SHORT: t.value = (short) v; return true;
            case Nbt.TAG_INT: t.value = (int) v; return true;
            case Nbt.TAG_LONG: t.value = v; return true;
            default: return false;
        }
    }
}
