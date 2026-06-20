package shell;

import java.io.BufferedInputStream;
import java.nio.ByteBuffer;

public class ScreenColor {
    private static boolean lastIsLight = false;
    private static long lastTime = System.currentTimeMillis();
    private static final double allTime = 400.0;
    
    public static int getBarColor() {
        if (lastIsLight) {
            double a = Math.min(1.0, (System.currentTimeMillis() - lastTime) / allTime);
            int c = (int)Math.floor(70+(1.0-a)*165.0);
            return (0xFF << 24) | ((c & 0xFF) << 16) | ((c & 0xFF) << 8) | (c & 0xFF);
        } else {
            double a = Math.min(1.0, (System.currentTimeMillis() - lastTime) / allTime);
            int c = (int)Math.floor(70+a*165.0);
            return (0xFF << 24) | ((c & 0xFF) << 16) | ((c & 0xFF) << 8) | (c & 0xFF);
        }
    }

    private static void computeBarColor(long[] colors) {
        if (colors.length == 0) {
            return;
        }

        int lightPixelCount = 0;
        int darkPixelCount = 0;

        int center = colors.length / 2;

        for (int i = center; i < colors.length; i++) {
            if (pixelIsLightColor(colors[i])) {
                lightPixelCount++;
            } else {
                darkPixelCount++;
            }
        }

        if (lightPixelCount > darkPixelCount) {
            // System.out.println("Gesture ADB Process: iOS White Bar Color Set To {Color.Black}");
            if (!lastIsLight) lastTime = System.currentTimeMillis();
            lastIsLight = true;
        } else {
            // System.out.println("Gesture ADB Process: iOS White Bar Color Set To {Color.White}");
            if (lastIsLight) lastTime = System.currentTimeMillis();
            lastIsLight = false;
        }
    }

    static void printRGBA(int color) {
        int r = color >>> 24;
        int g = (color & 0xff0000) >> 16;
        int b = (color & 0xff00) >> 8;
        int a = color & 0xff;
        // System.out.println(r + ", " + g + ", " + b + ", " + a);
    }

    private static boolean pixelIsLightColor(long color) {
        long r = color >>> 24;
        long g = (color & 0xff0000) >> 16;
        long b = (color & 0xff00) >> 8;
        // long a = color & 0xff;

        // System.out.printf("RGBA(%d,%d,%d,%d)\n", r, g, b, a);
        // alpha通道基本上都是固定的255
        // return r > 180 && g > 180 && b > 180 && a > 180;
        return r > 128 && g > 128 && b > 128;
    }

    public static void autoBarColor() {
        try {
            Process exec = Runtime.getRuntime().exec("screencap");

            PixelGather byteBuffer = PixelGather.frameBuffer(GlobalState.displayHeight, GlobalState.displayWidth);

            Thread thread = new PixelGatherThread(byteBuffer, new BufferedInputStream(exec.getInputStream()));
            thread.start();
            synchronized (byteBuffer) {
                byteBuffer.wait(3500);
            }
            try {
                exec.destroy();
            } catch (Exception ignored) {
            }

            // 更新颜色
            computeBarColor(byteBuffer.array());
        } catch (Exception e) {
            // System.out.println("Gesture ADB BarColor Error: " + e.getMessage());
        }
    }

    // 像素采集
    private static class PixelGather {
        private static final int fileHeader = 12; // 文件头部长度
        private static final int fileFooter = 4; // FIXME:文件脚部长度（Android 8.1 即SDK27 以前没有这4Byte！）
        private int frameSize;
        private int position;
        private ByteBuffer buffer; // 真实的缓冲区
        // 除了头部和脚部，则每4Byte(RGBA)代表一个像素，例如左上角第一个像素就是 bytes[16] ~ bytes[19]

