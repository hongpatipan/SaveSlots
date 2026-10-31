package saveslots;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Plain-folder tests for SaveSlotsApi file logic. Run outside the game. */
public class SaveSlotsTest {
    static int failures = 0;
    static Path saves, work;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("saveslots-test");
        saves = root.resolve("Saves/Sandbox");
        work = root.resolve("SaveSlotsWork");
        Files.createDirectories(saves);
        Path main = world("2026-10-26_21-58-55", "main-v1");
        Path other = world("2026-01-01_00-00-00", "other-world");
        Path otherSlot = world("2026-01-01_00-00-00 - a", "other-slot");
        Files.writeString(otherSlot.resolve("saveslots.txt"), "2026-01-01_00-00-00");
        Path legacy = world("2026-10-26_21-58-55 - hong", "legacy");   // made by 1.0.0, no marker
        Path foreign = world("2026-10-26_21-58-55 - z", "foreign");
        Files.writeString(foreign.resolve("saveslots.txt"), "SOMETHING ELSE");
        prepare();

        restoreTests(root.resolve("restore"));
        linkTests(root.resolve("link"));
        folderTests(root.resolve("folder"));

        // 1. new slot from main world
        Map<String, String> before = snapshot(main, other, otherSlot, legacy, foreign);
        check("new slot", run(main, "a", false), "OK:a");
        Path a = saves.resolve("2026-10-26_21-58-55 - a");
        check("new slot content", read(a), "main-v1");
        check("new slot marker", Files.readString(a.resolve("saveslots.txt")), "2026-10-26_21-58-55");
        unchanged("new slot", before);

        // 2. same name without overwrite
        before = snapshot(main, other, otherSlot, legacy, foreign, a);
        check("duplicate", run(main, "a", false), "ERR:exists:a");
        unchanged("duplicate", before);

        // 3. overwrite after the world changed; other slots untouched
        check("slot b", run(main, "b", false), "OK:b");
        Path b = saves.resolve("2026-10-26_21-58-55 - b");
        Files.writeString(main.resolve("data.bin"), "main-v2");
        before = snapshot(main, other, otherSlot, legacy, foreign, b);
        check("overwrite", run(main, "a", true), "OK:a");
        check("overwrite content", read(a), "main-v2");
        unchanged("overwrite", before);

        // 4. playing slot a, save as c -> "<world> - c", not "<world> - a - c"
        before = snapshot(main, other, otherSlot, legacy, foreign, a, b);
        check("from slot", run(a, "c", false), "OK:c");
        check("from slot name", Files.isDirectory(saves.resolve("2026-10-26_21-58-55 - c")), true);
        check("no nested name", Files.exists(saves.resolve("2026-10-26_21-58-55 - a - c")), false);
        unchanged("from slot", before);

        // 5. playing slot a, save as a -> nothing copied
        before = snapshot(main, other, otherSlot, legacy, foreign, b);
        check("same slot", run(a, "a", true), "OK:a");
        check("same slot content", read(a), "main-v2");
        unchanged("same slot", before);

        // 6. folder with a different world's marker is never overwritten
        before = snapshot(foreign);
        check("foreign", run(main, "z", true), "ERR:foreign:2026-10-26_21-58-55 - z");
        unchanged("foreign", before);

        // 7. playing a 1.0.0 slot without marker
        before = snapshot(main, other, otherSlot, foreign, a, b);
        check("legacy base", run(legacy, "d", false), "OK:d");
        check("legacy name", Files.isDirectory(saves.resolve("2026-10-26_21-58-55 - d")), true);
        unchanged("legacy base", before);

        // 8. names
        check("empty", run(main, "   ", false), "ERR:empty_name");
        check("traversal", run(main, "../../evil", false), "OK:evil");
        check("traversal stays", Files.isDirectory(saves.resolve("2026-10-26_21-58-55 - evil")), true);
        check("slash", run(main, "x/y\\z", false), "OK:xyz");
        check("separator", run(main, "p - q", false), "OK:p q");
        check("thai", run(main, "ก่อนบุกฐานทัพ", false), "OK:ก่อนบุกฐานทัพ");

