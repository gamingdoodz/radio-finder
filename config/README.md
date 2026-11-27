# Server-Adressen für die App

`servers.json` liest die App einmal am Tag (über raw.githubusercontent.com, sonst jsDelivr).
Zieht der Hitradar-Server um, hier die neue Adresse **zuerst** eintragen. Die alten, eingebauten Adressen
bleiben in der App immer als letzter Rückfall. Höchstens 5 Einträge, nur `https://` oder `http://`, ohne `/` am Ende.
Änderungen hier lösen keinen App-Bau aus.
