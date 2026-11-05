package saveslots;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import me.zed_0xff.zombie_buddy.Exposer;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.iso.ChunkSaveWorker;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Lua API for Save Slots, exposed by ZombieBuddy as the table SaveSlotsJava,
 * e.g. SaveSlotsJava.saveSlot(name, overwrite, info).
 *
 * A slot is a full copy of a world folder, stored next to it as
 * "<world> - <slot name>" with a saveslots.txt marker holding "<world>" and
 * an optional saveslots-info.txt with details shown in the dialog.
 *
 * Safety rules:
 * - Only the slot being saved, renamed or deleted is ever changed. The world
 *   being played and other worlds are only read. The main world is only
 *   replaced when the player loads a slot (restoreSlot), so slots are never
 *   played in and keep what they were saved with.
 * - A folder is only changed if it is a slot of the same world.
 * - New copies are built in a work folder outside Saves and swapped in with
 *   renames, so a failed copy leaves the old slot untouched. Deleted slots
 *   are moved out of Saves first, so a failed delete never leaves a broken
 *   save in Load Game.
 * - Overwriting a slot hard links files unchanged since its previous copy
 *   instead of copying them; that copy is deleted after the swap, so no file
 *   is ever shared between two saves. Loading always makes a full copy.
 *
 * Slots can also be kept in a save folder chosen in the options, as
 * "<folder>\<game mode>\<world> - <slot name>". New copies go there; slots
 * already in Saves stay there until they are overwritten, and both are listed.
 * Slots there are named "@<game mode>\<folder>" in place of "<game mode>\<folder>".
 *
 * Results are returned as "OK:<slot name>" or "ERR:<reason>[:<detail>]" so
 * the Lua side never has to handle Java exceptions.
 */
@Exposer.LuaClass(name = "SaveSlotsJava")
public class SaveSlotsApi {
    private static final String MARKER_FILE = "saveslots.txt";
    private static final String INFO_FILE = "saveslots-info.txt";
    private static final String SEPARATOR = " - ";
    private static final long CHUNK_FLUSH_TIMEOUT_MS = 15000;
    private static final long PENDING_LOAD_TIMEOUT_MS = 120000;
    private static final int MAX_NAME_LENGTH = 48;
    private static final String FOLDER_PREFIX = "@";
    private static final String FOLDER_WORK = "SaveSlotsWork";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // Survives the Lua reset when the game goes back to the main menu.
    private static String pendingLoad = "";
    private static long pendingLoadAt = 0;

    // The save folder chosen in the options (null: the game's Saves folder),
    // and why it is not used when it cannot be.
    private static Path saveFolder = null;
    // The folder typed in the options even while it is not ticked, so saves
    // already there can still be listed, loaded, renamed and deleted.
    private static Path lookFolder = null;
    private static String saveFolderError = "";

    // Old copies are deleted on a low-priority thread so the game does not
    // wait for it. They are in the work folder, which the game never uses.
    private static final Set<Path> deleting = ConcurrentHashMap.newKeySet();
    private static final ExecutorService deleter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SaveSlots-delete");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // Thumbnail stamp ("mtime:size") last loaded per file, so pictures are only reloaded when they changed.
    private static final Map<String, String> thumbStamps = new ConcurrentHashMap<>();

    /** Numbers from the last copy, for the log and the tests. */
    static CopyStats lastCopy = new CopyStats();

    // Tests only: makes every hard link fail, to check the copy falls back.
    static boolean breakLinksForTest = false;

    public static boolean isAvailable() {
        return !GameClient.client && !GameServer.server;
    }

    /**
     * The main world and its slots, main world first, then slots newest
     * first. One per line, tab separated:
     * name, saved at (epoch millis), playing now (1/0), saved at (text),
     * save folder relative to Saves ("Sandbox\folder"), size in bytes
     * (-1 if unknown), main world (1/0), info ("key=value|key=value").
     */
    public static String listSlots() {
        Path current = currentSave();
        return current == null ? "" : listSlotsAt(current, knownFolder());
    }

    static String listSlotsAt(Path current) {
        return listSlotsAt(current, null);
    }