        // 9. listing: only this world's slots, foreign and other world excluded
        String list = SaveSlotsApi.listSlotsAt(a);
        Set<String> names = new TreeSet<>();
        String currentFlag = null;
        for (String line : list.split("\n")) {
            String[] p = line.split("\t");
            names.add(p[0]);
            if (p[0].equals("a")) currentFlag = p[2];
        }
        check("list names", names.toString(), "[2026-10-26_21-58-55, a, b, c, d, evil, hong, p q, xyz, ก่อนบุกฐานทัพ]");
        String[] first = list.split("\n")[0].split("\t", -1);
        check("list main first", first[0] + "|" + first[6], "2026-10-26_21-58-55|1");
        check("list main rel", first[4], "Sandbox" + java.io.File.separator + "2026-10-26_21-58-55");
        String rowA = null;
        for (String line : list.split("\n")) if (line.startsWith("a\t")) rowA = line;
        String[] fa = rowA.split("\t", -1);
        check("list a fields", fa.length, 8);
        check("list a info", fa[7], "char=Kate Smith|day=3");
        String rowB = null;
        for (String line : list.split("\n")) if (line.startsWith("b\t")) rowB = line;
        check("list b size", Long.parseLong(rowB.split("\t", -1)[5]) > 0, true);
        check("list current flag", currentFlag, "1");

        // 10. copy fails (locked source file) -> target and others untouched, no leftovers
        Files.writeString(main.resolve("data.bin"), "main-v3");
        before = snapshot(main, other, a, b);
        try (RandomAccessFile raf = new RandomAccessFile(main.resolve("data.bin").toFile(), "rw");
             FileLock lock = raf.getChannel().lock()) {
            check("copy fail", run(main, "b", true).startsWith("ERR:io:"), true);
        }
        unchanged("copy fail", before);
        check("no leftovers", leftovers(), 0L);

        // 11. swap fails (file in target open) -> target kept
        before = snapshot(main, other, a, b);
        try (RandomAccessFile raf = new RandomAccessFile(b.resolve("data.bin").toFile(), "r")) {
            check("swap fail", run(main, "b", true).startsWith("ERR:io:"), true);
        }
        unchanged("swap fail", before);
        check("no leftovers 2", leftovers(), 0L);

        // 12. rename
        Path c = saves.resolve("2026-10-26_21-58-55 - c");
        before = snapshot(main, other, otherSlot, foreign, a, c);
        check("rename", SaveSlotsApi.renameSlotAt(a, "b", "b2"), "OK:b2");
        Path b2 = saves.resolve("2026-10-26_21-58-55 - b2");
        check("rename moved", Files.exists(b) + "|" + Files.isDirectory(b2), "false|true");
        unchanged("rename", before);
        check("rename to existing", SaveSlotsApi.renameSlotAt(a, "b2", "c"), "ERR:exists:c");
        check("rename playing", SaveSlotsApi.renameSlotAt(a, "a", "zz"), "ERR:playing");
        check("rename foreign", SaveSlotsApi.renameSlotAt(a, "z", "zz"), "ERR:not_found:z");
        check("rename main", SaveSlotsApi.renameSlotAt(a, "2026-10-26_21-58-55", "zz"), "ERR:not_found:2026-10-26_21-58-55");
        check("rename traversal", SaveSlotsApi.renameSlotAt(a, "../2026-01-01_00-00-00", "zz"), "ERR:not_found:../2026-01-01_00-00-00");
        check("rename case", SaveSlotsApi.renameSlotAt(a, "b2", "B2"), "OK:B2");
        boolean caseOk = false;
        try (Stream<Path> s = Files.list(saves)) { caseOk = s.anyMatch(p -> p.getFileName().toString().equals("2026-10-26_21-58-55 - B2")); }
        check("rename case applied", caseOk, true);
        b2 = saves.resolve("2026-10-26_21-58-55 - B2");
        check("rename kept content", read(b2), "main-v1");

