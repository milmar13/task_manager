# Task Manager

## Opis projekta
Ovaj projekat je urađen u okviru predmeta *Alati i metode softverskog inženjerstva*.  
Tema je aplikacija **Task Manager**,jednostavan sistem za vođenje evidencije zadataka.

Aplikacija omogućava:
- dodavanje, izmenu i brisanje zadataka,
- označavanje zadatka kao završenog,
- filtriranje i pretragu zadataka,
- pregled osnovne statistike.

Backend je razvijen u **Clojure-u** korišćenjem biblioteka Ring i Compojure.  
Frontend je jednostavna web stranica (HTML, CSS, JavaScript) koja komunicira sa REST servisom.  
Podaci se čuvaju u JSON fajlu.

---

## Funkcionalnosti
- Kreiranje zadataka (naziv, opis, prioritet, rok, tagovi)
- Izmena i brisanje zadataka
- Završavanje zadataka (status done)
- Pregled zadataka sa filterima i pretragom
- Statistika: broj zadataka po statusu, po prioritetu i broj zadataka kojima je istekao rok

---

## Tehnologije
- Programsko okruženje: **Clojure, Leiningen**
- Web server: **Ring/Compojure**
- Baza podataka: **JSON fajl**
- Frontend: **HTML, CSS, JavaScript**
- Testiranje: **Midje**

---

## Pokretanje projekta
1. Instalirati **Leiningen** i JDK 17+
2. U root folderu projekta pokrenuti:
  - lein clean
  - lein deps
  - lein run
3. Otvoriti u browseru: [http://localhost:3000](http://localhost:3000)

## Pokretanje testova
  - lein midje

---

## Zaključak
Projekat **Task Manager** pokazuje primenu alata i metoda softverskog inženjerstva kroz
- modelovanje i planiranje,
- implementaciju frontend-a i backend-a,
- testiranje funkcionalnosti.

Rezultat je jednostavna ali funkcionalna aplikacija za upravljanje zadacima.

