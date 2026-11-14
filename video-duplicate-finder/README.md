# Duplikátumkereső (Android)

Egy kiválasztott mappában (almappákkal együtt) keresi az ismétlődő videókat és képeket.
Minden feldolgozás a telefonon történik: az app **nem kér internet-engedélyt**, nem küld el semmit,
és a beállításai / tárolt ujjlenyomatai ki vannak zárva a felhőmentésből.

## Telepítés

1. GitHubon: **Actions** → *Duplikátumkereső APK* → a legutóbbi sikeres futás → *Artifacts* →
   `Duplikatumkereso-apk` letöltése (zip, benne az `Duplikatumkereso.apk`).
2. A zipet csomagold ki a telefonon, és nyisd meg az APK-t. Első alkalommal engedélyezni kell az
   „Ismeretlen forrásból származó alkalmazások telepítése” lehetőséget a fájlkezelőnek.
3. Indításkor engedélyezd a **Fotók és videók → Mindig engedélyezett** hozzáférést
   (a „Kiválasztott fotók” nem elég), és az értesítéseket (folyamatjelző).

A frissítések a régi verzióra telepíthetők (azonos aláíró kulcs: `app/app-signing.keystore`).

## Négy fejezet

| Fül | Mit talál | Hogyan |
|---|---|---|
| **Pontos** | bájtra azonos videók | azonos méret → gyors részleges SHA-256 → teljes SHA-256 |
| **Felbontás** | azonos videó más felbontásban/tömörítéssel | hossz ±2 mp-en belül egyezik + a képkockák legalább fele egyezik |
| **Részlet** | egy hosszabb videóból kivágott rövidebb | **10-nél több** egyező képkocka azonos időeltolással; az idővonal mutatja a kivágott szakaszt |
| **Képek** | pontosan azonos és hasonló (átméretezett) képek | méret + hash, illetve 64 bites képujjlenyomat |

### Képkocka-összevetés

* Mintavétel: **10 másodpercenként 1 kocka** (beállítható).
* Minden kockából 9×8 pixeles szürke képből számolt 64 bites ujjlenyomat (dHash) készül –
  ez a felbontástól és a tömörítéstől szinte független.
* Két kocka egyezik, ha legfeljebb 10 bitben tér el (beállítható).
* A fekete/egyszínű kockákat (elsötétítés) kihagyja, mert azok mindennel egyeznének.
* Részletnél az egyező kockapároknak nagyjából ugyanazzal az időeltolással kell egyezniük
  (a két videó mintavétele legfeljebb fél intervallummal csúszhat el egymáshoz képest).
* 10 mp-es mintavételnél a „10-nél több egyező kocka” feltétel miatt kb. **2 percnél
  hosszabb** közös szakasz kell a részlet felismeréséhez. Rövidebb részletekhez a
  Beállításokban csökkenthető a mintavételi idő vagy az egyező kockák száma.

### Sebesség

Az első vizsgálat több ezer videónál hosszú lehet (a képkockák kiolvasása a lassú rész),
ezért előtérszolgáltatásként fut értesítéssel, a telefon lezárható. Az ujjlenyomatokat
helyi adatbázisba menti, így a következő vizsgálat csak az új/módosult fájlokat elemzi.
Megszakítás után az újraindítás onnan folytatja. Android 15+ alatt a rendszer egy
háttérfolyamatot naponta legfeljebb 6 órát enged futni – ha ez lejár, egyszerűen
indítsd újra a vizsgálatot.

## Mappa és kuka

* A mappát a rendszer mappaválasztójával lehet kijelölni (ugyanazok a mappák, mint a
  Saját fájlokban). A `Download` mappa gyökerét az Android nem engedi kiválasztani, csak
  az almappáit.
* A törlés az Android rendszerkukájába tesz (a rendszer jóváhagyást kér), 30 napig
  visszaállítható. Hogy ez a gyártói fájlkezelő Kuka mappájában is látszik-e, a
  telefontól függ – az app saját **Kuka** képernyőjén (kezdőképernyő, kuka ikon) mindig
  visszaállítható vagy véglegesen törölhető.

## Fejlesztés

Kotlin + Jetpack Compose, minSdk 30 (Android 11), targetSdk 35.
A párosító logika (`core/`) Android nélkül tesztelhető: `gradle testReleaseUnitTest`.
Az APK-t a `.github/workflows/video-duplicate-finder.yml` építi minden pushnál.
