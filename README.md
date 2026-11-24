# RAW Viewer

App Android per **sfogliare, valutare e scaricare** le foto della SD della fotocamera (JPEG + RAW), pensata per essere veloce.

## Installazione

Ogni push su `main` viene compilato da GitHub Actions. L'APK aggiornato si scarica dalla release
[**latest**](../../releases/tag/latest).

## Perché è veloce

- **Scansione**: una sola query SAF per cartella con tutte le colonne (niente `DocumentFile`).
- **Miniature**: non decodifica mai il file intero. Legge l'anteprima JPEG incorporata
  (MPF ~1600 px nei JPEG Fujifilm, oppure il JPEG dentro il RAF): circa 1 MB invece di 15–50 MB.
  Le miniature finiscono in cache su disco e in memoria, e vengono generate in background per tutta la scheda.
- **Visualizzatore**: mostra subito l'anteprima grande e poi carica a tile il JPEG a piena risoluzione per lo zoom al 100%.

## Funzioni

- Scelta della cartella con lettura ricorsiva delle sottocartelle
- JPEG/HEIF e RAW con lo stesso nome raggruppati in un solo scatto (`RAF+JPG`)
- Voti: stelle 1–5, ✓ Scarica / ✗ Scarta, swipe su/giù, avanzamento automatico, annulla
- Filtri (stato, stelle, tipo, cartella), ordinamento per data o nome
- Raffiche raggruppate in pile; confronto affiancato di due scatti
- Info di scatto, compresa la simulazione pellicola Fujifilm
- Selezione multipla e "seleziona tutti i filtrati"
- Condivisione
- Copia sul telefono di JPEG, RAW o entrambi, in `Immagini/RAW Viewer/<data>`, in background e saltando i duplicati

L'app **non cancella mai nulla** dalla SD.

## Firma

La CI firma la release con il keystore nei secrets `KEYSTORE_B64` (keystore in base64) e `KEYSTORE_PASSWORD`.
Senza questi secrets usa una chiave di debug, che cambia a ogni build.
