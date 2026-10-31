# Videóvágó Androidra – veszteségmentes

Az asztali [Videóvágó](../videovago/README.md) natív Android változata. Ugyanazt tudja:
**újrakódolás nélkül** vág és forgat, akár 4K 60 fps-es HEVC/H.264 felvételt is, internet
nélkül. Az eredeti képkockák bitre változatlanul kerülnek át az új fájlba.

Android 10 vagy újabb kell hozzá. Engedélyt nem kér, külső könyvtárat nem használ (az APK kb. 1-2 MB).

## Telepítés

1. Nyisd meg a GitHubon az **Actions** fület → **Videovago Android APK** → a legutóbbi
   zöld futás → lent az **Artifacts** alatt a `Videovago-apk`. Ez egy ZIP-et tölt le, benne a
   `Videovago.apk`.
2. Másold a telefonra (vagy töltsd le eleve a telefonon), és nyisd meg. Az Android kérni fogja,
   hogy engedélyezd az „ismeretlen forrásból” telepítést annak az alkalmazásnak, amivel
   megnyitottad (pl. Fájlok, Chrome).

Az újabb buildek ugyanazzal a kulccsal vannak aláírva, így a régi fölé telepíthetők.

## Használat

1. Koppints az ollós csempére (vagy a Galériában: **Megosztás → Videóvágó**), és válassz módot:
   - **Vágás**: egy részt tartasz meg, a többi eltűnik.
   - **Több vágás**: több felesleges részt jelölsz ki, a többi összefűzve marad meg.
2. A szerkesztőben:
   - Fent a videó, alatta a **csúszka** (a törölt részek halványabbak, a határokat kis jelek mutatják).
   - `{` / `}`: a kijelölt rész eleje/vége a lejátszófej helyére kerül. `|◀` a rész elejére ugrik,
     a hangszóró az előnézet hangját némítja, a forgatás gomb 90°-ot fordít (hosszan nyomva visszaállítja).
   - **Képsáv**: a videó bélyegképei a hang hullámformájával. Több vágás módban a törölt részek pirosak,
     a kijelöltnek húzható fogantyúi vannak; a koppintás kijelöl. Két ujjal vagy a nagyítókkal nagyítható.
   - A fejléc gombjai: `+` új rész, kuka (törlés), áthúzott hangszóró (a kimenet hang nélkül),
     hullámforma be/ki, nagyítás.
   - Alatta a kijelölt rész eleje és vége a `<` `>` finomhangoló gombokkal, középen a kész videó hossza.
   - **Kimeneti beállítások**: a fájlnév, a beállítás ikonnal a hang és a külön fájlokba mentés.
   - Lent: ✕ kilépés, ⋮ (eredmény előnézete, videó adatai, súgó), ✓ **mentés**.
3. A kész MP4 a **Filmek/Videovago** mappába kerül, a végén le is játszhatod vagy megoszthatod.

## Hogyan működik

- **Vágás:** az Android saját `MediaExtractor` és `MediaMuxer` osztálya a tömörített
  képkockákat egy az egyben másolja át (ugyanaz, amit az asztali változatban az `ffmpeg -c copy`
  csinál). Ezért gyors: a sebességet csak a tároló írási sebessége korlátozza.
- **Kulcskockák:** a megtartott rész csak kulcskockánál kezdődhet. Az app beolvassa a
  kulcskockák helyét, és ezeket a széleket (Vágás módban a kezdet, Több vágás módban a törölt rész
  vége) a megelőző kulcskockára igazítja, így semmi nem vész el, amit meg akartál tartani. Ezeknél a
  széleknél a `<` `>` kulcskockánként lép, a többinél képkockánként.
- **Forgatás:** az MP4 megjelenítési mátrixába kerül (`setOrientationHint`), a képpontok
  változatlanok. A telefonok, a VLC, a böngészők és a vágóprogramok figyelembe veszik.
- **Előnézet:** a telefon hardveres dekóderével megy, így a 4K 60 fps is folyamatos.

## Korlátok

- A kimenet mindig MP4. Az MKV/WebM forrás is megnyitható, de csak akkor menthető, ha a kodek
  belefér az MP4-be (H.264, HEVC, és a legtöbb telefonon VP9/AV1 is).
- Az MP4-ben nem tárolható hangkodek (pl. PCM vagy AC-3 régebbi Androidon) kimarad; ezt az app
  jelzi.
- Egy videó- és egy hangsávot visz át (a telefonos felvételekben ennyi van). A feliratok és a
  további hangsávok kimaradnak.
- Exportálás közben az app ébren tartja a képernyőt. Ha bezárod az appot, az exportálás
  megszakad.

## Fordítás saját gépen

Android Studióval nyisd meg a `videovago-android` mappát, vagy parancssorból:

```
./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
```

Az `app/videovago.keystore` egy szándékosan nyilvános aláírókulcs (jelszó: `videovago`),
hogy a buildek egymás fölé telepíthetők legyenek. Ha Play Áruházba szánod, cseréld le a sajátodra.
