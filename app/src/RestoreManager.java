package com.artex.mobile;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从备份 zip 恢复全部数据（与 BackupManager 对称）。
 *
 * 安全策略：先把现有 pgdata / artex 改名为 .old，再解压覆盖；
 * 解压成功才删掉 .old，失败则回滚 —— 避免出现「新数据没解出来、旧数据也毁了」。
 *
 * 不涉及 SharedPreferences（网桥池、开关、实测状态）—— 那些属于设备配置。
 */
public class RestoreManager {

    public interface Callback {
        void onProgress(String msg);
        void onDone();
        void onError(String msg);
    }

    private final Context ctx;
    private final File filesDir;

    public RestoreManager(Context ctx) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
    }

    public void run(ArtExService svc, Uri zipUri, Callback cb) {
        File pg = new File(filesDir, "pgdata");
        File pgOld = new File(filesDir, "pgdata.old");
        File artex = new File(filesDir, "artex");
        File artexOld = new File(filesDir, "artex.old");
        boolean moved = false;
        try {
            cb.onProgress("停止服务…");
            if (svc != null) svc.stop();
            waitPgExit(cb);

            cb.onProgress("备份现有数据（以便失败回滚）…");
            deleteRecursive(pgOld);
            deleteRecursive(artexOld);
            if (pg.exists()) moved |= pg.renameTo(pgOld);
            if (artex.exists()) moved |= artex.renameTo(artexOld);

            cb.onProgress("解压备份…");
            int n = unzip(zipUri, filesDir, cb);

            // 必须校验到实质内容：
            // PG_VERSION 只有 3 字节、是最先解压出来的几个文件之一，它存在
            // 并不代表业务数据也解出来了。所以还要看 base/ 里有没有库文件。
            File newPg = new File(filesDir, "pgdata");
            if (!new File(newPg, "PG_VERSION").exists()) {
                throw new Exception("备份不完整：pgdata 里没有 PG_VERSION");
            }
            File base = new File(newPg, "base");
            if (!base.isDirectory() || base.list() == null || base.list().length == 0) {
                throw new Exception("备份不完整：pgdata/base 为空（只还原了 " + n + " 个文件）");
            }

            // PG 对数据目录权限敏感，恢复后修正
            newPg.setReadable(true, true);
            newPg.setWritable(true, true);
            newPg.setExecutable(true, true);

            // 旧数据不删，改名为带时间戳的目录留着 ——
            // 万一新数据有问题，用户还有回头路可走
            String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            // 只保留最近一份：每份 pgdata.old 都几十 MB，多次恢复会把空间吃满。
            // 先清掉更早的，再改名。
            pruneOldBackups();
            if (pgOld.exists()) pgOld.renameTo(new File(filesDir, "pgdata.old-" + stamp));
            if (artexOld.exists()) artexOld.renameTo(new File(filesDir, "artex.old-" + stamp));

            cb.onProgress("完成，共还原 " + n + " 个文件");
            cb.onDone();
        } catch (Throwable t) {
            Log.e("ARTEX", "恢复失败", t);
            // 回滚：删掉解压出来的一半，恢复原数据
            try {
                if (moved) {
                    deleteRecursive(pg);
                    deleteRecursive(artex);
                    if (pgOld.exists()) pgOld.renameTo(pg);
                    if (artexOld.exists()) artexOld.renameTo(artex);
                }
            } catch (Throwable t2) {
                Log.e("ARTEX", "回滚也失败了", t2);
            }
            cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    /** 删掉上一次恢复留下的 pgdata.old-* / artex.old-*（只保留最新一份） */
    private void pruneOldBackups() {
        try {
            File[] all = filesDir.listFiles();
            if (all == null) return;
            for (File f : all) {
                String n = f.getName();
                if (n.startsWith("pgdata.old-") || n.startsWith("artex.old-")) {
                    deleteRecursive(f);
                }
            }
        } catch (Throwable t) {
            Log.w("ARTEX", "清理旧备份失败: " + t);
        }
    }

    private void waitPgExit(Callback cb) {
        File pid = new File(filesDir, "pgdata/postmaster.pid");
        for (int i = 0; i < 40; i++) {
            if (!pid.exists()) return;
            if (i % 6 == 0) cb.onProgress("等待数据库退出…");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private int unzip(Uri uri, File destRoot, Callback cb) throws Exception {
        InputStream raw = ctx.getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("无法读取所选文件");
        int count = 0, skipped = 0;
        ZipInputStream zin = new ZipInputStream(raw);
        try {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                // 防 zip slip：纯字符串判断就行。
                // 原先用 getCanonicalPath() 比较，但它会解析符号链接
                // （Android 上 /data/user/0 是指向 /data/data 的软链），
                // 两边解析结果不一致时会把所有文件默默跳过且不报错。
                if (name.startsWith("/") || name.contains("..")) {
                    skipped++;
                    zin.closeEntry();
                    continue;
                }
                File out = new File(destRoot, name);
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) fo.write(buf, 0, r);
                    }
                    count++;
                    if (count % 200 == 0) cb.onProgress("已还原 " + count + " 个文件…");
                }
                zin.closeEntry();
            }
        } finally {
            try {
                zin.close();
            } catch (Throwable ignored) {}
        }
        if (skipped > 0) cb.onProgress("（跳过 " + skipped + " 条异常路径）");
        return count;
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }
}
