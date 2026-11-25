# Lifelog (locale)

App Android che ascolta durante la giornata, tiene **solo i momenti in cui qualcuno parla** e li trascrive **sul telefono**, senza cloud. Pensata per Samsung Galaxy S23 Ultra, funziona su Android 10 o più recente.

## Come si installa (senza programmare)

1. Dal telefono apri questa pagina su GitHub (serve essere loggati, il repository è privato).
2. Tocca **Releases** (a destra, o in fondo alla pagina) e apri l'ultima versione, es. "Lifelog v3".
3. Sotto "Assets" tocca il file **lifelog-vN.apk** per scaricarlo.
4. Apri il file scaricato. Android ti chiederà di permettere l'installazione da questa fonte (Chrome o "File personali"): consenti e torna indietro, poi tocca **Installa**.
5. Se compare "Play Protect non riconosce questa app", tocca **Altri dettagli → Installa comunque**. È normale per le app non prese dal Play Store.
6. Apri **Lifelog** e segui i 4 passi della schermata "Configurazione".

**Aggiornamenti:** scarica il nuovo APK dalle Releases e installalo sopra il vecchio. Le trascrizioni restano. Non disinstallare l'app, altrimenti perdi tutto.

## Cosa fa

- **Registrazione tutto il giorno** in un servizio in primo piano (c'è sempre una notifica "Lifelog sta ascoltando").
- **Rilevatore di voce (Silero VAD)**: il silenzio e i rumori vengono buttati subito; si salvano solo i pezzi di parlato.
- **Trascrizione sul telefono** con NVIDIA **Parakeet TDT 0.6B v3** (multilingue, italiano compreso) tramite [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (licenza Apache-2.0). Il modello (~490 MB) si scarica una volta sola al primo avvio.
- **Non scalda il telefono**: di base trascrive solo quando è in carica e la carica ha raggiunto l'80% (80 e non 85, perché la "Protezione batteria" Samsung spesso si ferma all'80%). Usa 2 thread a bassa priorità e si mette in pausa se la batteria supera 37,5 °C o se Android segnala che il telefono è caldo. C'è un pulsante "Trascrivi ora" per non aspettare.
- **L'audio viene cancellato appena trascritto.** Resta solo il testo.
- **Watchdog**: se Samsung chiude l'app, al rientro salva il "buco" (es. "non registrato 10:12–10:40") e ti manda una notifica con i minuti persi.
- **Elenco per giorno, ricerca, export** in Markdown o JSON (per farlo leggere poi a Claude o ChatGPT).

## Limiti da sapere (onestamente)

- **Android non permette di riaccendere il microfono da solo.** Da Android 14 un'app in sottofondo non può riavviare il microfono senza che tu la tocchi. Quindi se Samsung la chiude, l'app non riparte da sola: ti avvisa con una notifica e basta toccarla. Le impostazioni Samsung del passo 4 servono a far succedere questo il meno possibile.
- Dopo un **riavvio del telefono** o un **aggiornamento dell'app** devi toccare la notifica per riprendere (stesso motivo).
- La trascrizione automatica sbaglia, soprattutto con rumore, più persone che si sovrappongono, nomi propri. Non distingue chi sta parlando.
- La batteria: ascoltare tutto il giorno consuma. Va misurato sul tuo telefono nei primi giorni.
- Prima versione non ancora provata su un telefono vero: aspettati qualche bug da correggere.

## Avvertenza legale

Registrare conversazioni di altre persone senza che lo sappiano è rischioso in Italia e nell'UE (GDPR, diritto alla riservatezza; diffondere quelle registrazioni può essere reato). Usala per te, avvisa chi parla con te e non condividere le trascrizioni di altri.

## Prossimi passi

- Server MCP che legge l'export, così Claude/ChatGPT possono rispondere a domande sul tuo passato.
- Export automatico giornaliero in una cartella.

## Per sviluppatori

- Kotlin + Jetpack Compose, `minSdk 29`, `targetSdk 35`, solo `arm64-v8a`.
- `./scripts/fetch-deps.sh` scarica l'AAR di sherpa-onnx 2.0.3 (verificato con SHA-256), poi `./gradlew :app:assembleRelease`.
- La GitHub Action `Build APK` compila a ogni push su `main` e pubblica l'APK nelle Releases.
- La chiave di firma `app/lifelog.keystore` è nel repository di proposito: serve solo per installare gli aggiornamenti sopra le versioni precedenti (uso personale). Non usarla per pubblicare sul Play Store.
- Codice originale. Il progetto `mtib/android-local-transcribe` è stato usato solo come idea di riferimento: non ha licenza, quindi non ne è stato copiato codice.
