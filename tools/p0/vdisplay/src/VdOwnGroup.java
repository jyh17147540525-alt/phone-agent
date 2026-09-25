// VdOwnGroup: 验证「独立 DisplayGroup + 自有焦点」能否阻止跨应用跳转落主屏
// 用法: app_process -Djava.class.path=/data/local/tmp/vd2.jar /system/bin VdOwnGroup <flags十进制>
//
// flags 位（来自 AOSP android15-release DisplayManager.java）:
//   1<<0  PUBLIC
//   1<<1  PRESENTATION
//   1<<3  OWN_CONTENT_ONLY
//   1<<4  AUTO_MIRROR
//   1<<6  SUPPORTS_TOUCH
//   1<<10 TRUSTED            （需 ADD_TRUSTED_DISPLAY，shell 持有）
//   1<<11 OWN_DISPLAY_GROUP  （独立 DisplayGroup！需 TRUSTED 权限；且不能与 AUTO_MIRROR 同用）
//   1<<12 ALWAYS_UNLOCKED    （需 OWN_DISPLAY_GROUP + ADD_ALWAYS_UNLOCKED_DISPLAY）
//   1<<14 OWN_FOCUS          （需 TRUSTED）
//   1<<16 STEAL_TOP_FOCUS_DISABLED（需 TRUSTED + OWN_FOCUS）
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Surface;

public class VdOwnGroup {
    static ImageReader ir;

    public static void main(String[] args) {
        try {
            try { android.os.Looper.prepareMainLooper(); } catch (Throwable ignore) { }

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            Context sysCtx = (Context) atClass.getMethod("getSystemContext").invoke(thread);
            Context ctx = sysCtx.createPackageContext("com.android.shell", 0);

            int flags = args.length > 0 ? Integer.parseInt(args[0]) : 0;

            ir = ImageReader.newInstance(1080, 2340, android.graphics.PixelFormat.RGBA_8888, 3);
            Surface surface = ir.getSurface();
            System.out.println("SURFACE created");

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay vd = dm.createVirtualDisplay("vd-owngroup", 1080, 2340, 240, surface, flags);

            android.view.Display d = vd.getDisplay();
            System.out.println("CREATED flags=" + flags + " displayId=" + d.getDisplayId()
                + " name=" + d.getName() + " state=" + d.getState());
            System.out.println("FLAG_MARKER displayId=" + d.getDisplayId());

            // 长时间存活，供外部注入与观察
            long t0 = System.currentTimeMillis();
            long last = 0;
            while (true) {
                Thread.sleep(200);
                long now = System.currentTimeMillis();
                if (now - last >= 2000) {
                    last = now;
                    saveFrame("/data/local/tmp/vd2_frame.jpg");
                }
                if (now - t0 > 30 * 60 * 1000L) break;
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
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            img.close();
            System.out.println("FRAME_SAVED " + new java.io.File(path).length() + "B");
        } catch (Throwable t) {
            System.out.println("FRAME_ERR " + t);
        }
    }
}
