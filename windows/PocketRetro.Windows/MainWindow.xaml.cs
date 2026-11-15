using Microsoft.Win32;
using OpenCvSharp;
using OpenCvSharp.WpfExtensions;
using CvSize = OpenCvSharp.Size;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace PocketRetro.Windows;

public partial class MainWindow : System.Windows.Window
{
    private readonly object _frameGate = new();
    private readonly object _writerGate = new();
    private VideoCapture? _capture;
    private VideoWriter? _writer;
    private Mat? _latestFrame;
    private CancellationTokenSource? _cameraCancellation;
    private volatile bool _isRecording;
    private volatile bool _isClosing;
    private bool _videoMode;
    private CameraLook _selectedLook = CameraLook.For(CameraKind.VGA_2001);

    private static readonly CvSize[] OutputSizes =
    {
        new CvSize(640, 480), new CvSize(800, 600), new CvSize(1024, 768),
        new CvSize(1280, 960), new CvSize(1600, 1200)
    };

    public MainWindow() => InitializeComponent();

    private void Window_Loaded(object sender, RoutedEventArgs e) => StartCamera();

    private void StartCamera()
    {
        try
        {
            _capture = new VideoCapture(0, VideoCaptureAPIs.ANY);
            if (!_capture.IsOpened())
            {
                _capture.Dispose();
                _capture = null;
                CameraStateText.Text = "NO WEBCAM";
                Status("NO LIVE CAMERA // IMPORT A FILE TO START");
                return;
            }
            _cameraCancellation = new CancellationTokenSource();
            CameraStateText.Text = "LIVE CAMERA";
            Status("CAMERA ONLINE // " + _selectedLook.Title);
            _ = Task.Run(() => CameraLoop(_cameraCancellation.Token));
        }
        catch (Exception ex)
        {
            CameraStateText.Text = "CAMERA ERROR";
            Status("CAMERA INIT FAILED // " + ex.Message);
        }
    }

    private void CameraLoop(CancellationToken token)
    {
        using var frame = new Mat();
        while (!token.IsCancellationRequested && !_isClosing)
        {
            try
            {
                if (_capture is null || !_capture.Read(frame) || frame.Empty())
                {
                    Thread.Sleep(60);
                    continue;
                }
                lock (_frameGate)
                {
                    _latestFrame?.Dispose();
                    _latestFrame = frame.Clone();
                }

                var size = CurrentOutputSize();
                var look = _selectedLook;
                var options = Dispatcher.Invoke(() => (Flash: FlashCheck.IsChecked == true, Stamp: DateStampCheck.IsChecked == true));
                using var display = RetroEngine.Apply(frame, look, size, false, options.Flash);
                var bitmap = BitmapSourceConverter.ToBitmapSource(display);
                bitmap.Freeze();
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    if (_isClosing) return;
                    PreviewImage.Source = bitmap;
                    EmptyCameraMessage.Visibility = Visibility.Collapsed;
                }), DispatcherPriority.Render);

