package ru.nsu.ccfit.zuev.osu;

import static com.osudroid.data.BeatmapsKt.BeatmapInfo;

import android.util.Log;

import com.osudroid.beatmaps.BeatmapCache;
import com.osudroid.beatmaps.DifficultyCalculationManager;
import com.osudroid.data.BeatmapSetInfo;
import com.osudroid.data.DatabaseManager;
import com.osudroid.beatmaps.parser.BeatmapParser;
import com.osudroid.skillcourse.recovery.CourseLibraryEvents;
import kotlin.io.FilesKt;
import org.jetbrains.annotations.Nullable;
import ru.nsu.ccfit.zuev.osu.helper.FileUtils;
import ru.nsu.ccfit.zuev.osu.helper.StringTable;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.osudroid.data.BeatmapInfo;


public class LibraryManager {


    private static volatile List<BeatmapSetInfo> library = new ArrayList<>();

    private static int currentIndex = 0;

    private static final ArrayList<BeatmapInfo> pendingBeatmaps = new ArrayList<>();



    private LibraryManager() {
    }


    private static boolean checkDirectory(String path) {

        var directory = new File(path);

        if (!directory.exists()) {

            if (!directory.mkdir()) {
                ToastLogger.showText(StringTable.format(com.osudroid.resources.R.string.message_error_createdir, directory.getPath()), true);
                return false;
            }

            try {
                //noinspection ResultOfMethodCallIgnored
                new File(directory, ".nomedia").createNewFile();

            } catch (IOException e) {
                Log.e("LibraryManager", "Failed to create .nomedia file", e);
            }
        }

        return true;
    }


    public static void loadLibrary() {
        loadLibrary(Collections.emptyList());
    }

    /**
     * Reloads the committed library and publishes one update signal carrying the directories that
     * were explicitly rescanned.
     *
     * Callers must not publish another library event after this method returns.
     */
    public static void loadLibrary(@Nullable Collection<String> affectedDirectories) {

        if (!FileUtils.canUseSD() || !checkDirectory(Config.getScorePath()) && !checkDirectory(Config.getBeatmapPath())) {
            return;
        }

        currentIndex = 0;
        library = DatabaseManager.getBeatmapInfoTable().getBeatmapSetList();

        DifficultyCalculationManager.calculateDifficulties();

        // The in-memory library and its Room rows are now a coherent committed snapshot. Publish
        // the single library-update signal here so deletes, clear-database and ordinary imports all
        // invalidate cached course availability.
        CourseLibraryEvents.importCompleted(
                affectedDirectories == null
                        ? Collections.<String>emptyList()
                        : affectedDirectories);
    }

    /**
     * Scans the beatmap directory to find differences between the database and the file system. If any differences are
     * found, the database is updated accordingly.
     *
     * @param forceUpdateDirectories The directories to force update, even if they are already in the database.
     *                               Can be {@code null}.
     */
    public static synchronized void scanDirectory(@Nullable Collection<String> forceUpdateDirectories) {

        if (!checkDirectory(Config.getBeatmapPath())) {
            return;
        }

        var directories = new File(Config.getBeatmapPath()).listFiles(File::isDirectory);

        if (directories == null) {
            return;
        }

        // Runs the whole scan on this thread. Synchronized, so two scans (e.g. of two activity instances) never
        // run on the shared pending list at once.
        new LibraryDatabaseManager(directories, forceUpdateDirectories).start();
    }


    public static void clearDatabase() {
        DatabaseManager.getBeatmapInfoTable().deleteAll();
        loadLibrary();
        currentIndex = 0;
    }

    public static void deleteBeatmapSet(BeatmapSetInfo beatmapSet) {
        FilesKt.deleteRecursively(new File(beatmapSet.getPath()));
        DatabaseManager.getBeatmapInfoTable().deleteBeatmapSet(beatmapSet.getDirectory());
        BeatmapCache.invalidate(beatmapSet);
        loadLibrary();
    }