        // 用于取样的像素在帧数据中的位置
        // 采样的像素点数量建议设为 单数，因为最终会对比 暗色/亮色 点的数量
        // 前一半为状态栏的取样点，后一半为导航栏附近的取样点，数量应保持一致（因为后面会拆分成两部分分别计算亮色点数量）
        private int[] samplingPixel = {
                fileHeader + (GlobalState.displayWidth * 1 / 10 * 4), // y: 0, x: 0.1
                fileHeader + (GlobalState.displayWidth * 2 / 10 * 4), // y: 0, x: 0.2
                fileHeader + (GlobalState.displayWidth * 3 / 10 * 4), // y: 0, x: 0.3
                fileHeader + (GlobalState.displayWidth * 4 / 10 * 4), // y: 0, x: 0.4
                fileHeader + (GlobalState.displayWidth * 5 / 10 * 4), // y: 0, x: 0.5
                fileHeader + (GlobalState.displayWidth * 6 / 10 * 4), // y: 0, x: 0.6
                fileHeader + (GlobalState.displayWidth * 7 / 10 * 4), // y: 0, x: 0.7
                fileHeader + (GlobalState.displayWidth * 8 / 10 * 4), // y: 0, x: 0.8
                fileHeader + (GlobalState.displayWidth * 9 / 10 * 4), // y: 0, x: 0.9
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 1 / 10)) * 4), // y: 1, x : 0.1
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 2 / 10)) * 4), // y: 1, x : 0.2
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 3 / 10)) * 4), // y: 1, x : 0.3
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 4 / 10)) * 4), // y: 1, x : 0.4
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 5 / 10)) * 4), // y: 1, x : 0.5
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 6 / 10)) * 4), // y: 1, x : 0.6
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 7 / 10)) * 4), // y: 1, x : 0.7
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 8 / 10)) * 4), // y: 1, x : 0.8
                fileHeader + (((GlobalState.displayWidth * GlobalState.displayHeight) - (GlobalState.displayWidth * 9 / 10)) * 4)  // y: 1, x : 0.9
        };

        private PixelGather(int frameSize) {
            this.frameSize = frameSize;
            // 32位的像素，每个占用 4 字节
            this.buffer = ByteBuffer.allocate(samplingPixel.length * 4);
        }

        static PixelGather frameBuffer(int height, int width) {
            return new PixelGather(fileHeader + (height * width * 4) + fileFooter);
        }

        public static String bytesToHex(byte[] bytes) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xFF);
                if (hex.length() < 2) {
                    sb.append(0);
                }
                sb.append(hex);
            }
            return sb.toString();
        }

        void put(byte[] bytes, int offset, int count) {
            if (position + count > frameSize) {
                throw new IndexOutOfBoundsException("数据写入量超出缓冲区可用空间");
            }
            if (count != 0) {
                for (int pixel : samplingPixel) {
                    sampling(bytes, offset, count, pixel);
                }
            }
            position += count;
        }

        void sampling(byte[] bytes, int offset, int count, int pixel) {
            int rangeLeft = position;
            int rangeRight = position + count;

            // 判断像素是否在区域内
            if (pixel + 3 >= rangeLeft && pixel + 3 <= rangeRight) {
                // Log.d("AAAAA", "position " + position + "  rangeLeft " + rangeLeft + " rangeRight" + rangeRight);
                int targetIndex = position > pixel ? position : pixel;
                for (; targetIndex <= pixel + 3; targetIndex++) {
                    buffer.put(bytes[targetIndex - position + offset]);
                }
            }
        }

        int remaining() {
            return this.frameSize - position;
        }

        void clear() {
            position = 0;
            buffer.clear();
        }

        long[] array() {
            byte[] bytes = buffer.array();
            long[] colors = new long[bytes.length / 4];
            // System.out.print("Points: ");
            for (int i = 0; i < bytes.length; i += 4) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < 4; j++) {
                    String hex = Integer.toHexString(bytes[i + j] & 0xFF);
                    if (hex.length() < 2) {
                        sb.append(0);
                    }
                    sb.append(hex);
                }
                // System.out.print("  " + sb.toString());
                colors[i / 4] = Long.parseLong(sb.toString(), 16);
            }
            // System.out.println("");
            return colors;
        }
    }

    static class PixelGatherThread extends Thread {
        private PixelGather byteBuffer;
        private BufferedInputStream bufferedInputStream;

        PixelGatherThread(PixelGather pixelGather, BufferedInputStream bufferedInputStream) {
            this.byteBuffer = pixelGather;
            this.bufferedInputStream = bufferedInputStream;
        }

        @Override
        public void run() {
            try {
                int count;
                int cacheSize = 1024 * 1024; // 1M
                byte[] tempBuffer = new byte[cacheSize];
                while ((count = bufferedInputStream.read(tempBuffer)) > 0) {
                    int remaining = byteBuffer.remaining();
                    if (count > remaining) { // 读取了超过一帧
                        byteBuffer.put(tempBuffer, 0, remaining);
                        break;
                    } else if (count == remaining) { // 刚好读满一帧
                        byteBuffer.put(tempBuffer, 0, count);
                        break;
                    } else { // 不到一帧，继续读
                        byteBuffer.put(tempBuffer, 0, count);
                    }
                }
            } catch (Exception ignored) {

            } finally {
                synchronized (byteBuffer) {
                    byteBuffer.notify();
                }
            }
        }
    }
}