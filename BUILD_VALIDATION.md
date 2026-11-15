# Build validation status

- XML files (Android manifest, Android theme resources, WPF XAML and WPF application XAML) were parsed and found well-formed.
- Java and C# source files passed basic delimiter/structure checks.
- Project and dependency versions were checked against current AndroidX / CameraX / .NET / OpenCvSharp documentation on 15 November 2026.
- A full Android APK build, Windows .EXE build, camera permission test and physical-device capture test were **not run in this workspace**: Android SDK / Gradle and the .NET SDK are not installed in the environment.

Please use the build steps in `README_RU.md` on a machine with the listed toolchains. Camera support, video codecs and sound capture still need to be verified on the actual target devices.
