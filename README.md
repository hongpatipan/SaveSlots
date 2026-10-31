# Save Slots [B42]

Named saves for Project Zomboid Build 42 single-player: Save As, load without leaving the game, Sleep Save, Auto Backup, and an optional save folder on any drive.

Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3827827224

Requires [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853), since copying save folders needs Java.

## Layout

- `Contents/mods/SaveSlots/42/` – the mod as uploaded to the Workshop
  - `media/lua/client/SaveSlots.lua` – window, options, Sleep Save, Auto Backup, folder picker
  - `src/saveslots/SaveSlotsApi.java` – file work (copy, swap, rename, delete, restore)
  - `media/java/client/SaveSlots.jar` – compiled `SaveSlotsApi.java`
- `tests/SaveSlotsTest.java` – plain-folder tests for the Java file logic
- `workshop.txt`, `preview.png` – Workshop page text and picture

## Build

```
javac --release 17 -cp "projectzomboid.jar;ZombieBuddy.jar" -d build Contents/mods/SaveSlots/42/src/saveslots/SaveSlotsApi.java
jar cf Contents/mods/SaveSlots/42/media/java/client/SaveSlots.jar -C build saveslots
```

## Tests

```
javac --release 17 -cp "projectzomboid.jar;ZombieBuddy.jar" -d out Contents/mods/SaveSlots/42/src/saveslots/SaveSlotsApi.java tests/SaveSlotsTest.java
java -cp "out;projectzomboid.jar;ZombieBuddy.jar" saveslots.SaveSlotsTest
```
