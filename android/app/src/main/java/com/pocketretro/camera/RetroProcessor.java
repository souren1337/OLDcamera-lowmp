package com.pocketretro.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** CPU reference pipeline: low-resolution sampling, CMOS-like noise, warmth and lens falloff. */
public final class RetroProcessor {
    private RetroProcessor() { }

    public static Bitmap apply(Bitmap source, CameraProfile profile, int targetWidth,
                               int targetHeight, boolean dateStamp) {
        if (source == null) throw new IllegalArgumentException("Source image is empty");
        int[] output = fitToAspect(source, targetWidth, targetHeight);
        int width = output[0];
        int height = output[1];
        double targetAspect = targetWidth / (double) targetHeight;
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();
        if (source.getWidth() / (double) source.getHeight() > targetAspect)
            cropWidth = (int) Math.round(source.getHeight() * targetAspect);
        else cropHeight = (int) Math.round(source.getWidth() / targetAspect);
        int left = (source.getWidth() - cropWidth) / 2;
        int top = (source.getHeight() - cropHeight) / 2;
        Bitmap cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, width, height, true);
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != cropped && !scaled.isRecycled()) scaled.recycle();
        if (cropped != source && !cropped.isRecycled()) cropped.recycle();

        Random random = new Random(System.currentTimeMillis() / 1000L + profile.ordinal() * 7919L);
        float cx = (width - 1) * 0.5f;
        float cy = (height - 1) * 0.5f;
        float maxRadius = (float) Math.sqrt(cx * cx + cy * cy);
        int block = Math.max(1, profile.pixelBlock);
        int[] sample = pixels.clone();

        for (int y = 0; y < height; y++) {
            int rowBand = random.nextInt(profile.grain * 2 + 1) - profile.grain;
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int color = sample[index];
                if (block > 1) {
                    int sx = (x / block) * block;
                    int sy = (y / block) * block;
                    color = sample[Math.min(height - 1, sy) * width + Math.min(width - 1, sx)];
                }
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                float luminance = 0.299f * r + 0.587f * g + 0.114f * b;
                float dx = (x - cx) / Math.max(1f, cx);
                float dy = (y - cy) / Math.max(1f, cy);
                float radius = (float) Math.sqrt(dx * dx + dy * dy);
                float falloff = Math.max(0.58f, 1f - profile.vignette * radius * radius);

                int noiseR = random.nextInt(profile.grain * 2 + 1) - profile.grain;
                int noiseG = random.nextInt(profile.grain * 2 + 1) - profile.grain;
                int noiseB = random.nextInt(profile.grain * 2 + 1) - profile.grain;
                float rr = (luminance + (r - luminance) * profile.saturation) * profile.contrast
                        + 128f * (1f - profile.contrast) + profile.warmth * 48f;
                float gg = (luminance + (g - luminance) * profile.saturation) * profile.contrast
                        + 128f * (1f - profile.contrast) + profile.warmth * 10f;
                float bb = (luminance + (b - luminance) * profile.saturation) * profile.contrast
                        + 128f * (1f - profile.contrast) - profile.warmth * 36f;

                // Bright-edge halation: old compact sensors often bloom warm highlights.
                if (luminance > 218f) {
                    float glow = (luminance - 218f) / 37f * profile.halation;
                    rr += 30f * glow;
                    gg += 8f * glow;
                }
                // Sparse horizontal readout banding and dark-current noise.
                int band = (y % 47 == 0) ? -profile.grain / 2 : 0;
                r = clamp(Math.round((rr + noiseR + rowBand + band) * falloff));
                g = clamp(Math.round((gg + noiseG + rowBand + band) * falloff));
                b = clamp(Math.round((bb + noiseB + rowBand + band) * falloff));
                pixels[index] = Color.rgb(r, g, b);
            }
        }

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        if (dateStamp) drawDateStamp(result);
        return result;
    }

    private static int[] fitToAspect(Bitmap source, int maxWidth, int maxHeight) {
        double targetAspect = maxWidth / (double) maxHeight;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double sourceAspect = sourceWidth / (double) sourceHeight;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        if (sourceAspect > targetAspect) cropWidth = (int) Math.round(sourceHeight * targetAspect);
        else cropHeight = (int) Math.round(sourceWidth / targetAspect);
        float scale = Math.min(maxWidth / (float) cropWidth, maxHeight / (float) cropHeight);
        return new int[]{Math.max(1, Math.round(cropWidth * scale)), Math.max(1, Math.round(cropHeight * scale))};
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static void drawDateStamp(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        shadow.setTextSize(Math.max(12f, bitmap.getWidth() * 0.026f));
        shadow.setColor(Color.BLACK);
        String date = new SimpleDateFormat("MM/dd/yy", Locale.US).format(new Date());
        float x = bitmap.getWidth() * 0.05f;
        float y = bitmap.getHeight() * 0.94f;
        canvas.drawText(date, x + 1, y + 1, shadow);
        shadow.setColor(Color.rgb(255, 158, 66));
        canvas.drawText(date, x, y, shadow);
    }
}
