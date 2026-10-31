# Vandalism flyer generátor

A `moodboard/` mappában az eredeti Vandalism moodboard van (logók, ROOTS-flyerek, színszabályok).
A `generator/index.html` erre épül: egy háttérképből, egy dátumból és a fellépők listájából
egyszerre elkészíti a flyert három formátumban.

| Formátum | Méret | Mire |
|---|---|---|
| 16:9 | 1920×1080 | Facebook-esemény, YouTube, kijelző |
| 4:5 | 1080×1350 | Instagram-poszt |
| 9:16 | 1080×1920 | Story, Reels |

## Használat

1. Nyisd meg a `generator/index.html` fájlt böngészőben. Nincs szükség szerverre, a betűtípusokat a Google Fonts tölti be.
2. Húzd be a háttérképet, vagy válaszd ki, vagy illeszd be Ctrl+V-vel.
3. Add meg a dátumot és a fellépőket (soronként egy név). Ha kell, a helyszínt és a várost is.
4. Ha kell, az előnézeteken húzással formátumonként igazíthatod a kép kivágását.
5. Töltsd le a képeket egyenként, vagy egyben ZIP-ben (JPG vagy PNG).

## Mit vesz át a moodboardból

- **Alapváz** a ROOTS-flyerekről mérve: logó a dobócsillaggal jobbra fent, körbe írt nap, függőleges hónapnév és katakana, jobbra zárt line-up, a V✦ infóblokk bal lent.
- **Paletta a háttérképből** (OKLab k-közép): a háttér a kép legsötétebb nagy felülete, a kiemelő a legerősebb fényforrás, legalább 3:1-es kontraszttal. Ezt kapja a logó, a dátum és a line-up. A papírszín legalább 7:1-es kontrasztú, ezt kapja az infóblokk. Szürke képnél, vagy ha kéred, a Jelpiros (#D52727) lép be.
- **Textúra**: sötétedő szélek, átmenet a szöveges oldal felé, szemcse. Mindhárom erőssége állítható.
- Hosszú line-upnál a betűméret magától csökken. A nevek a hónaposzlop mellé vagy alá kerülnek, attól függően, hol férnek el nagyobb betűvel.

## Fejlesztés

A forrás a `generator/src/flyer.template.html`. A build beágyazza a logókat és a mintahátteret,
így a kész fájl egyetlen önálló HTML:

```
python3 generator/build.py
```

A mintaháttér (`generator/src/sample_tunnel.webp`) a moodboard „A · alagút” vázlatából készült.
