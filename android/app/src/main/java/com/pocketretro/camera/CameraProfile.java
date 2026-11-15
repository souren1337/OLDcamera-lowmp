package com.pocketretro.camera;

/** A named, deterministic camera recipe. Values are deliberately exposed for tuning. */
public enum CameraProfile {
    VGA_2001("VGA 2001", "640x480 • CMOS", 1.08f, 0.88f, 17, 0.24f, 0.20f, 3, 0.16f, 0xA5B0C5),
    CCD_2005("CCD 2005", "warm compact", 1.16f, 1.08f, 8, 0.12f, 0.16f, 2, 0.12f, 0xD4C49B),
    FILM_1996("FILM 1996", "soft negative", 1.05f, 0.82f, 12, 0.08f, 0.28f, 1, 0.20f, 0xE8C68A),
    TOY_1988("TOY 1988", "toy camera", 1.22f, 0.70f, 22, 0.18f, 0.34f, 4, 0.38f, 0xD7B56D),
    NIGHT_2003("NIGHT 2003", "flash / night", 1.24f, 0.92f, 25, 0.30f, 0.12f, 2, 0.10f, 0xC7A5D8);

    public final String label;
    public final String subtitle;
    public final float contrast;
    public final float saturation;
    public final int grain;
    public final float warmth;
    public final float vignette;
    public final int pixelBlock;
    public final float halation;
    public final int accentRgb;

    CameraProfile(String label, String subtitle, float contrast, float saturation,
                  int grain, float warmth, float vignette, int pixelBlock,
                  float halation, int accentRgb) {
        this.label = label;
        this.subtitle = subtitle;
        this.contrast = contrast;
        this.saturation = saturation;
        this.grain = grain;
        this.warmth = warmth;
        this.vignette = vignette;
        this.pixelBlock = pixelBlock;
        this.halation = halation;
        this.accentRgb = accentRgb;
    }
}
