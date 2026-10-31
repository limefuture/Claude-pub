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
4. Válassz a három felkínált betűszín közül, és ha kell, állítsd a feliratok méretét.
5. Ha kell, az előnézeteken húzással formátumonként igazíthatod a kép kivágását.
6. Töltsd le a képeket egyenként, vagy egyben ZIP-ben (JPG vagy PNG).

## Mit vesz át a moodboardból

- **Alapváz** a jóváhagyott Vandalism-flyerekről mérve (2026. október és november): logó a dobócsillaggal jobbra fent, alatta a körbe írt nap, a kör két oldalán egyforma magas, álló oszlopban a hónapnév és a バンダリズム, alatta a jobbra zárt line-up. A V✦ infóblokk 4:5-ön és 9:16-on bal lent, 16:9-en bal fent van.
- **Ragyogás**: a logó, a dátum és a line-up egy rétegen van, és együtt ragyog a kiemelő színnel.
- **Éjszakai tónus**: a háttérkép színeit tompítja, hideg kék árnyalatot ad neki, és sötétkékre emeli a mélyfeketét.
- **Betűszín a háttérképből**: minden feltöltésnél három élénk szín közül lehet választani. Az elsők a kép fényforrásaiból és legszínesebb részeiből jönnek, neonos telítettségre húzva, és legalább 4:1-es kontraszttal a háttéren. A hiányzó helyeket az eddig használt színek töltik ki: jelpiros, rózsaszín, nátrium-narancs, magenta. A háttérszín a kép legsötétebb nagy felülete, a papírszín legalább 7:1-es kontrasztú, ezt kapja az infóblokk.
- **Textúra**: sötétedő szélek, átmenet a szöveges oldal felé, erős szemcse. A tónus, a ragyogás, a sötétítés és a szemcse erőssége is csúszkával állítható.
- **Feliratok mérete**: két csúszka. Az egyik a logót, a dátumot és a japán feliratot méretezi együtt, a másik a fellépők nevét. Ha a nevek nem férnek el, a méretük magától csökken.

## Fejlesztés

A forrás a `generator/src/flyer.template.html`. A build beágyazza a logókat és a mintahátteret,
így a kész fájl egyetlen önálló HTML:

```
python3 generator/build.py
```

A mintaháttér (`generator/src/sample_tunnel.webp`) a moodboard „A · alagút” vázlatából készült.
