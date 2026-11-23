package ru.nsu.ccfit.zuev.osu.menu;

import android.util.Log;

import com.osudroid.data.BeatmapInfo;
import com.osudroid.data.DatabaseManager;
import com.osudroid.multiplayer.Multiplayer;
import com.osudroid.skillcourse.runtime.BoardMode;
import com.osudroid.skillcourse.runtime.BoardModePolicy;
import com.osudroid.skillcourse.runtime.CourseBoardLoader;
import com.osudroid.skillcourse.runtime.CourseBoardRow;
import com.osudroid.skillcourse.runtime.CourseBoardSnapshot;
import com.osudroid.skillcourse.runtime.CourseView;
import com.osudroid.skillcourse.runtime.CourseViewState;
import com.osudroid.utils.Execution;
import com.osudroid.utils.ModUtils;

import org.anddev.andengine.entity.Entity;
import org.anddev.andengine.entity.scene.Scene;
import org.anddev.andengine.entity.sprite.Sprite;
import org.anddev.andengine.entity.text.ChangeableText;
import org.anddev.andengine.entity.text.Text;
import org.anddev.andengine.input.touch.TouchEvent;
import org.anddev.andengine.input.touch.detector.ScrollDetector;
import org.anddev.andengine.input.touch.detector.SurfaceScrollDetector;
import org.anddev.andengine.opengl.texture.region.TextureRegion;
import org.anddev.andengine.util.MathUtils;
import org.jetbrains.annotations.Nullable;

