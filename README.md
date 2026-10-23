# Úkolníček 1.5

Úkolníček pro Android: úkol, **kdy to nastane**, **kdy upozornit** a kanály upozornění
(notifikace, WhatsApp, e-mail na přednastavenou adresu).

## Ovládání
- **+** přidá úkol
  - *Kdy to nastane*: datum a čas události
  - *Kdy upozornit*: v čas události, 5/15/30 min, 1–2 h, 1–2 dny, týden předem, nebo **vlastní datum a čas**
    (aplikace si pamatuje poslední volbu)
  - *Upozornit přes*: notifikace / WhatsApp / e-mail (libovolná kombinace)
- **mikrofon** (malé tlačítko nad +) = nadiktuj úkol, např. „zítra v 8 zavolat do servisu“.
  Datum a čas se nastaví jako čas události. Rozumí: dnes, zítra, pozítří, v pondělí…v neděli,
  za 20 minut / za hodinu / za 3 dny, v 8, v 15:30, ráno, dopoledne, odpoledne, večer.
- **klepnutí** na úkol = úprava, **zaškrtávátko** = hotovo (zruší upomínku), **dlouhé podržení** = smazat
- menu **⋮** → Nastavení (WhatsApp, e-mail) / Smazat hotové

## Jak získat APK
1. Repozitář na GitHubu s obsahem tohoto ZIPu (složka `.github` musí zůstat).
2. Záložka **Actions** → „Sestavit APK“ → stáhni **Ukolnicek-apk** a nainstaluj `app-release.apk`.

Alternativa: otevřít složku v Android Studiu a dát Run.

## Nastavení WhatsApp (CallMeBot, zdarma, posílá jen tobě)
1. Na callmebot.com (WhatsApp API) najdi aktuální číslo bota a ulož si ho do kontaktů.
2. Pošli mu zprávu `I allow callmebot to send me messages`.
3. Bot pošle API klíč → v aplikaci ⋮ → Nastavení zadej své číslo (+420…) a klíč → Test WhatsApp.

## Nastavení e-mailu
- **Kam posílat upomínky**: libovolná adresa (přednastavíš jednou).
- **Odesílací účet**: Gmail + *heslo aplikace* (ne běžné heslo):
  1. Google účet → Zabezpečení → zapni dvoufázové ověření.
  2. Na myaccount.google.com/apppasswords vytvoř heslo aplikace (16 znaků).
  3. Zadej Gmail adresu a toto heslo → Test e-mailu.
- Jiný poskytovatel: vyplň jeho SMTP server (musí podporovat SSL na portu 465), např. smtp.seznam.cz.

## Opakované úkoly
- V dialogu úkolu **Opakovat**: každý den, každý týden, každé 2 týdny, každý měsíc (stejný den),
  každý měsíc n-tý / poslední den v týdnu (např. poslední pondělí), **první / poslední pracovní den v měsíci**
  (po–pá bez českých státních svátků), každý rok.
- Nabídka se řídí zvoleným datem — pro „poslední pondělí“ vyber datum, které je posledním pondělím v měsíci.
- Hlasem: „každé poslední pondělí v 8 porada“, „první pracovní den zaplatit nájem“, „každý pátek v 7 vynést koš“,
  „každou druhou středu v 15 trénink“, „každý měsíc…“, „každý rok…“.
- Po proběhnutí termínu se úkol sám posune na další termín (i s upomínkou).
  Odškrtnutí opakovaného úkolu = hotovo pro tento termín, přeskočí na další.
- Pozvánka do Outlooku přenese i opakování. Pozor: Outlook nezná české svátky, u „pracovního dne“
  ukáže první/poslední den po–pá (aplikace sama svátky přeskočí správně).

## Pozvánka do kalendáře (Outlook)
- U úkolu zaškrtni **Pozvánka do kalendáře (Outlook)**.
- Na adresu z nastavení „Kam posílat pozvánky do kalendáře“ (prázdné = stejná jako pro upomínky) přijde pozvánka jako na schůzku → v Outlooku **Přijmout**
  a událost je v kalendáři i s připomenutím podle volby „Kdy upozornit“.
- Změna názvu/času úkolu pošle aktualizaci, smazání úkolu nebo odškrtnutí pošle zrušení události.
- Pozvánka se posílá hned při uložení (ne až v čas upomínky).

## Jak upomínka funguje
- V čas upomínky se hned ukáže notifikace.
- WhatsApp a e-mail se odešlou na pozadí; když zrovna není internet, počkají na něj
  a při chybě to zkusí znovu. Když se to nepovede ani napotřetí, přijde notifikace „Nepodařilo se odeslat“.
- Po restartu telefonu se upomínky samy znovu naplánují.

## Aby upozornění chodila spolehlivě
Na Xiaomi/Redmi/POCO: Nastavení → Aplikace → Úkolníček →
- **Spořič baterie: Bez omezení**
- **Automatické spouštění: zapnout**
