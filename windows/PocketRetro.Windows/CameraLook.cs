namespace PocketRetro.Windows;

public enum CameraKind { VGA_2001, CCD_2005, FILM_1996, TOY_1988, NIGHT_2003 }

public sealed record CameraLook(
    CameraKind Kind, string Title, string Subtitle,
    double Contrast, double Saturation, double Warmth,
    double Vignette, int Grain, int PixelBlock, double Halation)
{
    public static CameraLook For(CameraKind kind) => kind switch
    {
        CameraKind.CCD_2005 => new(kind, "CCD 2005", "warm compact / clipped highlights", 1.16, 1.08, 0.42, 0.12, 8, 2, 0.22),
        CameraKind.FILM_1996 => new(kind, "FILM 1996", "soft negative / amber print", 1.05, 0.82, 0.68, 0.20, 12, 1, 0.15),
        CameraKind.TOY_1988 => new(kind, "TOY 1988", "toy lens / strong vignette", 1.22, 0.70, 0.36, 0.38, 22, 4, 0.34),
        CameraKind.NIGHT_2003 => new(kind, "NIGHT 2003", "flash / night CMOS", 1.24, 0.92, 0.08, 0.10, 25, 2, 0.18),
        _ => new(kind, "VGA 2001", "640×480 / early CMOS", 1.08, 0.88, 0.20, 0.16, 17, 3, 0.16)
    };
}