        // 13. delete
        before = snapshot(main, other, otherSlot, foreign, a, b2);
        prepare();
        check("delete", SaveSlotsApi.deleteSlotAt(a, work, "c"), "OK:c");
        check("delete gone", Files.exists(c), false);
        unchanged("delete", before);
        check("delete playing", SaveSlotsApi.deleteSlotAt(a, work, "a"), "ERR:playing");
        check("delete foreign", SaveSlotsApi.deleteSlotAt(a, work, "z"), "ERR:not_found:z");
        check("delete main", SaveSlotsApi.deleteSlotAt(a, work, "2026-10-26_21-58-55"), "ERR:not_found:2026-10-26_21-58-55");
        check("delete traversal", SaveSlotsApi.deleteSlotAt(a, work, "..\\2026-01-01_00-00-00"), "ERR:not_found:..\\2026-01-01_00-00-00");
        unchanged("delete refused", before);
        check("no leftovers 3", leftovers(), 0L);

        // 14. delete with a file in use -> slot kept whole
        before = snapshot(b2);
        try (RandomAccessFile raf = new RandomAccessFile(b2.resolve("data.bin").toFile(), "r")) {
            check("delete locked", SaveSlotsApi.deleteSlotAt(a, work, "B2").startsWith("ERR:io:"), true);
        }
        unchanged("delete locked", before);

        // 15. pending load is handed out once
        SaveSlotsApi.requestLoad("Sandbox\\x");
        check("pending load", SaveSlotsApi.takePendingLoad(), "Sandbox\\x");
        check("pending load once", SaveSlotsApi.takePendingLoad(), "");

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void prepare() throws Exception {
        SaveSlotsApi.prepareWorkDir(work);
    }

    static String run(Path current, String name, boolean overwrite) throws Exception {
        prepare();
        return SaveSlotsApi.saveSlotAt(current, work, name, overwrite, "char=Kate Smith|day=3");
    }

    static Path world(String name, String content) throws Exception {
        Path dir = saves.resolve(name);
        Files.createDirectories(dir.resolve("map/0"));
        Files.writeString(dir.resolve("data.bin"), content);
        Files.writeString(dir.resolve("map/0/chunk_1.bin"), content + "-chunk");
        return dir;
    }

    static String read(Path dir) throws Exception {
        return Files.readString(dir.resolve("data.bin"));
    }

    static long leftovers() throws Exception {
        SaveSlotsApi.awaitDeletes();
        try (Stream<Path> s = Files.list(work)) {
            return s.count();
        }
    }

