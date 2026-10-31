# Videóvágó – veszteségmentes, offline

Videóvágó és -forgató, ami **nem kódolja újra a videót**: a kimenetben bitre ugyanazok a
képkockák vannak, mint az eredetiben. 4K 60 fps, HDR, 10 bites, H.264, HEVC, AV1, ProRes
anyagnál is ugyanolyan gyors: egy 20 GB-os fájl kivágása is csak annyi ideig tart, amennyi a
lemezre íráshoz kell.

Internet nem kell hozzá. A felület a böngészőben fut, de csak a saját gépeden
(`127.0.0.1`), a videó sehová nem töltődik fel.

## Telepítés

1. **Python 3.8+** – https://www.python.org/downloads/ (Windowson pipáld be az „Add to PATH” opciót).
2. **ffmpeg** (az `ffprobe`-bal együtt) – https://ffmpeg.org/download.html
   - Windows: töltsd le pl. a gyan.dev „ffmpeg-release-essentials” csomagot, és az
     `ffmpeg.exe`-t meg az `ffprobe.exe`-t másold a `videovago.py` mellé (vagy tedd a PATH-ba).
   - macOS: `brew install ffmpeg`
   - Linux: `sudo apt install ffmpeg`

   A forgatáshoz ffmpeg 6.0 vagy újabb ajánlott (régebbinél a régi `rotate` címkét használja).

Más nem kell, a program csak a Python beépített moduljait használja.

## Indítás

- Windows: dupla kattintás az `inditas.bat`-ra, vagy húzz rá egy videót.
- macOS / Linux: `./inditas.sh` vagy `./inditas.sh video.mp4`

Megnyílik a böngésző. Ha nem, másold be a konzolban kiírt linket.

## Használat

1. **Megnyitás** – válaszd ki a videót, vagy illeszd be a teljes útvonalát.
2. Keresd meg a kezdőpontot, nyomd meg az **I**-t, keresd meg a végpontot, nyomd meg az **O**-t.
3. **Enter** – a kijelölés felkerül a megtartott szakaszok közé. Több szakaszt is felvehetsz;
   ezeket egy fájlba fűzi, vagy külön fájlokba menti.
   A **Megfordítás** gomb a szakaszok *közötti* részeket tartja meg – így lehet részeket kivágni.
4. Forgatás: **↻ 90°** / **↺ 90°** / **180°** (vagy az **R** billentyű).
5. **Exportálás újrakódolás nélkül.**

| Billentyű | Funkció |
|---|---|
| Szóköz | lejátszás / szünet |
| ← / → | egy képkocka vissza / előre |
| Shift + ← / → | 1 másodperc |
| ↑ / ↓ | előző / következő kulcskocka |
| I / O | kezdőpont / végpont |
| Enter | szakasz hozzáadása |
| Del | kijelölt szakasz törlése |
| R / Shift+R | forgatás jobbra / balra |
| Görgő az idővonalon, + / − | nagyítás |
| Ctrl+O / Ctrl+E | megnyitás / exportálás |

## Hogyan lesz veszteségmentes?

**Vágás.** Az ffmpeg `-c copy` módban dolgozik: a tömörített képkockákat egy az egyben
átmásolja az új fájlba, nem bontja ki és nem tömöríti újra őket. Ennek egy következménye van:
a tömörített videóban csak a **kulcskockák** (I-frame) önállóak, a többi képkocka az előzőekre
hivatkozik. Ezért a vágás eleje csak kulcskockára eshet pontosan. A program beolvassa a
kulcskockák helyét (az idővonalon a kis szürke jelek), és a kezdőpontot alapból a megelőző
kulcskockára igazítja. A végpont bármelyik képkocka lehet.

- Fényképezőgépek, telefonok: általában 0,5–2 mp-enként van kulcskocka.
- Képernyőfelvétel, erősen tömörített letöltött videó: lehet ritkább is (akár 5–10 mp).
- A ↑/↓ billentyűvel kulcskockáról kulcskockára ugorhatsz.

**Forgatás.** A képpontokat elforgatni csak újrakódolással lehetne. Ehelyett a program a
fájl *megjelenítési mátrixát* (display matrix) írja át: ez az a jelzés, amit a telefonok is
használnak az álló videóknál. A videó képkockái változatlanok maradnak, a lejátszó forgatja el
megjelenítéskor. A VLC, az mpv, a QuickTime, a böngészők, a telefonok,
a Premiere, a DaVinci Resolve és a Final Cut mind figyelembe veszi. Ez **MP4 és MOV**
formátumban működik (MKV-ban nincs szabványos helye – ilyenkor a program MP4-et ajánl, a
konténercsere is veszteségmentes).

**Ellenőrizve:** a tesztekben a kimenet minden videócsomagja bájtra egyezik a forrás megfelelő
csomagjával, és a dekódolt képkockák MD5-összege is azonos.

## Jó tudni

- **Előnézet:** a böngésző lejátssza a H.264, VP9, AV1 videókat, és a legtöbb Windows/macOS
  gépen hardveresen a HEVC-t (H.265) is. Ha egy kodeket nem tud lejátszani (pl. ProRes), a
  program állóképes előnézetre vált: az ffmpeg készít képet az aktuális pozícióról. A vágást ez
  nem befolyásolja.
- A hang- és feliratsávok is másolással kerülnek át. A fejezetjelöléseket a program elhagyja,
  mert a vágás után már nem lennének érvényesek.
- Az eredeti fájlt a program soha nem írja felül; ha a kimeneti név foglalt, `(2)`, `(3)` …
  utótagot kap.
- Biztonság: a helyi szerver csak a `127.0.0.1` címen figyel, és minden kéréshez az indításkor
  generált véletlen tokent kéri, így más weboldal nem fér hozzá a fájljaidhoz.

## Fájlok

| Fájl | Mi ez |
|---|---|
| `videovago.py` | helyi szerver: ffprobe/ffmpeg hívások, export, videó kiszolgálása |
| `index.html` | a felület (idővonal, szakaszok, forgatás, export) |
| `inditas.bat`, `inditas.sh` | indítószkriptek |
