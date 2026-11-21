# LoopBox media discovery / file-check / download / conversion module

This ZIP contains Kotlin source files only. Copy them into the Android app; it is not a complete APK and does not modify your repository automatically.

## 1. Copy the files

Copy all five `.kt` files into:

`app/src/main/java/com/example/myapplication/mediacatalog/`

The package declaration is already set to:

`com.example.myapplication.mediacatalog`

If your app uses a different package, change the package line in each Kotlin file.

## 2. Add dependencies

In `app/build.gradle` (Module: app), add the dependencies below. Keep your existing versions aligned with the versions already used by your project; do not add duplicate dependency declarations.

```gradle
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
}
```

The app must already have Internet permission in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 3. Create the scraper and repository

Example in your Koin module:

```kotlin
single { LoopBoxMediaScraper() }
single { LoopBoxHomeDiscoveryRepository(get()) }
single { LoopBoxMediaFileChecker() }
single { LoopBoxMediaDownloadService() }
single { LoopBoxVideoToAudioConverter(androidContext()) }
```

If your project does not use Koin, instantiate these classes directly.

## 4. Load the Home feed

Call this from a ViewModel coroutine, not from the Composable body:

```kotlin
val sections = repository.refreshHome()
val music = sections[LoopBoxMediaKind.MUSIC].orEmpty()
val movies = sections[LoopBoxMediaKind.MOVIE].orEmpty()
val series = sections[LoopBoxMediaKind.SERIES].orEmpty()
```

Render each list in your existing Home UI. `artworkUrl`, `title`, `description`, `year`, and `source` are metadata. Do not show a play/download control just because a metadata item exists.

## 5. Check a file before showing Stream / Download

For each `LoopBoxMediaFile` attached to a result:

```kotlin
val check = fileChecker.check(file)
if (check.playable) {
    // Show Stream and Download controls.
} else {
    // Keep the catalogue card, but hide Stream and Download.
}
```

For streaming, use `check.finalUrl` as the media URI with your existing Media3 ExoPlayer. Do not stream `detailUrl` or a metadata page URL.

## 6. Download a verified file

Only call the downloader after the background check succeeds:

```kotlin
val saved = downloadService.download(
    file = file,
    checked = check,
    destination = File(context.getExternalFilesDir("Music"), "downloaded-track.mp3")
)
```

Use a filename and extension that match the actual selected file. For a production app, run long downloads through WorkManager and publish progress/notifications. This starter service performs a foreground coroutine download and does not yet provide resumable downloads or progress callbacks.

## 7. Convert a local/downloaded video to audio

```kotlin
val audioFile = converter.extractToM4a(
    inputVideo = Uri.fromFile(videoFile),
    outputM4a = File(context.getExternalFilesDir("Music"), "extracted-audio.m4a")
)
```

Use a local file URI or a content URI the app can read. Output is AAC audio in an M4A container, not MP3.

## What the scraper actually returns

- **Music:** iTunes search metadata plus Internet Archive audio items. iTunes previews are metadata/preview links and are deliberately not treated as downloadable full tracks.
- **Movies:** Cinemeta metadata plus Internet Archive movie records.
- **TV series:** TVmaze metadata plus relevant Internet Archive records.
- **Playable/downloadable:** only direct Internet Archive media files where the item exposes a recognised Creative Commons or public-domain licence and the file passes the background HTTP check.

Many catalogue entries will have no downloadable file. That is intentional: metadata availability does not mean a full media file is legally available. Unknown licences are not marked as permitted.

## Important implementation notes

- This is a source module, not a claim that your existing app has already been integrated or built.
- Internet Archive metadata and catalogue endpoints can change or rate-limit requests.
- Cinemeta and TVmaze are metadata sources; they do not grant rights to stream or download films/series.
- The licence check is conservative but is not legal advice. Confirm licence terms and attribution requirements before distributing media.
- This does not scrape hidden player streams, defeat DRM, bypass paywalls, or log in to subscription platforms.
- The `loadHomePage()` function currently performs a fresh network discovery each time. Add caching in your repository/ViewModel for a faster Home screen.
