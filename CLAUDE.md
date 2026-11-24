# RAW Viewer: note per Claude

App Android personale (Kotlin + Jetpack Compose) per sfogliare, valutare e copiare sul telefono
le foto della SD della fotocamera. L'utente scatta con una **Fujifilm** (JPEG con simulazioni pellicola + RAF),
legge la SD con un **adattatore USB-C** e scrive in italiano: UI e messaggi in italiano.

## Build

- In genere l'ambiente di sviluppo **non ha l'SDK Android** e non raggiunge Google Maven: non provare a compilare in locale.
- Ogni push su `main` viene compilato da GitHub Actions (`.github/workflows/build.yml`), che aggiorna la release `latest`
  con `raw-viewer.apk` (link fisso: `releases/latest/download/raw-viewer.apk`). Per seguire la build: `gh run list` / `gh run view <id> --log-failed`
  (usa le API REST, GraphQL non è disponibile).
- Versioni fissate e testate insieme: AGP 8.8.0, Kotlin 2.1.0, Compose BOM 2025.01.00, telephoto 0.15.1, compileSdk 35, minSdk 29.
- Firma: secrets `KEYSTORE_B64` + `KEYSTORE_PASSWORD`. Senza secrets usa la chiave di debug del runner, che cambia a ogni build.
- NDK: LibRaw 0.21.3 scaricata da CMake (`app/src/main/cpp`), solo `arm64-v8a`, con OpenMP.

## Architettura (`app/src/main/java/com/hsteex/rawviewer`)

- `data/Scanner.kt`: scansione SAF ricorsiva, UNA query per cartella (niente DocumentFile). Raggruppa JPEG/HEIF + RAW
  per cartella e nome. Chiave scatto = `volume:percorso/nome` in minuscolo (stabile, usata dai voti).
- `data/MetaParser.kt`: parser proprio di JPEG/EXIF/MPF/RAF (letture posizionali). Estrae metadati, simulazione
  pellicola Fuji dalla MakerNote, miniatura EXIF, anteprima MPF (~1600px) e JPEG incorporato nel RAF.
- `data/ImageRepo.kt`: miniature griglia (640px, cache in memoria + su disco in `cacheDir/thumbs`), anteprime
  per il visualizzatore, sorgenti `SubSamplingImageSource` (telephoto) per lo zoom, RAW sviluppato in cache.
  **Principio chiave: non decodificare mai il file intero se c'è un'anteprima incorporata.**
- `data/RawDecoder.kt` + `cpp/rawdecoder.cpp`: LibRaw via JNI (half-size, WB di macchina, sRGB 8 bit).
- `data/Db.kt`: SQLite (voti, cache metadati, cartelle recenti). `data/Prefs.kt`: impostazioni.
- `data/ImportWorker.kt`: copia con WorkManager in `Pictures/RAW Viewer/<data>/` tramite MediaStore, salta i duplicati.
- `data/Library.kt`: filtri, ordinamento, raffiche.
- `MainViewModel.kt`: stato unico, navigazione a stack (`Screen`), voti con annulla.
- `ui/`: Home, Settings, Browser (griglia + filtri + selezione), Viewer (pager, zoom, stelle, ✓/✗, swipe,
  toggle JPEG/RAW), Compare.

## Decisioni prese con l'utente

- **Mai cancellare file dalla SD** (per ora non vuole nemmeno l'opzione).
- Voti solo nel DB interno (niente XMP per ora).
- Nel visualizzatore si parte da JPEG; RAW solo con il toggle.
- Tema scuro.

## Stato

L'app compila (build 3) ma **non era ancora stata provata su un dispositivo** al momento del passaggio a Code.
Le prime segnalazioni dell'utente arriveranno dai test con la sua SD.