                if (_isRecording)
                {
                    using var recorded = RetroEngine.Apply(frame, look, size, options.Stamp, options.Flash);
                    lock (_writerGate)
                    {
                        if (_isRecording && _writer is not null && _writer.IsOpened()) _writer.Write(recorded);
                    }
                }
            }
            catch (ObjectDisposedException) { break; }
            catch (Exception ex)
            {
                Dispatcher.BeginInvoke(new Action(() => Status("PREVIEW WARNING // " + ex.Message)));
                Thread.Sleep(150);
            }
        }
    }

    private CvSize CurrentOutputSize()
    {
        var index = Dispatcher.CheckAccess()
            ? Math.Clamp(ResolutionCombo.SelectedIndex, 0, OutputSizes.Length - 1)
            : Dispatcher.Invoke(() => Math.Clamp(ResolutionCombo.SelectedIndex, 0, OutputSizes.Length - 1));
        return OutputSizes[index];
    }

    private string PhotoFolder()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "PocketRetro", "Photos");
        Directory.CreateDirectory(folder);
        return folder;
    }

    private string VideoFolder()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "PocketRetro");
        Directory.CreateDirectory(folder);
        return folder;
    }

    private void CaptureButton_Click(object sender, RoutedEventArgs e)
    {
        if (_videoMode)
        {
            if (_isRecording) StopRecording(); else StartRecording();
            return;
        }
        SavePhotoFromCamera();
    }

    private void SavePhotoFromCamera()
    {
        Mat? frame;
        lock (_frameGate) frame = _latestFrame?.Clone();
        if (frame is null || frame.Empty())
        {
            Status("NO CAMERA FRAME // CONNECT A WEBCAM OR IMPORT A PHOTO");
            frame?.Dispose();
            return;
        }
        using (frame)
        using (var developed = RetroEngine.Apply(frame, _selectedLook, CurrentOutputSize(), DateStampCheck.IsChecked == true,
                   FlashCheck.IsChecked == true))
        {
            var path = Path.Combine(PhotoFolder(), $"PR_{DateTime.Now:yyyyMMdd_HHmmss_fff}.jpg");
            Cv2.ImWrite(path, developed, new[] { new ImageEncodingParam(ImwriteFlags.JpegQuality, 92) });
            ShowMat(developed);
            Status("PHOTO SAVED // " + path);
        }
    }

    private void StartRecording()
    {
        Mat? sample;
        lock (_frameGate) sample = _latestFrame?.Clone();
        if (sample is null || sample.Empty())
        {
            sample?.Dispose();
            Status("NO CAMERA FRAME // VIDEO CANNOT START");
            return;
        }
        using (sample)
        {
            var size = CurrentOutputSize();
            var output = Path.Combine(VideoFolder(), $"PR_{DateTime.Now:yyyyMMdd_HHmmss}.avi");
            lock (_writerGate)
            {
                _writer?.Dispose();
                _writer = new VideoWriter(output, FourCC.MJPG, 20.0, size, true);
                if (!_writer.IsOpened())
                {
                    _writer.Dispose();
                    _writer = null;
                    Status("VIDEO WRITER NOT AVAILABLE // TRY ANOTHER PC");
                    return;
                }
                _isRecording = true;
            }
            CaptureButton.Content = "■   STOP RECORDING";
            CaptureButton.Background = System.Windows.Media.Brushes.IndianRed;
            Status("● RECORDING // " + Path.GetFileName(output));
        }
    }

    private void StopRecording()
    {
        lock (_writerGate)
        {
            _isRecording = false;
            _writer?.Release();
            _writer?.Dispose();
            _writer = null;
        }
        CaptureButton.Content = _videoMode ? "●   START RECORDING" : "◎   TAKE PHOTO";
        CaptureButton.ClearValue(System.Windows.Controls.Control.BackgroundProperty);
        Status("VIDEO SAVED // " + VideoFolder());
    }

    private void PhotoMode_Click(object sender, RoutedEventArgs e)
    {
        if (_isRecording) StopRecording();
        _videoMode = false;
        PhotoModeButton.Background = (System.Windows.Media.Brush)FindResource("AccentBrush");
        PhotoModeButton.Foreground = System.Windows.Media.Brushes.Black;
        VideoModeButton.ClearValue(System.Windows.Controls.Control.BackgroundProperty);
        CaptureButton.Content = "◎   TAKE PHOTO";
        Status("PHOTO MODE // " + _selectedLook.Title);
    }

    private void VideoMode_Click(object sender, RoutedEventArgs e)
    {
        _videoMode = true;
        VideoModeButton.Background = (System.Windows.Media.Brush)FindResource("AccentBrush");
        VideoModeButton.Foreground = System.Windows.Media.Brushes.Black;
        PhotoModeButton.ClearValue(System.Windows.Controls.Control.BackgroundProperty);
        CaptureButton.Content = "●   START RECORDING";
        Status("VIDEO MODE // MJPEG AVI // LOW RESOLUTION");
    }

    private void SelectCamera_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.Button button || button.Tag is not string name ||
            !Enum.TryParse<CameraKind>(name, out var kind)) return;
        _selectedLook = CameraLook.For(kind);
        SelectedProfileText.Text = _selectedLook.Title.ToUpperInvariant() + "  /  " + _selectedLook.Subtitle.ToUpperInvariant();
        Status("CAMERA SELECTED // " + _selectedLook.Title);
    }

    private void FlashCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (IsLoaded) Status(FlashCheck.IsChecked == true ? "FLASH LOOK // BRIGHT HIGHLIGHTS" : "FLASH LOOK OFF");
    }

    private void ImportPhoto_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Choose a photo to develop",
            Filter = "Image files|*.jpg;*.jpeg;*.png;*.bmp;*.webp;*.tif;*.tiff|All files|*.*",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            using var source = Cv2.ImRead(dialog.FileName, ImreadModes.Color);
            if (source.Empty()) throw new InvalidDataException("The selected image could not be decoded.");
            using var developed = RetroEngine.Apply(source, _selectedLook, CurrentOutputSize(),
                DateStampCheck.IsChecked == true, FlashCheck.IsChecked == true);
            var output = Path.Combine(PhotoFolder(), $"PR_IMPORT_{DateTime.Now:yyyyMMdd_HHmmss_fff}.jpg");
            Cv2.ImWrite(output, developed, new[] { new ImageEncodingParam(ImwriteFlags.JpegQuality, 92) });
            ShowMat(developed);
            Status("IMPORTED PHOTO DEVELOPED // " + output);
        }
        catch (Exception ex) { Status("PHOTO IMPORT FAILED // " + ex.Message); }
    }

    private async void ImportVideo_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Choose a video to develop",
            Filter = "Video files|*.mp4;*.mov;*.avi;*.mkv;*.wmv;*.m4v|All files|*.*",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;
        var inputPath = dialog.FileName;
        var outputPath = Path.Combine(VideoFolder(), $"PR_IMPORT_{DateTime.Now:yyyyMMdd_HHmmss}.avi");
        var look = _selectedLook;
        var size = CurrentOutputSize();
        var stamp = DateStampCheck.IsChecked == true;
        var flash = FlashCheck.IsChecked == true;
        CaptureButton.IsEnabled = false;
        Status("DEVELOPING VIDEO // " + Path.GetFileName(inputPath));
        try
        {
            var processedFrames = await Task.Run(() => ProcessVideoFile(inputPath, outputPath, look, size, stamp, flash));
            Status($"VIDEO DEVELOPED // {processedFrames:N0} FRAMES // {outputPath}");
        }
        catch (Exception ex) { Status("VIDEO PROCESSING FAILED // " + ex.Message); }
        finally { CaptureButton.IsEnabled = true; }
    }

    private static long ProcessVideoFile(string inputPath, string outputPath, CameraLook look,
        CvSize size, bool dateStamp, bool flash)
    {
        using var input = new VideoCapture(inputPath);
        if (!input.IsOpened()) throw new InvalidDataException("The video format is not supported by this OpenCV build.");
        var fps = input.Fps;
        if (double.IsNaN(fps) || fps < 1 || fps > 120) fps = 24;
        using var writer = new VideoWriter(outputPath, FourCC.MJPG, fps, size, true);
        if (!writer.IsOpened()) throw new IOException("Could not create the output video.");
        using var frame = new Mat();
        long count = 0;
        while (input.Read(frame) && !frame.Empty())
        {
            using var developed = RetroEngine.Apply(frame, look, size, dateStamp, flash);
            writer.Write(developed);
            count++;
        }
        if (count == 0) throw new InvalidDataException("The video contains no readable frames.");
        return count;
    }

    private void ShowMat(Mat mat)
    {
        var bitmap = BitmapSourceConverter.ToBitmapSource(mat);
        bitmap.Freeze();
        PreviewImage.Source = bitmap;
        EmptyCameraMessage.Visibility = Visibility.Collapsed;
    }

    private void Status(string message)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(new Action(() => Status(message)));
            return;
        }
        StatusText.Text = message;
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        _isClosing = true;
        if (_isRecording) StopRecording();
        _cameraCancellation?.Cancel();
        _capture?.Release();
        _capture?.Dispose();
        lock (_frameGate)
        {
            _latestFrame?.Dispose();
            _latestFrame = null;
        }
        lock (_writerGate)
        {
            _writer?.Dispose();
            _writer = null;
        }
        _cameraCancellation?.Dispose();
    }
}
