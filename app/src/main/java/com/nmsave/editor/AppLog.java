package com.nmsave.editor;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局运行日志：所有页面、后台服务、备份/写入都往同一个文件追加。
 * 位置：/sdcard/NM存档备份/运行日志.txt
 */
public final class AppLog {

    private static final Object LOCK = new Object();
    private static final String NAME = "运行日志.txt";
    private static final long MAX_BYTES = 800 * 1024L;   // 超过就裁掉前段，保留尾部

    private AppLog() {
    }

    public static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), "NM存档备份");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static File file() {
        return new File(dir(), NAME);
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg, t);
    }

    public static void e(String tag, Throwable t) {
        write("E", tag, null, t);
    }

    private static void write(String level, String tag, String msg, Throwable t) {
        synchronized (LOCK) {
            try {
                File f = file();
                if (f.length() > MAX_BYTES) trim(f);
                StringBuilder sb = new StringBuilder();
                sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
                        .append(" [").append(level).append("] ")
                        .append(tag == null ? "-" : tag).append("  ")
                        .append(msg == null ? "" : msg).append('\n');
                if (t != null) {
                    sb.append("    ").append(t.getClass().getName());
                    if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
                    sb.append('\n');
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < st.length && i < 6; i++) {
                        sb.append("      at ").append(st[i]).append('\n');
                    }
                }
                FileOutputStream fo = new FileOutputStream(f, true);
                try {
                    fo.write(sb.toString().getBytes("UTF-8"));
                } finally {
                    fo.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只保留尾部 300KB，避免日志无限增长。 */
    private static void trim(File f) {
        try {
            long keep = 300 * 1024L;
            long len = f.length();
            RandomAccessFile r = new RandomAccessFile(f, "rw");
            try {
                byte[] buf = new byte[(int) keep];
                r.seek(len - keep);
                r.readFully(buf);
                r.setLength(0);
                r.write(buf);
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读最后 maxChars 个字符，给日志页用。 */
    public static String readTail(int maxChars) {
        synchronized (LOCK) {
            try {
                File f = file();
                if (!f.isFile()) return "还没有日志。";
                RandomAccessFile r = new RandomAccessFile(f, "r");
                try {
                    long len = r.length();
                    int take = (int) Math.min(len, maxChars);
                    byte[] buf = new byte[take];
                    r.seek(len - take);
                    r.readFully(buf);
                    String s = new String(buf, "UTF-8");
                    return len > take ? "…（仅显示最近部分）\n" + s : s;
                } finally {
                    r.close();
                }
            } catch (Throwable t) {
                return "读取日志失败：" + t.getMessage();
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            try {
                File f = file();
                if (f.isFile()) {
                    FileOutputStream fo = new FileOutputStream(f, false);
                    fo.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