    /** Loading a slot: main world becomes a copy, the slot never changes. */
    static void restoreTests(Path root) throws Exception {
        Path dir = root.resolve("Sandbox");
        Path w = root.resolve("work");
        Files.createDirectories(dir);
        Files.createDirectories(w);
        Path main = dir.resolve("W");
        Files.createDirectories(main.resolve("map"));
        Files.writeString(main.resolve("data.bin"), "main-now");
        Files.writeString(main.resolve("map/c.bin"), "main-chunk");
        Path sleep = dir.resolve("W - Sleep Save");
        Files.createDirectories(sleep.resolve("map"));
        Files.writeString(sleep.resolve("data.bin"), "sleep");
        Files.writeString(sleep.resolve("map/c.bin"), "sleep-chunk");
        Files.writeString(sleep.resolve("saveslots.txt"), "W");
        Files.writeString(sleep.resolve("saveslots-info.txt"), "auto=sleep\n");
        Path other = dir.resolve("W - b");
        Files.createDirectories(other);
        Files.writeString(other.resolve("data.bin"), "b");
        Files.writeString(other.resolve("saveslots.txt"), "W");
        Path foreignMain = dir.resolve("V");
        Files.createDirectories(foreignMain);
        Files.writeString(foreignMain.resolve("saveslots.txt"), "X");
        Path vSlot = dir.resolve("V - s");
        Files.createDirectories(vSlot);
        Files.writeString(vSlot.resolve("saveslots.txt"), "V");

        Map<String, String> slotBefore = snapshotAbs(sleep, other);
        check("restore result", SaveSlotsApi.restoreSlotAt(sleep, w), "OK:Sandbox" + java.io.File.separator + "W");
        check("restore main data", Files.readString(main.resolve("data.bin")), "sleep");
        check("restore main chunk", Files.readString(main.resolve("map/c.bin")), "sleep-chunk");
        check("restore no marker in main", Files.exists(main.resolve("saveslots.txt")), false);
        check("restore no info in main", Files.exists(main.resolve("saveslots-info.txt")), false);
        check("restore slot unchanged", snapshotAbs(sleep, other), slotBefore);
        try (Stream<Path> s = Files.list(w)) {
            check("restore work empty", s.count(), 0L);
        }

        // main world itself and unknown folders are loaded as they are
        Files.writeString(main.resolve("data.bin"), "played");
        check("restore main as is", SaveSlotsApi.restoreSlotAt(main, w), "OK:Sandbox" + java.io.File.separator + "W");
        check("restore main untouched", Files.readString(main.resolve("data.bin")), "played");

        // main folder carrying a marker is never replaced
        check("restore foreign main", SaveSlotsApi.restoreSlotAt(vSlot, w), "ERR:foreign:V");
        check("restore missing", SaveSlotsApi.restoreSlotAt(dir.resolve("W - nope"), w), "ERR:not_found:W - nope");

        // main world deleted: the slot is copied to a new main world
        Path main2Slot = dir.resolve("Q - x");
        Files.createDirectories(main2Slot);
        Files.writeString(main2Slot.resolve("data.bin"), "q");
        Files.writeString(main2Slot.resolve("saveslots.txt"), "Q");
        check("restore no main", SaveSlotsApi.restoreSlotAt(main2Slot, w), "OK:Sandbox" + java.io.File.separator + "Q");
        check("restore no main data", Files.readString(dir.resolve("Q/data.bin")), "q");
        check("restore no main slot kept", Files.readString(main2Slot.resolve("data.bin")), "q");

        // a locked file in main: nothing changes
        Files.writeString(main.resolve("data.bin"), "locked-main");
        try (RandomAccessFile raf = new RandomAccessFile(main.resolve("data.bin").toFile(), "rw");
                FileLock lock = raf.getChannel().lock()) {
            String r = SaveSlotsApi.restoreSlotAt(other, w);
            check("restore locked fails", r.startsWith("ERR:io"), true);
        }
        check("restore locked main kept", Files.readString(main.resolve("data.bin")), "locked-main");
        check("restore locked slot kept", Files.readString(other.resolve("data.bin")), "b");
    }