    static String listSlotsAt(Path current, Path folder) {
        String base = baseWorldName(current);
        Path main = current.resolveSibling(base);
        List<String[]> rows = new ArrayList<>();
        try {
            for (Path dir : slotsOf(current, folder, base).values()) {
                boolean inFolder = folder != null && dir.startsWith(folder);
                rows.add(row(slotNameOf(dir, base), dir, current, false, inFolder));
            }
        } catch (IOException e) {
            System.out.println("[SaveSlots] Listing slots failed: " + e);
            return "";
        }
        rows.sort(Comparator.comparingLong((String[] r) -> Long.parseLong(r[1])).reversed());
        if (Files.isDirectory(main)) {
            rows.add(0, row(base, main, current, true, false));
        }
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.join("\t", r));
        }
        return sb.toString();
    }

    private static String[] row(String name, Path dir, Path current, boolean isMain, boolean inFolder) {
        long savedAt = savedAt(dir, isMain);
        String info = readInfo(dir);
        long size = isMain || dir.equals(current) ? -1 : infoSize(info, dir);
        return new String[] {
                name,
                Long.toString(savedAt),
                dir.equals(current) ? "1" : "0",
                DATE_FORMAT.format(Instant.ofEpochMilli(savedAt)),
                (inFolder ? FOLDER_PREFIX : "") + dir.getParent().getFileName() + File.separator + dir.getFileName(),
                Long.toString(size),
                isMain ? "1" : "0",
                info };
    }

    /** The slot name that would be used for this input, or "" if it is not usable. */
    public static String cleanName(String slotName) {
        return sanitize(slotName);
    }

    /**
     * Copies the world being played into the slot. Call Lua saveGame() first
     * so the world on disk is up to date. An existing slot is only replaced
     * when overwrite is true. info ("key=value|...") is stored with the slot.
     */
    public static String saveSlot(String slotName, boolean overwrite, String info) {
        String problem = checkCanSave();
        if (problem != null) {
            return problem;
        }
        Path work;
        try {
            work = workDir();
        } catch (IOException e) {
            return "ERR:io:" + e.getMessage();
        }
        return saveSlotAt(currentSave(), usableSaveFolder(), knownFolder(), work, slotName, overwrite, info);
    }

    /** null if the world being played can be saved into a slot now, otherwise the error. */
    private static String checkCanSave() {
        if (!isAvailable()) {
            return "ERR:multiplayer";
        }
        if (Core.getInstance().isNoSave()) {
            return "ERR:nosave";
        }
        if (currentSave() == null) {
            return "ERR:no_save";
        }
        if (!flushChunks()) {
            return "ERR:chunks_busy";
        }
        return null;
    }

    static String saveSlotAt(Path current, Path work, String slotName, boolean overwrite, String info) {
        return saveSlotAt(current, null, work, slotName, overwrite, info);
    }

    /**
     * File part of saveSlot, kept separate so it can be tested on plain folders.
     * The copy goes to the chosen save folder when there is one (null: Saves).
     * work is the work folder next to Saves.
     */
    static String saveSlotAt(Path current, Path folder, Path work, String slotName, boolean overwrite, String info) {
        return saveSlotAt(current, folder, folder, work, slotName, overwrite, info);
    }

    /** look: where existing slots are looked for besides Saves (the unticked folder too). */
    static String saveSlotAt(Path current, Path folder, Path look, Path work, String slotName, boolean overwrite,
            String info) {
        String clean = sanitize(slotName);
        if (clean.isEmpty()) {
            return "ERR:empty_name";
        }
        String base = baseWorldName(current);
        Path inSaves = current.resolveSibling(base + SEPARATOR + clean).normalize();
        Path parent = slotParent(current, folder);
        Path target = parent.resolve(base + SEPARATOR + clean).normalize();
        if (!inSaves.getParent().equals(current.getParent()) || !target.getParent().equals(parent)) {
            return "ERR:bad_name";
        }

        // Playing this very slot: saveGame() already wrote it.
        if (inSaves.equals(current)) {
            writeMarker(current, base);
            writeInfo(current, info, -1);
            return "OK:" + clean;
        }

        if (Files.exists(target) && slotNameOf(target, base) == null) {
            return "ERR:foreign:" + target.getFileName();
        }
        // The slot as it is now, in the chosen folder or still in Saves.
        Path existing = findSlot(current, look != null ? look : folder, base, clean);
        if (existing != null && !overwrite) {
            return "ERR:exists:" + clean;
        }

        // Built on the same drive as the target, so it can be swapped in with a rename.
        Path targetWork = work;
        if (folder != null) {
            try {
                targetWork = prepareWorkDir(folder.resolve(FOLDER_WORK));
            } catch (IOException e) {
                return "ERR:io:" + e.getMessage();
            }
        }
        long stamp = System.nanoTime();
        Path fresh = targetWork.resolve("new-" + stamp);
        Path old = targetWork.resolve("old-" + stamp);

        try {
            // Files unchanged since the previous copy of this slot are linked
            // to it instead of copied. That copy is deleted right after the
            // swap, so no file stays shared between two saves: a save opened
            // without the mod is written in place and must not change another.
            CopyStats stats = copyRecursively(current, fresh, existing != null && canLink(existing) ? existing : null);
            writeMarkerOrThrow(fresh, base);
            writeInfo(fresh, info, stats.bytes);
            Files.createDirectories(parent);
            System.out.println("[SaveSlots] Copied " + stats);
        } catch (IOException e) {
            deleteQuietly(fresh);
            System.out.println("[SaveSlots] Copy failed, nothing changed: " + e);
            return "ERR:io:" + e.getMessage();
        }

        try {
            if (target.equals(existing)) {
                Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
                try {
                    Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // Put the previous slot back before reporting the error.
                    Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
                    throw e;
                }
                discard(targetWork, old);
            } else {
                // The game menu's link to a copy elsewhere: the new copy takes its place.
                if (isLink(target)) {
                    Files.delete(target);
                }
                Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            deleteQuietly(fresh);
            System.out.println("[SaveSlots] Swap failed, previous slot kept: " + e);
            return "ERR:io:" + e.getMessage();
        }
        if (existing != null && !existing.equals(target)) {
            // It was in the other place (Saves or the save folder): the new copy replaces it.
            try {
                Path folderOfExisting = look != null && existing.startsWith(look) ? look : null;
                trash(existing, folderOfExisting != null ? prepareWorkDir(folderOfExisting.resolve(FOLDER_WORK)) : work);
            } catch (IOException e) {
                System.out.println("[SaveSlots] Could not remove the previous copy " + existing + ": " + e);
            }
        }

        System.out.println("[SaveSlots] Saved slot \"" + clean + "\" from " + current + " to " + target);
        return "OK:" + clean;
    }

    /** Renames a slot of the world being played. The slot being played cannot be renamed. */
    public static String renameSlot(String oldName, String newName) {
        if (!isAvailable()) {
            return "ERR:multiplayer";
        }
        Path current = currentSave();
        return current == null ? "ERR:no_save" : renameSlotAt(current, knownFolder(), oldName, newName);
    }

    static String renameSlotAt(Path current, String oldName, String newName) {
        return renameSlotAt(current, null, oldName, newName);
    }

    /** Renames the slot in the folder it is in. */
    static String renameSlotAt(Path current, Path folder, String oldName, String newName) {
        String base = baseWorldName(current);
        Path source = findSlot(current, folder, base, oldName);
        if (source == null) {
            return "ERR:not_found:" + oldName;
        }
        if (source.equals(current)) {
            return "ERR:playing";
        }
        String clean = sanitize(newName);
        if (clean.isEmpty()) {
            return "ERR:empty_name";
        }
        Path target = source.resolveSibling(base + SEPARATOR + clean).normalize();
        if (!target.getParent().equals(source.getParent())) {
            return "ERR:bad_name";
        }
        boolean sameFolder = source.getFileName().toString().equalsIgnoreCase(target.getFileName().toString());
        // Names are unique across Saves and the chosen folder.
        if (!sameFolder && (Files.exists(target) || findSlot(current, folder, base, clean) != null)) {
            return "ERR:exists:" + clean;
        }
        try {
            if (sameFolder) {
                // Case-only rename on Windows needs a stop in between.
                Path step = source.resolveSibling(source.getFileName() + ".saveslots-rename");
                Files.move(source, step, StandardCopyOption.ATOMIC_MOVE);
                Files.move(step, target, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            System.out.println("[SaveSlots] Rename failed, nothing changed: " + e);
            return "ERR:io:" + e.getMessage();
        }
        System.out.println("[SaveSlots] Renamed slot " + source + " to " + target);
        return "OK:" + clean;
    }

    /** Deletes a slot of the world being played. The slot being played and the main world cannot be deleted. */
    public static String deleteSlot(String slotName) {
        if (!isAvailable()) {
            return "ERR:multiplayer";
        }
        Path current = currentSave();
        if (current == null) {
            return "ERR:no_save";
        }
        try {
            return deleteSlotAt(current, knownFolder(), workDir(), slotName);
        } catch (IOException e) {
            return "ERR:io:" + e.getMessage();
        }
    }

    static String deleteSlotAt(Path current, Path work, String slotName) {
        return deleteSlotAt(current, null, work, slotName);
    }

    static String deleteSlotAt(Path current, Path folder, Path work, String slotName) {
        String base = baseWorldName(current);
        Path slot = findSlot(current, folder, base, slotName);
        if (slot == null) {
            return "ERR:not_found:" + slotName;
        }
        if (slot.equals(current)) {
            return "ERR:playing";
        }
        try {
            trash(slot, folder != null && slot.startsWith(folder) ? prepareWorkDir(folder.resolve(FOLDER_WORK)) : work);
        } catch (IOException e) {
            System.out.println("[SaveSlots] Delete failed, nothing changed: " + e);
            return "ERR:io:" + e.getMessage();
        }
        System.out.println("[SaveSlots] Deleted slot " + slot);
        return "OK:" + slotName;
    }

    /**
     * The save's thumbnail (the picture the game takes of the character when
     * saving), reloaded from disk so an overwritten slot shows its new picture.
     */
    public static Texture thumbnail(String relativeSave) {
        Path save = savePath(relativeSave);
        if (save == null) {
            return null;
        }
        // The game's picture loader does not open files from another drive,
        // but it does through the slot's link in Saves (as Load Game shows).
        Path link = linkOf(relativeSave, save);
        String path = (link != null ? link : save).resolve("thumb.png").toString();
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        String stamp = file.lastModified() + ":" + file.length();
        boolean filtering = TextureID.useFiltering;
        TextureID.useFiltering = true;
        try {
            Texture texture = Texture.getSharedTexture(path);
            // The asset manager keeps the picture loaded the first time; reload
            // it from disk like the game does after saving (forgetTexture alone
            // leaves the old picture in the asset table). Only when the file
            // changed since the last reload, so opening the window stays quick.
            if (!stamp.equals(thumbStamps.put(path, stamp))) {
                Texture.reload(path);
            }
            return texture;
        } finally {
            TextureID.useFiltering = filtering;
        }
    }

    /**
     * When the thumbnail of the save being played was last written, as text
     * (Lua numbers cannot hold millis exactly), or "" if there is none. The game
     * writes it on the render thread a frame or more after saveGame(), so Lua
     * waits for this to change before copying.
     */
    public static String thumbStamp() {
        Path current = currentSave();
        if (current == null) {
            return "";
        }
        try {
            Path thumb = current.resolve("thumb.png");
            return Files.isRegularFile(thumb)
                    ? Files.getLastModifiedTime(thumb).toMillis() + ":" + Files.size(thumb) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Remembers which save to load once the game is back at the main menu. */
    public static void requestLoad(String relativeSave) {
        pendingLoad = relativeSave == null ? "" : relativeSave;
        pendingLoadAt = System.currentTimeMillis();
    }

    /** The save requested by requestLoad, once; "" if none or it is too old. */
    public static String takePendingLoad() {
        String save = pendingLoad;
        boolean fresh = System.currentTimeMillis() - pendingLoadAt < PENDING_LOAD_TIMEOUT_MS;
        pendingLoad = "";
        pendingLoadAt = 0;
        return fresh ? save : "";
    }

    /**
     * Makes the main world a copy of the slot, so the game is played in the
     * main world and the slot itself stays as it was saved. Call only at the
     * main menu, when no world is being written. A main world or slot given
     * here is returned as it is. Returns "OK:<save to load>" or "ERR:...".
     */
    public static String restoreSlot(String relativeSave) {
        if (!isAvailable()) {
            return "ERR:multiplayer";
        }
        Path slot = savePath(relativeSave);
        if (slot == null) {
            return "ERR:not_found:" + relativeSave;
        }
        // A slot in the chosen folder is loaded into the main world in Saves.
        Path saves = savesDir();
        Path parent = saves == null ? slot.getParent() : saves.resolve(slot.getParent().getFileName().toString());
        try {
            return restoreSlotAt(slot, parent, workDir());
        } catch (IOException e) {
            return "ERR:io:" + e.getMessage();
        }
    }

    /** The slot name if the save ("Sandbox/folder") is a slot, otherwise "". */
    public static String slotOf(String relativeSave) {
        Path save = savePath(relativeSave);
        if (save == null || !Files.isDirectory(save)) {
            return "";
        }
        String name = slotNameOf(save, baseWorldName(save));
        return name == null ? "" : name;
    }

    /**
     * "Sandbox\folder" in Saves, or "@Sandbox\folder" in the save folder; null
     * if not usable. A link in Saves (see syncLinks) gives the slot it points to.
     */
    private static Path savePath(String relativeSave) {
        if (relativeSave == null || relativeSave.isEmpty() || relativeSave.contains("..")) {
            return null;
        }
        Path path;
        if (relativeSave.startsWith(FOLDER_PREFIX)) {
            Path folder = knownFolder();
            if (folder == null) {
                return null;
            }
            path = folder.resolve(relativeSave.substring(FOLDER_PREFIX.length())).normalize();
            if (!path.startsWith(folder)) {
                return null;
            }
        } else {
            path = new File(ZomboidFileSystem.instance.getSaveDirSub(relativeSave)).toPath().toAbsolutePath().normalize();
        }
        return realPath(path);
    }

    /** The link in Saves for a slot in the save folder ("@Sandbox\folder"), or null if it has none. */
    private static Path linkOf(String relativeSave, Path slot) {
        Path saves = savesDir();
        if (saves == null || relativeSave == null || !relativeSave.startsWith(FOLDER_PREFIX)) {
            return null;
        }
        Path link = saves.resolve(relativeSave.substring(FOLDER_PREFIX.length())).normalize();
        if (!link.startsWith(saves) || !isLink(link)) {
            return null;
        }
        try {
            return link.toRealPath().equals(slot.toRealPath()) ? link : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The folder itself if it is not a link, the folder it points to if it is. */
    private static Path realPath(Path path) {
        if (!isLink(path)) {
            return path;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /** True for a symbolic link or Windows junction (not followed). */
    static boolean isLink(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes every slot in the save folder show in the game's own Load Game
     * list, which only reads Saves: each gets a link (a junction on Windows)
     * in Saves\<game mode> with the slot's folder name. The game then reads,
     * changes mods of and deletes it like any save, in its real place.
     * Links whose slot is gone are removed, and so are empty folders the
     * game's Delete leaves in the save folder. Real folders are never touched.
     */
    public static String syncLinks() {
        Path folder = knownFolder();
        Path saves = savesDir();
        if (folder == null || saves == null || !isAvailable()) {
            return "OK:0";
        }
        return syncLinksAt(folder, saves);
    }

    static String syncLinksAt(Path folder, Path saves) {
        int made = 0;
        Path realFolder;
        try {
            realFolder = folder.toRealPath();
        } catch (IOException e) {
            return "ERR:io:" + e.getMessage();
        }
        Set<Path> linked = new HashSet<>();
        // Links in Saves: drop the ones whose slot is gone, note the rest.
        try (DirectoryStream<Path> modes = Files.newDirectoryStream(saves, Files::isDirectory)) {
            for (Path mode : modes) {
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(mode)) {
                    for (Path link : dirs) {
                        if (!isLink(link)) {
                            continue;
                        }
                        Path real = null;
                        try {
                            real = link.toRealPath();
                        } catch (IOException e) {
                            // Points nowhere: slot deleted or renamed, or its drive is unplugged
                            // (made again when it is back).
                        }
                        if (real == null) {
                            if (link.getFileName().toString().contains(SEPARATOR)) {
                                deleteLink(link);
                            }
                        } else if (real.startsWith(realFolder)) {
                            if (readMarker(real) == null) {
                                deleteLink(link);
                            } else {
                                linked.add(real);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[SaveSlots] Checking links in Saves failed: " + e);
        }
        // Slots in the save folder without a link get one.
        try (DirectoryStream<Path> modes = Files.newDirectoryStream(realFolder, Files::isDirectory)) {
            for (Path mode : modes) {
                if (mode.getFileName().toString().equals(FOLDER_WORK) || hidden(mode) || isLink(mode)) {
                    continue;
                }
                try (DirectoryStream<Path> dirs = Files.newDirectoryStream(mode)) {
                    for (Path dir : dirs) {
                        if (isLink(dir) || !Files.isDirectory(dir)) {
                            continue;
                        }
                        String base = readMarker(dir);
                        if (base == null || slotNameOf(dir, base) == null) {
                            // Emptied by the game's Delete, which only removes the link and the files.
                            if (isEmptyDir(dir)) {
                                deleteLink(dir);
                            }
                            continue;
                        }
                        if (linked.contains(dir)) {
                            continue;
                        }
                        Path link = saves.resolve(mode.getFileName().toString()).resolve(dir.getFileName().toString());
                        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                            continue;   // a save of that name is in Saves already
                        }
                        Files.createDirectories(link.getParent());
                        if (makeLink(link, dir)) {
                            made++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[SaveSlots] Linking saves failed: " + e);
            return "ERR:io:" + e.getMessage();
        }
        if (made > 0) {
            System.out.println("[SaveSlots] Linked " + made + " saves from " + realFolder + " into " + saves);
        }
        return "OK:" + made;
    }

    private static boolean isEmptyDir(Path dir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /** Removes a link (never what it points to) or an empty folder. */
    private static void deleteLink(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            System.out.println("[SaveSlots] Could not remove " + path + ": " + e);
        }
    }

    /** A junction on Windows (needs no admin rights), a symbolic link elsewhere. */
    private static boolean makeLink(Path link, Path target) {
        try {
            if (File.separatorChar == '\\') {
                Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isDirectory(link)) {
                    System.out.println("[SaveSlots] Could not link " + link + ": " + out);
                    return false;
                }
            } else {
                Files.createSymbolicLink(link, target);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            System.out.println("[SaveSlots] Could not link " + link + ": " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String restoreSlotAt(Path slot, Path work) {
        return restoreSlotAt(slot, slot.getParent(), work);
    }

    /** savesParent: the game mode folder in Saves that holds the main world. */
    static String restoreSlotAt(Path slot, Path savesParent, Path work) {
        if (!Files.isDirectory(slot)) {
            return "ERR:not_found:" + slot.getFileName();
        }
        String base = baseWorldName(slot);
        String rel = savesParent.getFileName() + File.separator;
        if (slotNameOf(slot, base) == null) {
            // The main world itself, or not a slot of ours: load it as it is.
            return "OK:" + rel + slot.getFileName();
        }
        Path main = savesParent.resolve(base);
        if (Files.exists(main) && readMarker(main) != null) {
            return "ERR:foreign:" + main.getFileName();
        }

        long stamp = System.nanoTime();
        Path fresh = work.resolve("new-" + stamp);
        Path old = work.resolve("old-" + stamp);
        try {
            // Always a full copy: the main world is played and written to, so
            // it must never share a file with the slot.
            System.out.println("[SaveSlots] Copied " + copyRecursively(slot, fresh, null));
            Files.deleteIfExists(fresh.resolve(MARKER_FILE));
            Files.deleteIfExists(fresh.resolve(INFO_FILE));
            Files.createDirectories(savesParent);
        } catch (IOException e) {
            deleteQuietly(fresh);
            System.out.println("[SaveSlots] Copy failed, nothing changed: " + e);
            return "ERR:io:" + e.getMessage();
        }
        try {
            if (Files.exists(main)) {
                Files.move(main, old, StandardCopyOption.ATOMIC_MOVE);
                try {
                    Files.move(fresh, main, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(old, main, StandardCopyOption.ATOMIC_MOVE);
                    throw e;
                }
                discard(work, old);
            } else {
                Files.move(fresh, main, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            deleteQuietly(fresh);
            System.out.println("[SaveSlots] Swap failed, main world kept: " + e);
            return "ERR:io:" + e.getMessage();
        }
        System.out.println("[SaveSlots] Restored " + slot + " into " + main);
        return "OK:" + rel + main.getFileName();
    }

    private static Path currentSave() {
        String dir = ZomboidFileSystem.instance.getCurrentSaveDir();
        if (dir == null) {
            return null;
        }
        Path path = new File(dir).toPath().toAbsolutePath().normalize();
        return Files.isDirectory(path) ? path : null;
    }

    /** The world a save belongs to: its marker for a slot, otherwise its own folder name. */
    private static String baseWorldName(Path save) {
        String marked = readMarker(save);
        if (marked != null) {
            return marked;
        }
        // Slots made by 1.0.0 have no marker: "<world> - <slot>" next to a "<world>" folder.
        String name = save.getFileName().toString();
        for (int idx = name.indexOf(SEPARATOR); idx > 0; idx = name.indexOf(SEPARATOR, idx + 1)) {
            if (Files.isDirectory(save.resolveSibling(name.substring(0, idx)))) {
                return name.substring(0, idx);
            }
        }
        return name;
    }

    /** Slot name if dir is a slot of the given world, otherwise null. */
    private static String slotNameOf(Path dir, String base) {
        String name = dir.getFileName().toString();
        String prefix = base + SEPARATOR;
        if (!name.startsWith(prefix) || name.length() == prefix.length()) {
            return null;
        }
        String marked = readMarker(dir);
        // Slots made by 1.0.0 have no marker; the name prefix identifies them.
        if (marked != null && !marked.equals(base)) {
            return null;
        }
        return name.substring(prefix.length());
    }

    /**
     * The folder of an existing slot of this world, or null. One in the chosen
     * folder wins over one of the same name in Saves (left over if removing it failed).
     */
    private static Path findSlot(Path current, Path folder, String base, String slotName) {
        if (slotName == null || slotName.isEmpty() || slotName.contains("/") || slotName.contains("\\")
                || slotName.contains("..")) {
            return null;
        }
        Path found = null;
        for (Path parent : slotParents(current, folder)) {
            Path dir = parent.resolve(base + SEPARATOR + slotName).normalize();
            if (dir.getParent().equals(parent) && Files.isDirectory(dir) && !isLink(dir) && slotNameOf(dir, base) != null) {
                found = dir;
            }
        }
        return found;
    }

    /** This world's slots by lower-case name, from Saves and then the chosen folder, which wins. */
    private static Map<String, Path> slotsOf(Path current, Path folder, String base) throws IOException {
        Map<String, Path> slots = new LinkedHashMap<>();
        for (Path parent : slotParents(current, folder)) {
            if (!Files.isDirectory(parent)) {
                continue;
            }
            // Links are only there for the game's menu (see syncLinks); the slot itself is in the save folder.
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(parent, d -> Files.isDirectory(d) && !isLink(d))) {
                for (Path dir : dirs) {
                    String slot = slotNameOf(dir, base);
                    if (slot != null) {
                        slots.put(slot.toLowerCase(Locale.ROOT), dir);
                    }
                }
            }
        }
        return slots;
    }

    /** Where slots of the world being played can be: its folder in Saves, then the chosen folder. */
    private static List<Path> slotParents(Path current, Path folder) {
        List<Path> parents = new ArrayList<>();
        parents.add(current.getParent());
        if (folder != null) {
            parents.add(slotParent(current, folder));
        }
        return parents;
    }

    /** Where new copies go: "<folder>\<game mode>", or next to the world in Saves. */
    private static Path slotParent(Path current, Path folder) {
        return folder == null ? current.getParent()
                : folder.resolve(current.getParent().getFileName().toString()).normalize();
    }

    /**
     * Sets where new saves go: a folder the player chose, or "" for the
     * game's Saves folder. Returns "OK:<folder>" ("OK:" for Saves) or
     * "ERR:<reason>:<folder>"; after an error saves go to Saves.
     */
    public static String setSaveFolder(String folder) {
        String text = folder == null ? "" : folder.trim();
        // Explorer's "Copy as path" adds quotes.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        saveFolder = null;
        saveFolderError = "";
        if (text.isEmpty()) {
            return "OK:";
        }
        String result = checkSaveFolder(text, savesDir());
        if (result.startsWith("OK:")) {
            saveFolder = Path.of(result.substring(3));
        } else {
            saveFolderError = result;
        }
        System.out.println("[SaveSlots] Save folder \"" + text + "\": " + result);
        return result;
    }

    /** "" for Saves, "OK:<folder>", or "ERR:<reason>:<folder>" when the chosen folder is not used. */
    public static String saveFolderStatus() {
        if (!saveFolderError.isEmpty()) {
            return saveFolderError;
        }
        if (saveFolder == null) {
            return "";
        }
        return Files.isDirectory(saveFolder) ? "OK:" + saveFolder : "ERR:missing:" + saveFolder;
    }

    /** Checks a folder picked in the folder picker without using it yet: "OK:<folder>" or "ERR:<reason>:<folder>". */
    public static String checkFolder(String folder) {
        return folder == null || folder.trim().isEmpty() ? "ERR:bad_path:" : checkSaveFolder(folder.trim(), savesDir());
    }

    /**
     * Folders for the folder picker; "" lists the drives. First line
     * "OK:<folder>", then the folder one up ("" for the drives), then one line
     * per folder, "<name>\t<full path>", by name. Hidden folders are left out.
     */
    public static String listFolders(String folder) {
        List<String[]> rows = new ArrayList<>();
        String shown = "";
        String up = "";
        if (folder == null || folder.trim().isEmpty()) {
            for (File root : File.listRoots()) {
                rows.add(new String[] { root.getPath(), root.getPath() });
            }
        } else {
            Path dir;
            try {
                dir = Path.of(folder.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return "ERR:bad_path:" + folder;
            }
            if (!Files.isDirectory(dir)) {
                return "ERR:missing:" + folder;
            }
            shown = dir.toString();
            up = dir.getParent() == null ? "" : dir.getParent().toString();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path d : dirs) {
                    String name = d.getFileName().toString();
                    if (!name.contains("\t") && !name.contains("\n") && !hidden(d)) {
                        rows.add(new String[] { name, d.toString() });
                    }
                }
            } catch (IOException | RuntimeException e) {
                return "ERR:not_readable:" + folder;
            }
            rows.sort(Comparator.comparing((String[] r) -> r[0], String.CASE_INSENSITIVE_ORDER));
        }
        StringBuilder sb = new StringBuilder("OK:").append(shown).append('\n').append(up);
        for (String[] r : rows) {
            sb.append('\n').append(r[0]).append('\t').append(r[1]);
        }
        return sb.toString();
    }

    private static boolean hidden(Path dir) {
        try {
            return Files.isHidden(dir);
        } catch (IOException e) {
            return true;
        }
    }

    /** Makes a folder for the folder picker: "OK:<full path>" or "ERR:<reason>". */
    public static String makeFolder(String parent, String name) {
        String clean = sanitize(name);
        if (clean.isEmpty()) {
            return "ERR:empty_name";
        }
        if (parent == null || parent.trim().isEmpty()) {
            return "ERR:bad_path:";
        }
        try {
            Path dir = Path.of(parent.trim()).toAbsolutePath().normalize();
            Path made = dir.resolve(clean).normalize();
            if (!dir.equals(made.getParent())) {
                return "ERR:bad_path:" + parent;
            }
            if (Files.exists(made)) {
                return "ERR:exists:" + clean;
            }
            Files.createDirectory(made);
            return "OK:" + made;
        } catch (IOException | RuntimeException e) {
            return "ERR:not_writable:" + parent;
        }
    }

    /** The chosen folder if it can be used right now (a removed drive cannot), otherwise null. */
    private static Path usableSaveFolder() {
        Path folder = saveFolder;
        return folder != null && Files.isDirectory(folder) ? folder : null;
    }

    /**
     * The folder in the options, ticked or not ("" for none). Saves already
     * there stay usable after unticking; new saves then go to Saves.
     */
    public static String setLookFolder(String folder) {
        String text = folder == null ? "" : folder.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        lookFolder = null;
        try {
            Path dir = text.isEmpty() ? null : Path.of(text);
            if (dir != null && dir.isAbsolute()) {
                lookFolder = dir.normalize();
            }
        } catch (RuntimeException e) {
            // Not a path: nothing to look in.
        }
        return lookFolder == null ? "" : lookFolder.toString();
    }

    /** Where existing saves are looked for: the chosen folder, or the unticked one. */
    private static Path knownFolder() {
        Path folder = usableSaveFolder();
        if (folder == null) {
            folder = lookFolder;
        }
        if (folder == null || !Files.isDirectory(folder)) {
            return null;
        }
        Path saves = savesDir();
        return saves != null && folder.startsWith(saves) ? null : folder;
    }

    /** Checks a folder typed by the player and creates it. "OK:<folder>" or "ERR:<reason>:<text>". */
    static String checkSaveFolder(String text, Path saves) {
        Path dir;
        try {
            dir = Path.of(text);
        } catch (RuntimeException e) {
            return "ERR:bad_path:" + text;
        }
        // "D:" alone means the current folder of drive D, so a full path is needed.
        if (!dir.isAbsolute()) {
            return "ERR:not_absolute:" + text;
        }
        dir = dir.normalize();
        if (saves != null && dir.startsWith(saves)) {
            return "ERR:in_saves:" + text;
        }
        if (inOneDrive(dir)) {
            return "ERR:onedrive:" + text;
        }
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "saveslots-", ".tmp");
            Files.delete(probe);
        } catch (IOException | RuntimeException e) {
            return "ERR:not_writable:" + text;
        }
        return "OK:" + dir;
    }

    private static Path savesDir() {
        try {
            String dir = ZomboidFileSystem.instance.getSaveDir();
            return dir == null ? null : new File(dir).toPath().toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long savedAt(Path dir, boolean isMain) {
        try {
            Path marker = dir.resolve(isMain ? "map_t.bin" : MARKER_FILE);
            return Files.getLastModifiedTime(Files.exists(marker) ? marker : dir).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String readMarker(Path save) {
        String text = readSmallFile(save.resolve(MARKER_FILE));
        return text == null || text.isEmpty() || text.contains("/") || text.contains("\\") ? null : text;
    }

    /** Info as "key=value|key=value", tabs and newlines removed so it fits one row. */
    private static String readInfo(Path save) {
        String text = readSmallFile(save.resolve(INFO_FILE));
        if (text == null) {
            return "";
        }
        return text.replace("\r", "").replace("\t", " ").replace("\n", "|");
    }

    private static long infoSize(String info, Path dir) {
        for (String part : info.split("\\|")) {
            if (part.startsWith("size=")) {
                try {
                    return Long.parseLong(part.substring(5));
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        return folderSize(dir);
    }

    private static String readSmallFile(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 4096) {
                return null;
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMarker(Path save, String base) {
        try {
            writeMarkerOrThrow(save, base);
        } catch (IOException e) {
            System.out.println("[SaveSlots] Could not write marker in " + save + ": " + e);
        }
    }

    private static void writeMarkerOrThrow(Path save, String base) throws IOException {
        Files.write(save.resolve(MARKER_FILE), base.getBytes(StandardCharsets.UTF_8));
    }

    /** Stores the details Lua sent ("key=value|..."), one per line, plus the slot size. */
    private static void writeInfo(Path save, String info, long size) {
        StringBuilder sb = new StringBuilder();
        if (info != null) {
            for (String part : info.split("\\|")) {
                String line = part.replace("\r", "").replace("\n", " ").replace("\t", " ").trim();
                if (line.contains("=") && !line.startsWith("size=") && line.length() < 200) {
                    sb.append(line).append('\n');
                }
            }
        }
        if (size >= 0) {
            sb.append("size=").append(size).append('\n');
        }
        try {
            Files.write(save.resolve(INFO_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[SaveSlots] Could not write info in " + save + ": " + e);
        }
    }

    private static long folderSize(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Work folder outside Saves (so it never shows in Load Game), on the same drive for renames. */
    private static Path workDir() throws IOException {
        return prepareWorkDir(new File(ZomboidFileSystem.instance.getCacheDir(), "SaveSlotsWork").toPath());
    }

    static Path prepareWorkDir(Path work) throws IOException {
        Files.createDirectories(work);
        // Leftovers from a crash: half-made copies and deleted slots. "old-*" folders are kept in case one is needed.
        for (String pattern : new String[] { "new-*", "del-*" }) {
            try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(work, pattern)) {
                for (Path p : leftovers) {
                    deleteLater(p);
                }
            }
        }
        return work;
    }

    /** Waits for the background chunk writer to finish queued saves. */
    private static boolean flushChunks() {
        ChunkSaveWorker worker = ChunkSaveWorker.instance;
        if (worker == null) {
            return true;
        }
        worker.SaveNow();
        long deadline = System.currentTimeMillis() + CHUNK_FLUSH_TIMEOUT_MS;
        while (worker.saving || !worker.toSaveQueue.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (Character.isLetterOrDigit(cp) || cp == ' ' || cp == '_' || cp == '-'
                    || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
                sb.appendCodePoint(cp);
            }
        });
        String result = sb.toString().trim().replaceAll(" {2,}", " ").replace(SEPARATOR, " ");
        if (result.length() > MAX_NAME_LENGTH) {
            result = result.substring(0, MAX_NAME_LENGTH).trim();
        }
        return result;
    }

    /** What a copy did: files and bytes in the new folder, and how many were linked instead of copied. */
    static final class CopyStats {
        long files, bytes, linkedFiles, linkedBytes, millis;

        @Override
        public String toString() {
            return files + " files (" + (bytes / (1024 * 1024)) + " MB) in " + millis + " ms, "
                    + linkedFiles + " unchanged files (" + (linkedBytes / (1024 * 1024)) + " MB) linked";
        }
    }

    /**
     * Copies the folder. With linkFrom (the previous copy of the same slot), a
     * file whose size and time there match the source is hard linked to it
     * instead of copied. If a link cannot be made, the rest is copied.
     */
    static CopyStats copyRecursively(Path source, Path target, Path linkFrom) throws IOException {
        CopyStats stats = new CopyStats();
        long start = System.currentTimeMillis();
        boolean[] linking = { linkFrom != null };
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = source.relativize(file).toString();
                Path copy = target.resolve(rel);
                stats.files++;
                stats.bytes += attrs.size();
                // The marker and info files are rewritten after the copy, so they are never linked.
                if (linking[0] && !rel.equals(MARKER_FILE) && !rel.equals(INFO_FILE)
                        && unchanged(attrs, linkFrom.resolve(rel))) {
                    try {
                        if (breakLinksForTest) {
                            throw new IOException("links broken for test");
                        }
                        Files.createLink(copy, linkFrom.resolve(rel));
                        stats.linkedFiles++;
                        stats.linkedBytes += attrs.size();
                        return FileVisitResult.CONTINUE;
                    } catch (IOException | UnsupportedOperationException e) {
                        System.out.println("[SaveSlots] Hard links not possible here, copying instead: " + e);
                        linking[0] = false;
                        Files.deleteIfExists(copy);
                    }
                }
                Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        stats.millis = System.currentTimeMillis() - start;
        lastCopy = stats;
        return stats;
    }

    /** True if the earlier copy has the same size and time as the source file. */
    private static boolean unchanged(BasicFileAttributes source, Path earlier) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(earlier, BasicFileAttributes.class);
            return attrs.isRegularFile() && attrs.size() == source.size()
                    && attrs.lastModifiedTime().equals(source.lastModifiedTime());
        } catch (IOException e) {
            return false;
        }
    }

    /** Hard links are skipped in OneDrive folders, which may not sync them well. */
    private static boolean canLink(Path dir) {
        return !inOneDrive(dir);
    }

    private static boolean inOneDrive(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        for (String var : new String[] { "OneDrive", "OneDriveConsumer", "OneDriveCommercial" }) {
            String root = System.getenv(var);
            try {
                if (root != null && !root.isEmpty() && abs.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                    return true;
                }
            } catch (RuntimeException e) {
                return true;
            }
        }
        return false;
    }

    /** Moves a slot out of sight into a work folder on its drive and deletes it in the background. */
    private static void trash(Path slot, Path work) throws IOException {
        Path trash = work.resolve("del-" + System.nanoTime());
        Files.move(slot, trash, StandardCopyOption.ATOMIC_MOVE);
        deleteLater(trash);
    }

    /**
     * Gets rid of an old copy that was just swapped out: renamed to "del-*"
     * (cleaned up after a crash too) and deleted in the background.
     */
    private static void discard(Path work, Path old) {
        Path trash = work.resolve("del-" + old.getFileName());
        try {
            Files.move(old, trash, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(old);
            return;
        }
        deleteLater(trash);
    }

    /** Deletes a folder in the work dir on the background thread (once, even if asked twice). */
    static void deleteLater(Path path) {
        if (!deleting.add(path)) {
            return;
        }
        deleter.execute(() -> {
            try {
                long start = System.currentTimeMillis();
                deleteQuietly(path);
                System.out.println("[SaveSlots] Removed old copy " + path.getFileName() + " in "
                        + (System.currentTimeMillis() - start) + " ms");
            } finally {
                deleting.remove(path);
            }
        });
    }

    /** Tests only: waits until the background deletes asked for so far are done. */
    static void awaitDeletes() throws InterruptedException {
        try {
            deleter.submit(() -> { }).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        } catch (IOException e) {
            System.out.println("[SaveSlots] Could not delete " + path + ": " + e);
        }
    }
}
