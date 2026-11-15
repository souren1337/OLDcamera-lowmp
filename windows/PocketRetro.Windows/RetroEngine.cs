using OpenCvSharp;

namespace PocketRetro.Windows;

/// <summary>Creates low-megapixel files with early digital-camera sensor character.</summary>
public static class RetroEngine
{
    public static Mat Apply(Mat source, CameraLook look, Size outputSize, bool dateStamp, bool flashLook = false)
    {
        if (source.Empty()) throw new ArgumentException("Source frame is empty", nameof(source));
        var targetAspect = outputSize.Width / (double)outputSize.Height;
        var sourceAspect = source.Width / (double)source.Height;
        var cropWidth = source.Width;
        var cropHeight = source.Height;
        if (sourceAspect > targetAspect) cropWidth = Math.Max(1, (int)Math.Round(source.Height * targetAspect));
        else cropHeight = Math.Max(1, (int)Math.Round(source.Width / targetAspect));
        var crop = new Rect((source.Width - cropWidth) / 2, (source.Height - cropHeight) / 2, cropWidth, cropHeight);

        using var region = new Mat(source, crop);
        var work = new Mat();
        Cv2.Resize(region, work, outputSize, 0, 0, InterpolationFlags.Area);

        // Sensor sampling: merge neighbouring pixels before the final nearest-neighbour readout.
        if (look.PixelBlock > 1)
        {
            using var sampled = new Mat();
            var small = new Size(Math.Max(1, outputSize.Width / look.PixelBlock),
                                 Math.Max(1, outputSize.Height / look.PixelBlock));
            Cv2.Resize(work, sampled, small, 0, 0, InterpolationFlags.Area);
            Cv2.Resize(sampled, work, outputSize, 0, 0, InterpolationFlags.Nearest);
        }

        work.ConvertTo(work, work.Type(), look.Contrast, 127.0 * (1.0 - look.Contrast));
        // Blend against grayscale for saturation, then bias BGR channels for warmth.
        using (var gray = new Mat())
        using (var grayBgr = new Mat())
        {
            Cv2.CvtColor(work, gray, ColorConversionCodes.BGR2GRAY);
            Cv2.CvtColor(gray, grayBgr, ColorConversionCodes.GRAY2BGR);
            Cv2.AddWeighted(work, look.Saturation, grayBgr, 1.0 - look.Saturation, 0, work);
        }
        var channels = Cv2.Split(work);
        try
        {
            // OpenCV channel order is BGR: warm profiles lift red and pull blue down.
            channels[0].ConvertTo(channels[0], channels[0].Type(), 1.0, -look.Warmth * 23.0);
            channels[1].ConvertTo(channels[1], channels[1].Type(), 1.0, look.Warmth * 4.0);
            channels[2].ConvertTo(channels[2], channels[2].Type(), 1.0, look.Warmth * 24.0);
            Cv2.Merge(channels, work);
        }
        finally
        {
            foreach (var channel in channels) channel.Dispose();
        }

        if (flashLook) Cv2.ConvertScaleAbs(work, work, 1.13, 16);

        using (var signed = new Mat())
        using (var noise = new Mat(work.Size(), MatType.CV_16SC3))
        {
            work.ConvertTo(signed, MatType.CV_16SC3);
            Cv2.Randn(noise, Scalar.All(0), Scalar.All(look.Grain));
            Cv2.Add(signed, noise, signed);
            signed.ConvertTo(work, MatType.CV_8UC3);
        }

        // Radial falloff, similar to inexpensive plastic lenses and small CMOS modules.
        using (var mask = new Mat(work.Height, work.Width, MatType.CV_32FC1))
        {
            var cx = (work.Width - 1) / 2.0;
            var cy = (work.Height - 1) / 2.0;
            for (var y = 0; y < work.Height; y++)
            {
                for (var x = 0; x < work.Width; x++)
                {
                    var dx = (x - cx) / Math.Max(1.0, cx);
                    var dy = (y - cy) / Math.Max(1.0, cy);
                    var radius2 = dx * dx + dy * dy;
                    var value = (float)Math.Clamp(1.0 - look.Vignette * radius2, 0.58, 1.0);
                    mask.Set<float>(y, x, value);
                }
            }
            var vignetteChannels = Cv2.Split(work);
            try
            {
                for (var i = 0; i < vignetteChannels.Length; i++)
                {
                    using var floatChannel = new Mat();
                    vignetteChannels[i].ConvertTo(floatChannel, MatType.CV_32FC1);
                    Cv2.Multiply(floatChannel, mask, floatChannel);
                    floatChannel.ConvertTo(vignetteChannels[i], MatType.CV_8UC1);
                }
                Cv2.Merge(vignetteChannels, work);
            }
            finally
            {
                foreach (var channel in vignetteChannels) channel.Dispose();
            }
        }

        // Mild highlight halation: bloom only the brightest regions, tinting them warm.
        if (look.Halation > 0)
        {
            using var gray = new Mat();
            using var bright = new Mat();
            using var glow = new Mat();
            Cv2.CvtColor(work, gray, ColorConversionCodes.BGR2GRAY);
            Cv2.Threshold(gray, bright, 222, 255, ThresholdTypes.Binary);
            Cv2.GaussianBlur(bright, glow, new Size(0, 0), 5.0);
            using var glowBgr = new Mat();
            Cv2.CvtColor(glow, glowBgr, ColorConversionCodes.GRAY2BGR);
            using var tint = new Mat(work.Size(), work.Type(), new Scalar(0, 18 * look.Halation, 34 * look.Halation));
            Cv2.AddWeighted(work, 1.0, tint, 0.18, 0, work);
            Cv2.AddWeighted(work, 1.0, glowBgr, 0.18 * look.Halation, 0, work);
        }

        if (dateStamp)
        {
            var stamp = DateTime.Now.ToString("MM/dd/yy");
            var scale = Math.Max(0.45, outputSize.Width / 1100.0);
            var origin = new Point(Math.Max(8, outputSize.Width / 32), outputSize.Height - Math.Max(10, outputSize.Height / 22));
            Cv2.PutText(work, stamp, new Point(origin.X + 1, origin.Y + 1), HersheyFonts.HersheySimplex,
                scale, new Scalar(30, 18, 6), Math.Max(1, outputSize.Width / 500), LineTypes.AntiAlias);
            Cv2.PutText(work, stamp, origin, HersheyFonts.HersheySimplex,
                scale, new Scalar(70, 154, 255), Math.Max(1, outputSize.Width / 500), LineTypes.AntiAlias);
        }
        return work;
    }
}