    /** Overwriting a slot links unchanged files to its previous copy; nothing else is shared. */
    static void linkTests(Path root) throws Exception {
        Path dir = root.resolve("Sandbox");
        Path w = root.resolve("work");
        Path main = dir.resolve("W");
        Files.createDirectories(main.resolve("map/0"));
        Files.writeString(main.resolve("data.bin"), "v1");
        Files.writeString(main.resolve("map/0/c1.bin"), "chunk-1");
        Files.writeString(main.resolve("map/0/c2.bin"), "chunk-2");
        Path other = dir.resolve("W - other");
        SaveSlotsApi.prepareWorkDir(w);
        check("link other", SaveSlotsApi.saveSlotAt(main, w, "other", false, "auto=backup"), "OK:other");
        Map<String, String> otherBefore = snapshot(other);

        // new slot: nothing to link to
        check("link new", SaveSlotsApi.saveSlotAt(main, w, "s", false, "auto=backup"), "OK:s");
        check("link new count", SaveSlotsApi.lastCopy.linkedFiles, 0L);
        Path s = dir.resolve("W - s");

        // the game rewrites one file; the two chunks are unchanged
        Files.writeString(main.resolve("data.bin"), "v2");
        Files.setLastModifiedTime(main.resolve("data.bin"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        check("link overwrite", SaveSlotsApi.saveSlotAt(main, w, "s", true, "auto=backup"), "OK:s");
        check("link files", SaveSlotsApi.lastCopy.files, 3L);
        check("link linked", SaveSlotsApi.lastCopy.linkedFiles, 2L);
        check("link content", Files.readString(s.resolve("data.bin")), "v2");
        check("link chunk", Files.readString(s.resolve("map/0/c1.bin")), "chunk-1");
        check("link marker", Files.readString(s.resolve("saveslots.txt")), "W");
        check("link info", Files.readString(s.resolve("saveslots-info.txt")).contains("size=16"), true);
        SaveSlotsApi.awaitDeletes();
        try (Stream<Path> l = Files.list(w)) {
            check("link old copy removed", l.count(), 0L);
        }

        // the main world is written in place: the slot must not change with it
        Files.writeString(main.resolve("map/0/c1.bin"), "chunk-1-played");
        check("link not shared with main", Files.readString(s.resolve("map/0/c1.bin")), "chunk-1");
        check("link other slot unchanged", snapshot(other), otherBefore);

        // the slot itself is written in place: the main world must not change with it
        Files.writeString(s.resolve("map/0/c2.bin"), "slot-edit");
        check("link main not shared with slot", Files.readString(main.resolve("map/0/c2.bin")), "chunk-2");

        // links fail: everything is copied, same result
        SaveSlotsApi.breakLinksForTest = true;
        try {
            check("link fallback", SaveSlotsApi.saveSlotAt(main, w, "s", true, "auto=backup"), "OK:s");
        } finally {
            SaveSlotsApi.breakLinksForTest = false;
        }
        check("link fallback linked", SaveSlotsApi.lastCopy.linkedFiles, 0L);
        check("link fallback files", SaveSlotsApi.lastCopy.files, 3L);
        check("link fallback content", Files.readString(s.resolve("map/0/c1.bin")), "chunk-1-played");
        check("link fallback c2", Files.readString(s.resolve("map/0/c2.bin")), "chunk-2");
        check("link fallback other unchanged", snapshot(other), otherBefore);

        // swap fails after linking: the previous copy is kept whole
        Files.writeString(main.resolve("data.bin"), "v3");
        Files.setLastModifiedTime(main.resolve("data.bin"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        Map<String, String> sBefore = snapshot(s);
        try (RandomAccessFile raf = new RandomAccessFile(s.resolve("data.bin").toFile(), "r")) {
            check("link swap fail", SaveSlotsApi.saveSlotAt(main, w, "s", true, "auto=backup").startsWith("ERR:io:"), true);
        }
        check("link swap fail kept", snapshot(s), sBefore);
        SaveSlotsApi.prepareWorkDir(w);
        SaveSlotsApi.awaitDeletes();
        try (Stream<Path> l = Files.list(w)) {
            check("link swap fail no leftovers", l.count(), 0L);
        }

        // crash leftovers: half copies and deleted folders go, "old-*" stays
        for (String name : new String[] { "new-1", "del-2", "del-old-3", "old-4" }) {
            Files.createDirectories(w.resolve(name + "/map"));
            Files.writeString(w.resolve(name + "/map/x.bin"), "x");
        }
        SaveSlotsApi.prepareWorkDir(w);
        SaveSlotsApi.prepareWorkDir(w);   // asked twice while the first is running
        SaveSlotsApi.awaitDeletes();
        List<String> left = new ArrayList<>();
        try (Stream<Path> l = Files.list(w)) {
            l.forEach(p -> left.add(p.getFileName().toString()));
        }
        check("leftovers cleaned", left.toString(), "[old-4]");
    }

    /** Slots kept in a save folder chosen in the options. */
    static void folderTests(Path root) throws Exception {
        Path savesRoot = root.resolve("Saves");
        Path dir = savesRoot.resolve("Sandbox");
        Path w = root.resolve("work");
        Path chosen = root.resolve("D/PZ Saves");
        Path main = dir.resolve("W");
        Files.createDirectories(main.resolve("map"));
        Files.writeString(main.resolve("data.bin"), "v1");
        Files.writeString(main.resolve("map/c.bin"), "chunk");
        SaveSlotsApi.prepareWorkDir(w);

        // checking the folder typed in the options
        check("folder relative", SaveSlotsApi.checkSaveFolder("PZ Saves", savesRoot), "ERR:not_absolute:PZ Saves");
        check("folder in saves", SaveSlotsApi.checkSaveFolder(dir.resolve("x").toString(), savesRoot).startsWith("ERR:in_saves:"), true);
        check("folder ok", SaveSlotsApi.checkSaveFolder(chosen.toString(), savesRoot), "OK:" + chosen);
        check("folder created", Files.isDirectory(chosen), true);
        try (Stream<Path> l = Files.list(chosen)) {
            check("folder probe removed", l.count(), 0L);
        }

        // a slot made before the folder was set stays in Saves
        check("folder old slot", SaveSlotsApi.saveSlotAt(main, w, "old", false, ""), "OK:old");
        Path oldInSaves = dir.resolve("W - old");

        // new slot goes to the chosen folder
        check("folder new", SaveSlotsApi.saveSlotAt(main, chosen, w, "new", false, ""), "OK:new");
        Path newSlot = chosen.resolve("Sandbox/W - new");
        check("folder new there", Files.readString(newSlot.resolve("data.bin")), "v1");
        check("folder new not in saves", Files.exists(dir.resolve("W - new")), false);
        check("folder new marker", Files.readString(newSlot.resolve("saveslots.txt")), "W");

        // names are unique across both places
        check("folder exists in saves", SaveSlotsApi.saveSlotAt(main, chosen, w, "old", false, ""), "ERR:exists:old");

        // listing shows both, slots in the chosen folder with "@"
        String list = SaveSlotsApi.listSlotsAt(main, chosen);
        Map<String, String> rels = new TreeMap<>();
        for (String line : list.split("\n")) {
            String[] p = line.split("\t", -1);
            rels.put(p[0], p[4]);
        }
        String sep = java.io.File.separator;
        check("folder list", rels.toString(),
                "{W=Sandbox" + sep + "W, new=@Sandbox" + sep + "W - new, old=Sandbox" + sep + "W - old}");
        check("folder list without folder", SaveSlotsApi.listSlotsAt(main, null).contains("W - new"), false);

        // overwriting a slot still in Saves moves it to the chosen folder
        Files.writeString(main.resolve("data.bin"), "v2");
        check("folder overwrite old", SaveSlotsApi.saveSlotAt(main, chosen, w, "old", true, ""), "OK:old");
        SaveSlotsApi.awaitDeletes();
        check("folder overwrite moved", Files.readString(chosen.resolve("Sandbox/W - old/data.bin")), "v2");
        check("folder overwrite left saves", Files.exists(oldInSaves), false);
        try (Stream<Path> l = Files.list(w)) {
            check("folder overwrite no leftovers", l.count(), 0L);
        }

        // overwriting in the chosen folder
        Files.writeString(main.resolve("data.bin"), "v3");
        check("folder overwrite new", SaveSlotsApi.saveSlotAt(main, chosen, w, "new", true, ""), "OK:new");
        check("folder overwrite new content", Files.readString(newSlot.resolve("data.bin")), "v3");
        SaveSlotsApi.awaitDeletes();
        try (Stream<Path> l = Files.list(chosen.resolve("SaveSlotsWork"))) {
            check("folder work empty", l.count(), 0L);
        }

        // rename stays in the chosen folder, clashes with Saves are refused
        check("folder saves slot", SaveSlotsApi.saveSlotAt(main, w, "insaves", false, ""), "OK:insaves");
        check("folder rename clash", SaveSlotsApi.renameSlotAt(main, chosen, "new", "insaves"), "ERR:exists:insaves");
        check("folder rename", SaveSlotsApi.renameSlotAt(main, chosen, "new", "renamed"), "OK:renamed");
        Path renamed = chosen.resolve("Sandbox/W - renamed");
        check("folder renamed there", Files.readString(renamed.resolve("data.bin")), "v3");

        // loading a slot from the chosen folder replaces the main world in Saves
        Files.writeString(main.resolve("data.bin"), "played");
        check("folder restore", SaveSlotsApi.restoreSlotAt(renamed, dir, w), "OK:Sandbox" + sep + "W");
        check("folder restore main", Files.readString(main.resolve("data.bin")), "v3");
        check("folder restore no marker", Files.exists(main.resolve("saveslots.txt")), false);
        check("folder restore slot kept", Files.readString(renamed.resolve("data.bin")), "v3");

        // delete from the chosen folder and from Saves
        check("folder delete", SaveSlotsApi.deleteSlotAt(main, chosen, w, "renamed"), "OK:renamed");
        check("folder delete saves", SaveSlotsApi.deleteSlotAt(main, chosen, w, "insaves"), "OK:insaves");
        SaveSlotsApi.awaitDeletes();
        check("folder deleted", Files.exists(renamed) + "|" + Files.exists(dir.resolve("W - insaves")), "false|false");
        try (Stream<Path> l = Files.list(chosen.resolve("SaveSlotsWork"))) {
            check("folder delete no leftovers", l.count(), 0L);
        }
        check("folder main kept", Files.readString(main.resolve("data.bin")), "v3");

        // folder picker
        Path pick = root.resolve("pick");
        Files.createDirectories(pick.resolve("beta"));
        Files.createDirectories(pick.resolve("Alpha"));
        Path hiddenDir = Files.createDirectories(pick.resolve("secret"));
        Files.setAttribute(hiddenDir, "dos:hidden", true);
        Files.writeString(pick.resolve("file.txt"), "x");
        String[] lines = SaveSlotsApi.listFolders(pick.toString()).split("\n", -1);
        check("picker head", lines[0] + "|" + lines[1], "OK:" + pick + "|" + root);
        check("picker rows", String.join(",", Arrays.copyOfRange(lines, 2, lines.length)),
                "Alpha\t" + pick.resolve("Alpha") + ",beta\t" + pick.resolve("beta"));
        check("picker drives", SaveSlotsApi.listFolders("").startsWith("OK:\n\n"), true);
        check("picker missing", SaveSlotsApi.listFolders(pick.resolve("nope").toString()).startsWith("ERR:missing:"), true);
        check("picker make", SaveSlotsApi.makeFolder(pick.toString(), "PZ Saves"), "OK:" + pick.resolve("PZ Saves"));
        check("picker make exists", SaveSlotsApi.makeFolder(pick.toString(), "PZ Saves"), "ERR:exists:PZ Saves");
        check("picker make traversal", SaveSlotsApi.makeFolder(pick.toString(), "..\\evil"), "OK:" + pick.resolve("evil"));
        check("picker make empty", SaveSlotsApi.makeFolder(pick.toString(), " / "), "ERR:empty_name");
        check("picker make no parent", SaveSlotsApi.makeFolder("", "x"), "ERR:bad_path:");
    }

    static Map<String, String> snapshotAbs(Path... dirs) throws Exception {
        return snapshot(dirs);
    }

    static Map<String, String> snapshot(Path... dirs) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (Path d : dirs) {
            try (Stream<Path> s = Files.walk(d)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    m.put(f.toString(), HexFormat.of().formatHex(md.digest(Files.readAllBytes(f))));
                }
            }
        }
        return m;
    }

    static void unchanged(String label, Map<String, String> before) throws Exception {
        List<Path> dirs = new ArrayList<>();
        for (String f : before.keySet()) {
            Path p = Paths.get(f);
            while (!p.getParent().equals(saves)) p = p.getParent();
            if (!dirs.contains(p)) dirs.add(p);
        }
        Map<String, String> after = snapshot(dirs.toArray(new Path[0]));
        check(label + " (other saves unchanged)", after, before);
    }

    static void check(String label, Object actual, Object expected) {
        boolean ok = Objects.equals(actual, expected);
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + label + (ok ? "" : "  expected=" + expected + " actual=" + actual));
    }
}
