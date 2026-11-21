package com.nmsave.editor;

/** 物品显示名 / 介绍（display）/ 死亡不掉落（keep_on_death）的纯逻辑，无 Android 依赖，桌面可测。 */
public class ItemDisplay {

    // ---------- 显示名 / 介绍 / 死亡不掉落 ----------

    public static Nbt.Compound itemCompound(Nbt.Tag item) {
        if (item == null || item.type != Nbt.TAG_COMPOUND) return null;
        return (Nbt.Compound) item.value;
    }

    /** 找物品 tag 里的 display：优先 tag.display，没有再找 tag.userData.display。 */
    public static Nbt.Compound findDisplay(Nbt.Tag item) {
        Nbt.Compound c = itemCompound(item);
        if (c == null) return null;
        Nbt.Tag tag = c.get("tag");
        if (tag == null || tag.type != Nbt.TAG_COMPOUND) return null;
        Nbt.Compound tc = (Nbt.Compound) tag.value;
        Nbt.Tag d = tc.get("display");
        if (d != null && d.type == Nbt.TAG_COMPOUND) return (Nbt.Compound) d.value;
        Nbt.Tag ud = tc.get("userData");
        if (ud != null && ud.type == Nbt.TAG_COMPOUND) {
            Nbt.Tag d2 = ((Nbt.Compound) ud.value).get("display");
            if (d2 != null && d2.type == Nbt.TAG_COMPOUND) return (Nbt.Compound) d2.value;
        }
        return null;
    }

    public static String displayNameOf(Nbt.Tag item) {
        Nbt.Compound d = findDisplay(item);
        if (d == null) return "";
        Nbt.Tag n = d.get("Name");
        return n == null ? "" : String.valueOf(n.value);
    }

    public static String displayLoreOf(Nbt.Tag item) {
        Nbt.Compound d = findDisplay(item);
        if (d == null) return "";
        Nbt.Tag l = d.get("Lore");
        if (l == null || l.type != Nbt.TAG_LIST) return "";
        Nbt.ListTag lt = (Nbt.ListTag) l.value;
        StringBuilder sb = new StringBuilder();
        for (Nbt.Tag t : lt.items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.valueOf(t.value));
        }
        return sb.toString();
    }

    public static boolean hasKeepOnDeath(Nbt.Tag item) {
        Nbt.Compound c = itemCompound(item);
        if (c == null) return false;
        Nbt.Tag tag = c.get("tag");
        if (tag == null || tag.type != Nbt.TAG_COMPOUND) return false;
        Nbt.Compound tc = (Nbt.Compound) tag.value;
        return tc.get("minecraft:keep_on_death") != null || tc.get("keep_on_death") != null;
    }

    public static int countLore(String text) {
        int n = 0;
        if (text == null) return 0;
        for (String line : text.split("\\n", -1)) {
            if (line.trim().length() > 0) n++;
        }
        return n;
    }

    /**
     * 改显示名 / 介绍 / 死亡不掉落，返回新的 JSON 文本；解析失败返回 null。
     * keepOn 为 true 时写 minecraft:keep_on_death（空 compound），false 时两种写法都删掉。
     */
    public static String patchDisplay(String text, Nbt.Tag template, String name, String lore, boolean keepOn) {
        Nbt.Tag parsed;
        try {
            parsed = NbtJson.fromJsonAny(text, template);
        } catch (Throwable t) {
            return null;
        }
        if (parsed.type != Nbt.TAG_COMPOUND) return null;
        Nbt.Compound c = (Nbt.Compound) parsed.value;

        name = name == null ? "" : name.trim();
        String[] lines = lore == null ? new String[0] : lore.split("\\n", -1);
        java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
        for (String line : lines) {
            String t = line.trim();
            if (t.length() > 0) keep.add(t);
        }

        Nbt.Tag tag = c.get("tag");
        if (keepOn || name.length() > 0 || keep.size() > 0) {
            if (tag == null || tag.type != Nbt.TAG_COMPOUND) {
                tag = new Nbt.Tag(Nbt.TAG_COMPOUND, "tag", new Nbt.Compound());
                c.put("tag", tag);
            }
        }
        if (tag != null && tag.type == Nbt.TAG_COMPOUND) {
            Nbt.Compound tc = (Nbt.Compound) tag.value;
            // 原档已经写在 userData.display 的，就跟着写回原位置
            Nbt.Compound disp = findDisplay(parsed);
            boolean underUserData = false;
            Nbt.Tag ud = tc.get("userData");
            if (disp == null && ud != null && ud.type == Nbt.TAG_COMPOUND) {
                Nbt.Tag d2 = ((Nbt.Compound) ud.value).get("display");
                if (d2 != null && d2.type == Nbt.TAG_COMPOUND) {
                    disp = (Nbt.Compound) d2.value;
                    underUserData = true;
                }
            }
            if (disp == null && (name.length() > 0 || keep.size() > 0)) {
                disp = new Nbt.Compound();
                tc.put("display", new Nbt.Tag(Nbt.TAG_COMPOUND, "display", disp));
            }
            if (disp != null) {
                if (name.length() == 0) disp.remove("Name");
                else disp.put("Name", new Nbt.Tag(Nbt.TAG_STRING, "Name", name));
                if (keep.size() == 0) {
                    disp.remove("Lore");
                } else {
                    Nbt.ListTag lt = new Nbt.ListTag();
                    lt.elemType = Nbt.TAG_STRING;
                    for (String line : keep) lt.items.add(new Nbt.Tag(Nbt.TAG_STRING, "", line));
                    disp.put("Lore", new Nbt.Tag(Nbt.TAG_LIST, "Lore", lt));
                }
                if (disp.isEmpty()) {
                    if (underUserData) {
                        Nbt.Compound uc = (Nbt.Compound) ud.value;
                        uc.remove("display");
                        if (uc.isEmpty()) tc.remove("userData");
                    } else {
                        tc.remove("display");
                    }
                }
            }
            if (keepOn) {
                // 原档用的是不带命名空间的写法就跟着它，避免同义键重复
                if (tc.get("keep_on_death") != null && tc.get("minecraft:keep_on_death") == null) {
                    tc.put("keep_on_death",
                            new Nbt.Tag(Nbt.TAG_COMPOUND, "keep_on_death", new Nbt.Compound()));
                } else {
                    tc.put("minecraft:keep_on_death",
                            new Nbt.Tag(Nbt.TAG_COMPOUND, "minecraft:keep_on_death", new Nbt.Compound()));
                }
            } else {
                tc.remove("minecraft:keep_on_death");
                tc.remove("keep_on_death");
            }
            if (tc.isEmpty()) c.remove("tag");
        }
        return NbtJson.toJson(parsed);
    }

}