import ru.nsu.ccfit.zuev.osu.*;
import ru.nsu.ccfit.zuev.osu.game.GameHelper;
import ru.nsu.ccfit.zuev.osu.helper.StringTable;
import ru.nsu.ccfit.zuev.osu.online.OnlineManager;
import ru.nsu.ccfit.zuev.osuplus.R;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class ScoreBoard extends Entity implements ScrollDetector.IScrollDetectorListener {
    private final Scene mainScene;
    private final MenuItemListener listener;
    private final ChangeableText loadingText;
    private float percentShow = -1;
    private boolean showOnlineScores = false;
    private volatile BoardMode boardMode = BoardMode.LOCAL_MAP;
    private volatile long loadGeneration = 0;
    private volatile String selectedBeatmapHash;
    private volatile boolean disposed;
    private boolean isScroll = false;

    private final SurfaceScrollDetector mScrollDetector;

    private float maxY = 100500;
    private int pointerId = -1;
    private float initialY = -1;
    private Float touchY;
    private float camY = -146;
    private float velocityY = 0;
    private float secPassed = 0;
    private float tapTime = 0;
    private float height = 0;
    private float downTime = -1;
    private int _scoreID = -1;
    private boolean moved = false;
    private volatile ArrayList<ScoreBoardItem> scoreItems = null;


    private volatile LoadTask currentTask;

    private Runnable currentAvatarTask;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();


    public ScoreBoard(final Scene scene, final Entity layer, final MenuItemListener listener) {
        this.mainScene = scene;
        layer.attachChild(this);

        this.loadingText = new ChangeableText(5, 230, ResourceManager.getInstance().getFont("strokeFont"), "", 160);
        this.attachChild(this.loadingText);

        this.listener = listener;
        this.mScrollDetector = new SurfaceScrollDetector(this);
    }

    private String formatScore(StringBuilder sb, long score) {
        sb.setLength(0);
        sb.append(Math.abs(score));
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, ' ');
        }
        return sb.toString();
    }

    private void initFromOnline(BeatmapInfo beatmapInfo, long generation) {
        loadingText.setText("Loading scores...");

        currentTask = new LoadTask(BoardMode.ONLINE_SCORE, true, generation, beatmapInfo.getMD5(), null) {

            @Override
            public void run() {
                if (!isActive()) {
                    return;
                }

                List<String> scores;

                try {
                    scores = OnlineManager.getInstance().getTop(beatmapInfo.getMD5());
                } catch (OnlineManager.OnlineManagerException e) {
                    Log.e("Scoreboard", "Failed to load scores from online.", e);

                    publish(() -> loadingText.setText("Cannot load scores"));
                    return;
                }

                if (!isActive()) {
                    return;
                }

                final String message = OnlineManager.getInstance().getFailMessage();
                publish(() -> {
                    loadingText.setText(message);

                    var username = OnlineManager.getInstance().getUsername();
                    var items = new ArrayList<ScoreBoardItem>(scores.size());
                    var sb = new StringBuilder();

                    float nextTotal = 0;

                    for (int i = 0; i < scores.size() && isActive(); ++i) {

                        var data = scores.get(i).split("\\s+");

                        if (data.length < 8 || data.length > 9) {
                            continue;
                        }

                        var isInLeaderboard = data.length == 8;
                        var isPersonalBest = data.length == 9 || data[1].equals(username);

                        var scoreID = Integer.parseInt(data[0]);
                        var playerName = isPersonalBest ? username : data[1];
                        var score = Long.parseLong(data[2]);
                        var combo = Integer.parseInt(data[3]);
                        var mark = data[4];
                        var mods = data[5];
                        var accuracy = Float.parseFloat(data[6]);
                        var avatarURL = data[7];
                        var beatmapRank = isPersonalBest && !isInLeaderboard ? Integer.parseInt(data[8]) : (i + 1);

                        sb.setLength(0);
                        var scoreStr = formatScore(sb, score);

                        sb.setLength(0);
                        var titleStr = sb.append('#').append(beatmapRank).append(' ').append(playerName)
                                .append('\n')
                                .append(StringTable.format(com.osudroid.resources.R.string.menu_score, scoreStr, combo))
                                .toString();

                        if (i < scores.size() - 1) {
                            String[] nextData = scores.get(i + 1).split("\\s+");

                            if (nextData.length == 9 || nextData.length == 10) {
                                nextTotal = Integer.parseInt(nextData[2]);
                            }
                        } else {
                            nextTotal = 0;
                        }

                        sb.setLength(0);

                        var modString = "";

                        try {
                            modString = ModUtils.deserializeMods(mods).toDisplayModString(false);
                        } catch (final Exception e) {
                            Log.e("ScoreBoard", "Failed to parse mods from local score.", e);
                        }

                        var diffTotal = score - Math.round(nextTotal);

                        sb.setLength(0);
                        var accStr = sb.append(modString)
                                .append('\n')
                                .append(StringTable.format("%.2f", GameHelper.Round(accuracy * 100, 2))).append('%')
                                .append('\n')
                                .append(nextTotal == 0 ? "-" : ((diffTotal != 0 ? "+" : "") + diffTotal))
                                .toString();

                        if (!isActive()) {
                            return;
                        }

                        if (isPersonalBest) {
                            attachChild(new ScoreItem(avatarExecutor, titleStr, accStr, mark, true, scoreID, avatarURL, playerName, beatmapInfo.getMD5(), true), 0);
                        }

                        if (isInLeaderboard) {
                            attachChild(new ScoreItem(avatarExecutor, titleStr, accStr, mark, true, scoreID, avatarURL, playerName, beatmapInfo.getMD5(), false));

                            var item = new ScoreBoardItem();
                            item.set(beatmapRank, playerName, combo, score, scoreID);
                            items.add(item);
                        }
                    }
                    if (!isActive()) {
                        return;
                    }
                    scoreItems = items;
                    percentShow = 0;
                });
            }
        };
        loadExecutor.submit(currentTask);
    }

    private void initFromLocal(BeatmapInfo beatmap, long generation) {

        currentTask = new LoadTask(BoardMode.LOCAL_MAP, false, generation, beatmap.getMD5(), null) {

            @Override
            public void run() {
                if (!isActive()) {
                    return;
                }

                DatabaseManager.getScoreInfoTable().migrateScores(beatmap.getMD5(), beatmap.getBeatmapDifficulty());
                var scores = DatabaseManager.getScoreInfoTable().getBeatmapLeaderboard(beatmap.getMD5(), beatmap.getBeatmapDifficulty());

                publish(() -> {
                    if (scores.isEmpty() || !isActive()) {

                        // This allows the in-game leaderboard to show even if the local database is empty, it'll append
                        // the player score (because the in-game leaderboard assumes that the board finished loading only
                        // if the scores list isn't null).
                        if (isActive()) {
                            scoreItems = new ArrayList<>();
                        }
                        return;
                    }

                    var items = new ArrayList<ScoreBoardItem>(scores.size());
                    var sb = new StringBuilder();

                    long nextTotalScore;

                    for (int i = 0; i < scores.size() && isActive(); ++i) {

                        var scored = scores.get(i);
                        var score = scored.getScoreInfo();
                        var effectiveScore = scored.getEffectiveScore();

                        sb.setLength(0);
                        var totalScore = formatScore(sb, effectiveScore);

                        sb.setLength(0);
                        var titleStr = sb.append('#').append(i + 1).append(' ').append(score.getPlayerName())
                                .append('\n')
                                .append(StringTable.format(com.osudroid.resources.R.string.menu_score, totalScore, score.getMaxCombo()))
                                .toString();

                        if (i < scores.size() - 1) {
                            nextTotalScore = scores.get(i + 1).getEffectiveScore();
                        } else {
                            nextTotalScore = 0;
                        }

                        sb.setLength(0);

                        var modString = "";

                        try {
                            modString = ModUtils.deserializeMods(score.getMods()).toDisplayModString(false);
                        } catch (final Exception e) {
                            Log.e("ScoreBoard", "Failed to parse mods from local score.", e);
                        }

                        var diffTotalScore = effectiveScore - nextTotalScore;

                        sb.setLength(0);
                        var accStr = sb.append(modString)
                                .append('\n')
                                .append(StringTable.format("%.2f", GameHelper.Round(score.getAccuracy() * 100, 2))).append('%')
                                .append('\n')
                                .append(nextTotalScore == 0 ? "-" : ((diffTotalScore != 0 ? "+" : "") + diffTotalScore))
                                .toString();

                        if (!isActive()) {
                            return;
                        }

                        attachChild(new ScoreItem(avatarExecutor, titleStr, accStr, score.getMark(), false, (int) score.getId(), null, null, beatmap.getMD5(), false));

                        var item = new ScoreBoardItem();
                        item.set(i + 1, score.getPlayerName(), score.getMaxCombo(), effectiveScore, (int) score.getId());
                        items.add(item);
                    }
                    if (!isActive()) {
                        return;
                    }
                    scoreItems = items;
                    percentShow = 0;
                });
            }
        };
        loadExecutor.submit(currentTask);
    }

    public synchronized void init(final BeatmapInfo beatmapInfo) {
        if (disposed) return;
        if (currentTask != null && currentTask.avatarExecutor != null)
            currentTask.avatarExecutor.shutdownNow();

        final BoardMode mode = BoardModePolicy.normalize(
                boardMode,
                OnlineManager.getInstance().isStayOnline(),
                !Multiplayer.isMultiplayer && CourseView.isActive()
        );
        final long generation = ++loadGeneration;
        final CourseViewState courseState = mode.isCourse() ? CourseView.activeState() : null;

        boardMode = mode;
        showOnlineScores = mode == BoardMode.ONLINE_SCORE;
        selectedBeatmapHash = beatmapInfo == null ? null : beatmapInfo.getMD5();
        currentTask = null;
        loadingText.setText("");
        scoreItems = null;

        Execution.updateThread(() -> {
            if (disposed || generation != loadGeneration || boardMode != mode) return;

            detachChildren();
            currentAvatarTask = null;
            attachChild(loadingText);

            if (beatmapInfo == null && mode != BoardMode.COURSE_TOTAL) {
                if (mode == BoardMode.COURSE_MAP) {
                    loadingText.setText("Select an installed course map.\nSwitch to course totals to see attempts.");
                }
                return;
            }

            switch (mode) {
                case ONLINE_SCORE:
                    initFromOnline(beatmapInfo, generation);
                    break;
                case ONLINE_PP:
                    initFromPpUnavailable();
                    break;
                case COURSE_MAP:
                case COURSE_TOTAL:
                    initFromCourse(mode, courseState, beatmapInfo, generation);
                    break;
                default:
                    initFromLocal(beatmapInfo, generation);
                    break;
            }
        });
    }

    private void initFromPpUnavailable() {
        // Invalidate any previous task. The server has no PP field/sort, so never fetch a score
        // and present it as PP.
        currentTask = null;
        loadingText.setText(BoardModePolicy.ONLINE_PP_UNAVAILABLE);
        scoreItems = new ArrayList<>();
    }

    private void initFromCourse(
            BoardMode mode,
            CourseViewState state,
            BeatmapInfo beatmapInfo,
            long generation
    ) {
        if (state == null) {
            loadingText.setText(BoardModePolicy.COURSE_VIEW_UNAVAILABLE);
            return;
        }

        loadingText.setText(mode == BoardMode.COURSE_TOTAL
                ? "Loading course scores..."
                : "Loading course map scores...");

        final String hash = beatmapInfo == null ? null : beatmapInfo.getMD5();
        currentTask = new LoadTask(mode, false, generation, hash, state) {
            @Override
            public void run() {
                if (!isActive()) {
                    return;
                }

                CourseBoardSnapshot snapshot;

                try {
                    snapshot = CourseBoardLoader.load(mode, state, hash);
                } catch (Exception e) {
                    Log.e("ScoreBoard", "Failed to load local course scores.", e);
                    publish(() -> loadingText.setText(BoardModePolicy.COURSE_ATTEMPTS_UNREADABLE));
                    return;
                }

                if (!isActive()) {
                    return;
                }

                publish(() -> {
                    var message = snapshot.getMessage();
                    loadingText.setText(message == null ? "" : message);

                    var rows = snapshot.getRows();
                    for (int i = 0; i < rows.size() && isActive(); ++i) {
                        CourseBoardRow row = rows.get(i);
                        attachChild(new ScoreItem(
                                null,
                                row.getTitle(),
                                row.getDetail(),
                                row.getMark(),
                                false,
                                -1,
                                null,
                                null,
                                hash,
                                false,
                                () -> CourseBoardLoader.open(row)
                        ));
                    }

                    // Course boards are menu-only. Keeping scoreItems null prevents the gameplay HUD
                    // from comparing a single-map score against course totals or EX.
                    if (isActive()) {
                        percentShow = 0;
                    }
                });
            }
        };
        loadExecutor.submit(currentTask);
    }

    @Override
    protected void onManagedUpdate(float pSecondsElapsed)
    {
        super.onManagedUpdate(pSecondsElapsed);
        secPassed += pSecondsElapsed;

        // The first child is always the loading text, and only leaderboard items need to be updated.
        if (getChildCount() <= 1) {
            return;
        }

        if (percentShow == -1) {
            float y = -camY;

            var count = getChildCount();
            for (int i = 0; i < count; i++)
            {
                var child = getChild(i);

                if (!(child instanceof Sprite))
                    continue;

                var sprite = (Sprite) child;
                sprite.setPosition(sprite.getX(), y);
                y += 0.8f * (sprite.getHeight() - 32);
            }

            y += camY;
            camY += velocityY * pSecondsElapsed;
            maxY = y - 0.8f * (Config.getRES_HEIGHT() - 110 - (height - 32));

            if (camY <= -146 && velocityY < 0 || camY > maxY && velocityY > 0) {
                camY -= velocityY * pSecondsElapsed;
                velocityY = 0;
                isScroll = false;
            }

            if (Math.abs(velocityY) > 500 * pSecondsElapsed) {
                velocityY -= 10 * pSecondsElapsed * Math.signum(velocityY);
            } else {
                velocityY = 0;
                isScroll = false;
            }
        } else {
            percentShow += pSecondsElapsed * 4;
            if (percentShow > 1) {
                percentShow = 1;
            }

            var count = getChildCount();
            for (int i = 0; i < count; i++)
            {
                var child = getChild(i);

                if (!(child instanceof Sprite))
                    continue;

                var sprite = (Sprite) child;
                sprite.setPosition(-160, 146 + 0.8f * percentShow * i * (sprite.getHeight() - 32));
            }

            if (percentShow == 1) {
                percentShow = -1;
            }
        }

        if (downTime >= 0) {
            downTime += pSecondsElapsed;
        }

        if (downTime > 0.5f) {
            moved = true;
            if (!Multiplayer.isMultiplayer && _scoreID != -1 && !showOnlineScores) {
                GlobalManager.getInstance().getSongMenu().showDeleteScoreMenu(_scoreID);
            }
            downTime = -1;
        }
    }

    @Override
    public void onScroll(ScrollDetector pScrollDetector, TouchEvent pTouchEvent, float pDistanceX, float pDistanceY) {
        switch (pTouchEvent.getAction()) {
            case TouchEvent.ACTION_DOWN:
                velocityY = 0;
                touchY = pTouchEvent.getY();
                pointerId = pTouchEvent.getPointerID();
                tapTime = secPassed;
                initialY = touchY;
                isScroll = true;
                break;
            case TouchEvent.ACTION_MOVE:
                if (pointerId == -1 || pointerId == pTouchEvent.getPointerID()) {
                    isScroll = true;
                    if (initialY == -1) {
                        velocityY = 0;
                        touchY = pTouchEvent.getY();
                        pointerId = pTouchEvent.getPointerID();
                        tapTime = secPassed;
                        initialY = touchY;
                    }

                    final float dy = pTouchEvent.getY() - touchY;

                    camY -= dy;
                    touchY = pTouchEvent.getY();
                    if (camY <= -146) {
                        camY = -146;
                        velocityY = 0;
                    } else if (camY >= maxY){
                        camY = maxY;
                        velocityY = 0;
                    }
                }
                break;
            default:
                if (pointerId == -1 || pointerId == pTouchEvent.getPointerID()) {
                    touchY = null;

                    if (secPassed - tapTime < 0.001f || initialY == -1) {
                        velocityY = 0;
                        isScroll = false;
                    } else {
                        velocityY = (initialY - pTouchEvent.getY()) / (secPassed - tapTime);
                        isScroll = true;
                    }

                    pointerId = -1;
                    initialY = -1;
                }
                break;
        }
    }

    public boolean isShowOnlineScores() {
        return showOnlineScores;
    }

    public void setShowOnlineScores(boolean showOnlineScores) {
        this.showOnlineScores = showOnlineScores;
        this.boardMode = showOnlineScores ? BoardMode.ONLINE_SCORE : BoardMode.LOCAL_MAP;
    }

    public BoardMode getBoardMode() {
        return boardMode;
    }

    public void setBoardMode(BoardMode boardMode) {
        this.boardMode = boardMode == null ? BoardMode.LOCAL_MAP : boardMode;
        this.showOnlineScores = this.boardMode == BoardMode.ONLINE_SCORE;
    }

    /** Stops detached scenes from publishing rows or keeping their loader threads alive. */
    public synchronized void dispose() {
        disposed = true;
        loadGeneration++;
        if (currentTask != null && currentTask.avatarExecutor != null) {
            currentTask.avatarExecutor.shutdownNow();
        }
        currentTask = null;
        currentAvatarTask = null;
        loadExecutor.shutdownNow();
    }

    @Nullable
    public ArrayList<ScoreBoardItem> getScoreBoardItems() {
        return scoreItems;
    }


    private abstract class LoadTask implements Runnable {

        protected final ExecutorService avatarExecutor;

        private final BoardMode mode;

        private final long generation;

        private final String beatmapHash;

        private final CourseViewState courseState;

        LoadTask(BoardMode mode, boolean fromOnline, long generation, String beatmapHash, CourseViewState courseState) {
            this.avatarExecutor = fromOnline ? Executors.newSingleThreadExecutor() : null;
            this.mode = mode;
            this.generation = generation;
            this.beatmapHash = beatmapHash;
            this.courseState = courseState;
        }

        protected final boolean isActive() {
            if (disposed || currentTask != this || generation != loadGeneration || boardMode != mode) {
                return false;
            }

            if (!Objects.equals(selectedBeatmapHash, beatmapHash)) {
                return false;
            }

            if (!mode.isCourse()) {
                return !CourseView.isActive() && (!mode.isOnline() || OnlineManager.getInstance().isStayOnline());
            }

            if (courseState == null) {
                return false;
            }

            var active = CourseView.activeState();
            return active != null
                    && active.getGeneration() == courseState.getGeneration()
                    && Objects.equals(active.getCourseId(), courseState.getCourseId())
                    && Objects.equals(active.getCourseHash(), courseState.getCourseHash())
                    && Objects.equals(active.getMapByInstalledHash(), courseState.getMapByInstalledHash())
                    && Objects.equals(active.getOutdated(), courseState.getOutdated());
        }

        protected final void publish(Runnable action) {
            Execution.updateThread(() -> {
                if (isActive()) action.run();
            });
        }

    }


    private class ScoreItem extends Sprite {


        private float dx = 0;

        private float dy = 0;

        private TextureRegion avatarTexture;

        private Runnable avatarTask;

        private final ExecutorService avatarExecutor;

        private final String username;

        private final int scoreID;

        private final String hash;

        private final boolean showOnline;

        private final Runnable openAction;


        private ScoreItem(
                ExecutorService avatarExecutor,
                String title,
                String acc,
                String markStr,
                boolean showOnline,
                int scoreID,
                String avaURL,
                String username,
                String hash,
                boolean isPersonalBest) {
            this(avatarExecutor, title, acc, markStr, showOnline, scoreID, avaURL, username, hash, isPersonalBest, null);
        }

        private ScoreItem(
                ExecutorService avatarExecutor,
                String title,
                String acc,
                String markStr,
                boolean showOnline,
                int scoreID,
                String avaURL,
                String username,
                String hash,
                boolean isPersonalBest,
                Runnable openAction) {
            super(-150, 40,  ResourceManager.getInstance().getTexture("menu-button-background").deepCopy());

            this.avatarExecutor = avatarExecutor;
            this.showOnline = showOnline;
            this.username = username;
            this.scoreID = scoreID;
            this.hash = hash;
            this.openAction = openAction;

            var shouldLoadAvatar = showOnlineScores
                    && openAction == null
                    && Config.getLoadAvatar()
                    && avaURL != null
                    && avatarExecutor != null;

            int baseX = shouldLoadAvatar ? 90 : 0;
            var baseY = 0f;

            if (isPersonalBest) {

                var topText = new Text(
                        getWidth() / 2f,
                        0f,
                        ResourceManager.getInstance().getFont("strokeFont"),
                        "Personal Best");

                attachChild(topText);
                baseY = topText.getHeight() + 5;

                topText.setPosition((getWidth() + baseX - topText.getWidth()) / 2f, 20f);
                topText.setScale(0.8f);

                setHeight(baseY + 120);

            } else {
                setHeight(107);
            }

            setScale(0.65f);
            setWidth(724 * 1.1f);
            camY = -146;

            setColor(0, 0, 0);
            setAlpha(0.5f);

            float finalBaseY = baseY;
            final LoadTask owner = currentTask;
            avatarTask = shouldLoadAvatar ? new Runnable() {

                @Override
                public void run() {
                    var texture = ResourceManager.getInstance().getTexture("emptyavatar");

                    if (!avatarExecutor.isShutdown() && OnlineManager.getInstance().loadAvatarToTextureManager(avaURL)) {
                        avatarTexture = ResourceManager.getInstance().getAvatarTextureIfLoaded(avaURL);

                        if (avatarTexture != null)
                            texture = avatarTexture;
                    }

                    final TextureRegion loadedTexture = texture;
                    Execution.updateThread(() -> {
                        if (owner == null || !owner.isActive() || getParent() != ScoreBoard.this) {
                            onDetached();
                        } else {
                            attachChild(new Sprite(55, finalBaseY + 12, 90, 90, loadedTexture));
                        }
                        if (currentAvatarTask == this) currentAvatarTask = null;
                    });
                }
            } : null;


            var text = new Text(baseX + 160, baseY + 20, ResourceManager.getInstance().getFont("font"), title);
            var accText = new Text(670, baseY + 12, ResourceManager.getInstance().getFont("smallFont"), acc);
            var mark = new Sprite(baseX + 80, baseY + 35, ResourceManager.getInstance().getTexture("ranking-" + markStr + "-small"));

            text.setScale(1.2f);
            mark.setScale(1.5f);
            mark.setPosition(baseX + mark.getWidth() / 2 + 60, mark.getY());
            attachChild(text);
            attachChild(accText);
            attachChild(mark);

            mainScene.registerTouchArea(this);
            height = getHeight();
        }

        @Override
        public void onDetached()
        {
            if (avatarTexture != null)
                // Ensure texture unloading happens in the next tick of the
                // update thread to prevent concurrency problems.
                Execution.updateThread(() -> ResourceManager.getInstance().unloadTexture(avatarTexture));

            mainScene.unregisterTouchArea(this);
        }

        @Override
        protected void onManagedUpdate(float pSecondsElapsed)
        {
            super.onManagedUpdate(pSecondsElapsed);

            // This is to avoid loading avatars when the scene was changed (game started or user gone back to main menu).
            if (avatarTask != null && currentAvatarTask == null) {

                var task = avatarTask;
                avatarTask = null;
                currentAvatarTask = task;

                try {
                    avatarExecutor.submit(task);
                } catch (RejectedExecutionException e) {
                    if (currentAvatarTask == task)
                        currentAvatarTask = null;
                }
            }
        }

        @Override
        public boolean onAreaTouched(TouchEvent event, float localX, float localY) {
            mScrollDetector.onTouchEvent(event);
            mScrollDetector.setEnabled(true);

            if (event.isActionDown()) {
                moved = false;
                setAlpha(0.8f);
                listener.stopScroll(getY() + localY);
                dx = localX;
                dy = localY;
                downTime = 0;
                _scoreID = scoreID;
                return true;
            } else if (event.isActionUp() && !moved && !isScroll) {
                downTime = -1;
                setAlpha(0.5f);

                if (openAction != null) {
                    openAction.run();
                    return true;
                }

                if (Multiplayer.isMultiplayer)
                    return true;

                listener.openScore(scoreID, showOnline, username, hash);
                GlobalManager.getInstance().getScoring().setReplayID(scoreID);
                return true;
            } else if (event.isActionOutside() || event.isActionMove() && MathUtils.distance(dx, dy, localX, localY) > 10) {
                downTime = -1;
                setAlpha(0.5f);
                moved = true;
            }
            return false;
        }
    }
}

