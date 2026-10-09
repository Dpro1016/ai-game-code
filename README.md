# Lecture Scribe

An Android app that transcribes lectures live, entirely on the tablet. It was built for a
Samsung Galaxy Tab S9 Ultra but runs on any 64-bit Android 10+ device.

- **Long sessions:** records for 3+ hours with the screen off or while you use other apps,
  through a foreground service plus a wake lock.
- **Offline and private:** speech recognition runs on the device using NVIDIA's Parakeet TDT
  0.6B v2 model through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). You need
  internet once to download the model, and audio never leaves the tablet.
- **Readable output:** the transcript has punctuation and capitals, with a timestamp on each
  line. New text appears a few seconds after each sentence, and never more than about
  25 seconds behind.
- **Crash-safe:** each line is written to disk as soon as it is recognized. The audio is saved
  in a format that stays playable even if the app is killed partway through.
- **Review:** tap any line to hear that moment of the lecture, search inside a lecture, and
  share the transcript as a `.txt` file or copy it.

## Install on the tablet

1. On the tablet, open
   **https://github.com/Dpro1016/ai-game-code/releases/latest/download/LectureScribe.apk**
2. Open the downloaded file. If Android asks, allow your browser to "install unknown apps",
   then tap **Install**.
3. Open **Lecture Scribe** and tap **Download speech model**. This is about 460 MB and only
   happens once, so use Wi-Fi.
4. Tap **Allow** on the "Allow background recording" card so Samsung's battery saver can't
   pause long recordings.

To update, install a newer APK over the old one. Your lectures are kept.

## Using it in class

- Tap **Start recording**, then lock the screen if you like. A notification shows the running
  timer and has a **Stop** button.
- When you stop, the remaining text finishes processing and the lecture opens.
- Sit as close to the front as you can. The microphone level bar shows whether the professor
  is coming through.
- Storage use is about 14 MB per hour with audio saving on. You can turn audio saving off.

## How it works

```
mic (16 kHz) ─┬─> AAC encoder ─> audio.aac
              └─> Silero VAD ─> Segmenter ─> queue ─> Parakeet (4 threads) ─> transcript.txt + live UI
```

`Segmenter` cuts the audio at pauses. If the speaker never pauses, it force-splits at the
quietest point so text keeps flowing. Recording and recognition run on separate threads,
so a slow moment in recognition never drops audio. The recognizer catches up afterwards.

In desktop tests on simulated lecture audio, with reverb, room noise, and mic levels down to
-52 dBFS, the word error rate was about 9%. That figure counts "30" vs "thirty" as an error,
and recognition ran at about 10x faster than real time.

## Building

Push to GitHub and the `Build Lecture Scribe` workflow builds a signed APK and publishes it as
the latest release. To build locally you need the Android SDK and JDK 17, then run
`./gradlew assembleRelease`. The sherpa-onnx AAR is downloaded and checksum-verified
automatically.

The signing key (`app/lecture-scribe.keystore`) is committed on purpose so every build can
install over the previous one. That is fine for a personal sideloaded app. Use a private key if
you ever publish it.
