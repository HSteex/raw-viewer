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
  **Priorità I/O**: la banda della SD è poca. Il background (indicizzazione metadati, miniature su disco) passa da
  `awaitBackgroundTurn()`: è in pausa col visualizzatore/confronto aperto e dopo ogni lettura in primo piano
  (`foreground {}`: miniature visibili, anteprime del visualizzatore). Il visualizzatore ha una coda sua (`ioViewer`).
  Nel pager solo la pagina a schermo carica la piena risoluzione (dopo 300 ms di sosta); le vicine caricano
  l'anteprima dopo quella della corrente. La piena risoluzione JPEG viene prima copiata in `cacheDir/full`
  (max 40 file / 700 MB) e telephoto la legge da file: niente letture SD durante lo zoom.
- Diagnostica: impostazione "Mostra tempi di caricamento" (overlay nel visualizzatore).
- Visualizzatore: istogramma (RGB + luminanza) e maschera bruciature (rosso ≥253, blu ≤2) da `data/ImageStats.kt`,
  calcolati sull'anteprima in memoria (sempre il JPEG). Le maschere si disegnano nel `transformedContentBounds`, quindi
  seguono lo zoom. Menu "Analisi": istogramma, bruciature, messa a fuoco (peaking = laplaciano sull'anteprima + riquadro
  del punto AF Fuji, MakerNote 0x1031, DB v4) e zoom al 100% sul punto AF. Doppio tocco = 100% (maxZoom calcolato
  dai pixel veri della foto). Comandi nel cassetto in basso (vedi Design).
- Filtri avanzati (chip "⚙ Avanzati" → bottom sheet): simulazione, obiettivo, orientamento, ISO massimo; escludono
  gli scatti senza metadati letti.
- La posizione della griglia (`LazyGridState`) vive nel ViewModel; al ritorno si porta sull'ultima foto vista.
- Confronto: striscia di foto in basso (l'elenco da cui si arriva, `Screen.Compare.keys`); un tocco mette la foto nel
  riquadro selezionato. Zoom collegato (`linkZoom`): stesso ingrandimento relativo e stesso punto in frazioni dell'immagine.
- Le anteprime incorporate Fuji (MPF, miniatura EXIF) sono 4:3 con bande nere: `decodeShot` le ritaglia al formato della
  foto (`cropToAspect` con larghezza/altezza EXIF). Miniature ora `.g3.jpg`.
- `ZoomableState.zoomTo()` vuole lo zoom FINALE rispetto ai pixel della foto (1f = 100%), non quello rispetto al fit.
- MPF: come anteprima valgono solo le voci "Large Thumbnail" (0x010001/0x010002). Negli Ultra HDR la secondaria è
  la gain map (causava il "flash" grigio). DB v3 svuota la cache metadati; miniature ora `.g2.jpg`.
- Ordinamento per data: per gli scatti non ancora indicizzati la data del file è corretta con la mediana dello scarto
  ora di scatto/data file (`Library.mtimeOffset`): sulle SD FAT è sfasata del fuso orario.
- Griglia: le celle mostrano subito la miniatura EXIF (`quickThumb`, solo per scatti già indicizzati), poi quella
  da 640px. Le miniature in background si generano partendo dalla posizione di scorrimento della griglia.
- "Scaricata": colonna `downloaded` in `ratings` (DB v2). `ImportWorker` segna ogni scatto quando tutti i suoi file
  sono sul telefono (copiati o già presenti) e toglie il ✓; il ViewModel la rilegge a ogni avanzamento della copia.
  Il banner della copia si chiude da solo se è andata bene; l'id chiuso è salvato in `Prefs.dismissedImport`.
- `data/RawDecoder.kt` + `cpp/rawdecoder.cpp`: LibRaw via JNI (half-size, WB di macchina, sRGB 8 bit).
- `data/Db.kt`: SQLite (voti, cache metadati, cartelle recenti). `data/Prefs.kt`: impostazioni.
- `data/ImportWorker.kt`: copia con WorkManager in `Pictures/RAW Viewer/<data>/` tramite MediaStore, salta i duplicati.
- `data/Library.kt`: filtri, ordinamento, raffiche.
- `MainViewModel.kt`: stato unico, navigazione a stack (`Screen`), voti con annulla.
- `ui/`: Home, Settings, Browser (griglia + filtri + selezione), Viewer (pager, zoom, stelle, ✓/✗, swipe,
  toggle JPEG/RAW), Compare.

## Design (redesign Material You)

- `ui/Theme.kt`: colori dinamici dallo sfondo (Android 12+, impostazione "Colori dallo sfondo", default sì) con
  ripiego sull'ambra; fondo quasi nero fisso. `Accent`/`Surface1`/`Surface2` sono getter @Composable sul tema:
  usarli solo dentro composable (in un DrawScope copiarli prima in una val). Colori con significato fissi:
  `PickGreen`, `RejectRed`, `StarYellow`, `DownloadedBlue`.
- Vetro opaco con Haze 1.2.2 (`hazeSource` sul contenuto, `hazeEffect` sulle barre): `frosted()` per griglia
  (intestazione + barra di selezione), `frostedDark()` (tinta nera neutra) sopra le foto: barre del visualizzatore,
  dock di voto, pillole del confronto. Mai un `hazeEffect` dentro il proprio `hazeSource`.
- Visualizzatore: in basso un cassetto di vetro (`ViewerDrawer`): da chiuso maniglia, dati di scatto e ✗ · stelle · ✓
  (bordo che si accende col voto); tirato su (o tocco sulla maniglia) analisi (istogramma, bruciature, fuoco,
  100% sul punto AF) e info scatto. JPEG/RAW resta fuori, accanto ai dati di scatto. Il trascinamento vale solo sul cassetto: sulla foto lo swipe vota.
  In alto: indietro, titolo, annulla, confronta, condividi (niente menu ⋮).
  Mockup di riferimento: artifact "RAW Viewer Redesign".
- Home e Impostazioni: liste raggruppate (`groupShape` in HomeScreen.kt; in Impostazioni piastrelle con fessura da 3 dp).

## Decisioni prese con l'utente

- **Mai cancellare file dalla SD** (per ora non vuole nemmeno l'opzione).
- Voti solo nel DB interno (niente XMP per ora).
- Nel visualizzatore si parte da JPEG; RAW solo con il toggle.
- Tema scuro.

## Stato

L'app compila (build 3) ma **non era ancora stata provata su un dispositivo** al momento del passaggio a Code.
Le prime segnalazioni dell'utente arriveranno dai test con la sua SD.
