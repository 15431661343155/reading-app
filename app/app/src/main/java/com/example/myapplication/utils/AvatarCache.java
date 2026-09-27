package com.example.myapplication.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.example.myapplication.api.RetrofitClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 头像本地缓存：把服务器相对路径（形如 /avatars/xxx.png）下载到应用私有目录，
 * 供显示层用 {@code BitmapFactory.decodeFile} 直接读取。
 *
 * <p>用途：
 * <ul>
 *   <li>登录成功后把服务器头像地址下载到本地，写回 {@code user_info.avatar}，
 *       这样退出重登（SP 被 clear）后头像不丢；</li>
 *   <li>「个人信息」页上传头像后，把服务器返回的相对地址下载到本地，
 *       保证 SP 里始终是可直接 decode 的本地路径，与显示层模型一致。</li>
 * </ul>
 *
 * <p>注意：必须在后台线程调用（内部走网络），下载结果通过回调/调用方切回主线程处理。
 */
public class AvatarCache {

    private static final String DIR = "avatars_cache";

    private static File cacheDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 把相对头像地址下载到本地缓存文件。
     *
     * @param ctx          上下文（用于拼接绝对路径与取缓存目录）
     * @param relativeUrl  形如 /avatars/xxx.png 的相对路径（来自登录响应 / 上传返回）
     * @return 本地文件；任何失败（网络/编码/空参）返回 null
     */
    public static File download(Context ctx, String relativeUrl) {
        if (ctx == null || relativeUrl == null || relativeUrl.isEmpty()) return null;
        String full = RetrofitClient.getFullImageUrl(relativeUrl);
        if (full.isEmpty()) return null;
        try {
            URL url = new URL(full);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                return null;
            }
            // 按相对路径 hash 命名，同一头像只缓存一份
            String name = "server_" + Math.abs((long) relativeUrl.hashCode()) + ".jpg";
            File out = new File(cacheDir(ctx), name);
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            conn.disconnect();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验本地文件是否为可解码的图片（防止 SP 指向了已失效的缓存）。 */
    public static boolean isValidImage(File file) {
        if (file == null || !file.exists()) return false;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp != null) {
            bmp.recycle();
            return true;
        }
        return false;
    }
}