    private static void scanBeatmapSetFolder(File directory) {

        var osuFiles = directory.listFiles((dir, name) -> name.endsWith(".osu"));

        if (osuFiles == null) {
            if (Config.isDeleteUnimportedBeatmaps()) {
                FilesKt.deleteRecursively(directory);
            }
            return;
        }

        var beatmapsFound = 0;

        for (var osuFile : osuFiles) {
            try {
                var data = new BeatmapParser(osuFile).parse(false);
                var beatmapInfo = BeatmapInfo(data, directory.lastModified(), false);

                pendingBeatmaps.add(beatmapInfo);
                beatmapsFound++;
            } catch (Exception e) {
                Log.e("LibraryManager", "Failed to parse beatmap file: " + osuFile.getPath(), e);
                if (Config.isDeleteUnimportedBeatmaps()) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        osuFile.delete();
                    } catch (Exception ignored) {}
                }
            }
        }

        if (Config.isDeleteUnimportedBeatmaps() && beatmapsFound == 0) {
            FilesKt.deleteRecursively(directory);
        }
    }


    public static List<BeatmapSetInfo> getLibrary() {
        return library;
    }

    public static void shuffleLibrary() {
        Collections.shuffle(library);
        currentIndex = 0;
    }

    public static int getSizeOfBeatmaps() {
        return library.size();
    }


    public static BeatmapSetInfo getCurrentBeatmapSet() {
        return library.get(currentIndex);
    }

    public static BeatmapSetInfo selectNextBeatmapSet() {

        if (library.isEmpty()) {
            currentIndex = 0;
            return null;
        }

        currentIndex = ++currentIndex % library.size();
        return library.get(currentIndex);
    }

    public static BeatmapSetInfo selectPreviousBeatmapSet() {

        if (library.isEmpty()) {
            currentIndex = 0;
            return null;
        }

        currentIndex = currentIndex == 0 ? library.size() - 1 : --currentIndex;
        return library.get(currentIndex);
    }


    public static void findBeatmapSetIndex(BeatmapInfo info) {

        for (int i = 0; i < library.size(); i++) {

            if (library.get(i).getDirectory().equals(info.getSetDirectory())) {
                currentIndex = i;
                return;
            }
        }

        currentIndex = 0;
    }


    @Nullable
    public static BeatmapInfo findBeatmapByMD5(String md5) {

        if (md5 == null) {
            return null;
        }

        for (int i = library.size() - 1; i >= 0; i--) {
            var beatmaps = library.get(i).getBeatmaps();

            for (int j = beatmaps.size() - 1; j >= 0; j--) {
                var beatmap = beatmaps.get(j);

                if (md5.equals(beatmap.getMD5())) {
                    return beatmap;
                }
            }
        }

        return null;
    }


    /**
     * Removes rows whose `.osu` file no longer exists inside directories that were explicitly
     * reparsed. This handles a renamed difficulty, whose new `(filename, setDirectory)` key would
     * otherwise be inserted alongside the old row by the REPLACE policy.
     */
    private static void reconcileForcedDirectories(@Nullable Collection<String> forceDirectories,
                                                   Collection<BeatmapInfo> parsedBeatmaps) {
        if (forceDirectories == null || forceDirectories.isEmpty()) {
            return;
        }

        for (var directory : forceDirectories) {
            var expectedFilenames = new HashSet<String>();

            for (var beatmap : parsedBeatmaps) {
                if (directory.equals(beatmap.getSetDirectory())) {
                    expectedFilenames.add(beatmap.getFilename());
                }
            }

            try {
                var dao = DatabaseManager.getBeatmapInfoTable();

                for (var existing : dao.getBeatmapsInSet(directory)) {
                    if (!expectedFilenames.contains(existing.getFilename())) {
                        dao.deleteByKey(existing.getFilename(), directory);
                    }
                }
            } catch (Exception e) {
                Log.e("LibraryManager", "Failed to reconcile forced beatmap directory: " + directory, e);
            }
        }
    }


    private static final class LibraryDatabaseManager {


        private final File[] directories;

        private final @Nullable Collection<String> forceUpdateDirectories;

        private final ExecutorService executors;


        private int directoryCount;

        private int fileCached = 0;


        private LibraryDatabaseManager(File[] directories, @Nullable Collection<String> forceUpdateDirectories) {

            this.directories = directories;
            this.directoryCount = directories.length;
            this.forceUpdateDirectories = forceUpdateDirectories;
            this.executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        }


        public void start() {

            int optimalChunkSize = (int) Math.ceil((double) directoryCount / Runtime.getRuntime().availableProcessors());

            for (int i = 0; i < directories.length; i += optimalChunkSize) {

                var directoriesSlice = Arrays.copyOfRange(directories, i, Math.min(i + optimalChunkSize, directories.length));

                executors.submit(() -> {

                    for (int i1 = directoriesSlice.length - 1; i1 >= 0; i1--) {

                        var directory = directoriesSlice[i1];

                        if ((forceUpdateDirectories == null || !forceUpdateDirectories.contains(directory.getName())) &&
                                DatabaseManager.getBeatmapInfoTable().isBeatmapSetImported(directory.getName())) {
                            directoryCount--;
                            continue;
                        }

                        GlobalManager.getInstance().setLoadingProgress(50 + 50 * fileCached / directoryCount);
                        GlobalManager.getInstance().setInfo("Loading " + directory.getName() + "...");

                        ToastLogger.setPercentage(fileCached * 100f / directoryCount);
                        fileCached++;

                        scanBeatmapSetFolder(directory);
                    }
                });
            }

            executors.shutdown();

            try {
                if (!executors.awaitTermination(1, TimeUnit.HOURS)) {
                    Log.e("LibraryManager", "Timeout while waiting for executor termination.");
                }
            } catch (InterruptedException e) {
                Log.e("LibraryManager", "Failed while waiting for executor termination.", e);
            }

            try {
                var parsedBeatmaps = new ArrayList<>(pendingBeatmaps);

                DatabaseManager.getBeatmapInfoTable().insertAll(parsedBeatmaps);
                reconcileForcedDirectories(forceUpdateDirectories, parsedBeatmaps);
                pendingBeatmaps.clear();
            } catch (Exception e) {
                Log.e("LibraryManager", "Failed to insert beatmaps into database.", e);
                return;
            }

            var missingDirectories = new ArrayList<>(DatabaseManager.getBeatmapInfoTable().getBeatmapSetPaths());

            for (var i = missingDirectories.size() - 1; i >= 0; i--) {

                if (new File(Config.getBeatmapPath(), missingDirectories.get(i)).exists()) {
                    missingDirectories.remove(i);
                }
            }

            if (!missingDirectories.isEmpty()) {
                DatabaseManager.getBeatmapInfoTable().deleteAllBeatmapSets(missingDirectories);
            }
        }

    }
}
