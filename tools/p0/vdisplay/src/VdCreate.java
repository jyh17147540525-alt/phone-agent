// VdCreate: 用 shell 特权(app_process)创建「自有内容 + 消费面」的虚拟屏
// 用法: app_process -Djava.class.path=/data/local/tmp/vd.jar /system/bin VdCreate <mode>
// mode 位: 1=PUBLIC 2=PRESENTATION 4=OWN_CONTENT_ONLY 8=AUTO_MIRROR 16=null surface(默认提供ImageReader面)
//   ⚠️ 修正记录(2026-09-25): 原注释把 mode bit 2 写作 "TOUCH"，但 flag 值 2 实际是
//      VIRTUAL_DISPLAY_FLAG_PRESENTATION；SUPPORTS_TOUCH 是 1<<6=64。原注释会误导接手人。
// 目的: 验证「无感 + 全树 + 注入点击 + 像素帧」能否在第三类虚拟屏上同时成立
// ⚠️ 本脚本只能表达 5 个 flag；要测 OWN_DISPLAY_GROUP / OWN_FOCUS 等请用 VdOwnGroup（直接传十进制）
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Surface;

public class VdCreate {
    static ImageReader ir;

    public static void main(String[] args) {
        try {
            // app_process 主线程没有 Looper, 先准备好(部分系统服务构造需要)
            try { android.os.Looper.prepareMainLooper(); } catch (Throwable ignore) { }
            // ActivityThread 是隐藏类, 用反射获取系统 Context, 再加载 com.android.shell 包上下文
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context sysCtx = (Context) atClass.getMethod("getSystemContext").invoke(thread);
            Context ctx = sysCtx.createPackageContext("com.android.shell", 0);

            int mode = args.length > 0 ? Integer.parseInt(args[0]) : 0;
            int flags = 0;
            if ((mode & 1) != 0) flags |= 1;      // PUBLIC
            if ((mode & 2) != 0) flags |= 2;      // PRESENTATION（原注释误写为 TOUCH）
            if ((mode & 4) != 0) flags |= 4;      // OWN_CONTENT_ONLY
            if ((mode & 8) != 0) flags |= 8;      // AUTO_MIRROR

            Surface surface = null;
            if ((mode & 16) == 0) {
                // 消费面: 虚拟屏内容渲染到这个 ImageReader, 我们可读像素
                ir = ImageReader.newInstance(1080, 2340, android.graphics.PixelFormat.RGBA_8888, 3);
                surface = ir.getSurface();
                System.out.println("SURFACE created (ImageReader)");
            }

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay vd = dm.createVirtualDisplay("vd-touch", 1080, 2340, 240, surface, flags);

            android.view.Display d = vd.getDisplay();
            System.out.println("CREATED mode=" + mode + " flags=" + flags
                + " displayId=" + d.getDisplayId() + " name=" + d.getName() + " state=" + d.getState());
            System.out.println("FLAG_MARKER displayId=" + d.getDisplayId() + " name=" + d.getName());

            if (ir != null) {
                // 每 2 秒保存一帧到 /data/local/tmp(可被 adb 拉取), 用于验证像素通道
                long t0 = System.currentTimeMillis();
                long last = 0;
                while (true) {
                    Thread.sleep(200);
                    long now = System.currentTimeMillis();
                    if (now - last >= 2000) {
                        last = now;
                        saveFrame("/data/local/tmp/vd_frame.jpg");
                    }
                    if (now - t0 > 20 * 60 * 1000L) break;
                }
            } else {
                Thread.sleep(20 * 60 * 1000L);
            }
        } catch (Throwable t) {
            System.out.println("ERROR: " + t);
            t.printStackTrace();
        }
    }

    static void saveFrame(String path) {
        try {
            android.media.Image img = ir.acquireLatestImage();
            if (img == null) { System.out.println("NO_FRAME yet"); return; }
            android.media.Image.Plane plane = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            java.nio.ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pxStride = plane.getPixelStride();
            buf.rewind();
            for (int y = 0; y < h; y++) {
                int rowStart = y * rowStride;
                for (int x = 0; x < w; x++) {
                    int off = rowStart + x * pxStride;
                    int b = buf.get(off) & 0xFF;
                    int g = buf.get(off + 1) & 0xFF;
                    int r = buf.get(off + 2) & 0xFF;
                    int a = buf.get(off + 3) & 0xFF;
                    pixels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            img.close();
            System.out.println("FRAME_SAVED " + path + " " + new java.io.File(path).length() + "B");
        } catch (Throwable t) {
            System.out.println("FRAME_ERR " + t);
        }
    }
}
