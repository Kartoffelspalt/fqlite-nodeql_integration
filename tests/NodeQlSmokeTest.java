// Desktop integration smoke test; private editor state is inspected intentionally.
// No user files or databases are opened. Run with ./gradlew nodeQlSmokeTest.
import fqlite.nodeql.NodeQlBuilderWindow;
import fqlite.base.GUI;
import fqlite.base.ThemeManager;
import fqlite.sql.InMemoryDatabase;
import fqlite.ui.NodeObject;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.stage.Stage;
import javafx.scene.layout.Region;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TableView;
import javafx.scene.control.Button;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;

public class NodeQlSmokeTest extends Application {
    private NodeQlBuilderWindow builder;
    private InMemoryDatabase db;
    private int checks;
    private GUI mainApp;
    private Scene mainScene;
    private Button mainThemeButton;
    private ChangeListener<Boolean> mainThemeListener;
    private Object get(Object object, String key) throws Exception {
        Field field = object.getClass().getDeclaredField(key);
        field.setAccessible(true);
        return field.get(object);
    }
    private Object call(String method, Object... args) throws Exception {
        for (Method candidate : builder.getClass().getDeclaredMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                candidate.setAccessible(true);
                return candidate.invoke(builder, args);
            }
        }
        throw new NoSuchMethodException(method);
    }
    private Object add(String type) throws Exception {
        Class<? extends Enum> templates = (Class<? extends Enum>) Class.forName("fqlite.nodeql.NodeQlBuilderWindow$BlockTemplate");
        call("addBlock", Enum.valueOf(templates, type));
        return get(builder, "selected");
    }
    private void input(Object block, String key, String value) throws Exception {
        ((Map<String, TextField>) get(block, "fields")).get(key).setText(value);
    }
    private void snap(Object block, double y) throws Exception {
        ((Region) block).relocate(3800, y);
        call("snapOrFloat", block);
        call("refreshSql");
    }
    private String sql() throws Exception { return ((TextArea) get(builder, "sqlPreview")).getText(); }
    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private Color background(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return (Color) ((Region) scene.getRoot()).getBackground().getFills().get(0).getFill();
    }

    private void verifyInitialTheme() throws Exception {
        Stage stage = (Stage) get(builder, "stage");
        Button toggle = (Button) get(builder, "themeButton");
        Color light = background(stage.getScene());
        check(light.equals(background(mainScene)), "builder must inherit main app's light palette");
        check(toggle.getText().equals(ThemeManager.toggleLabel()), "initial theme button label");
        check(light.getBrightness() > 0.9, "builder opened dark despite light app setting");

        toggle.fire();
        check(ThemeManager.isDark() && mainApp.isDarkTheme(), "builder toggle must update app theme");
        check(mainThemeButton.getText().equals(toggle.getText()), "main toggle label is stale");
        check(mainThemeButton.getStyle().equals(toggle.getStyle()), "builder must reuse main toggle styling");
        check(background(stage.getScene()).getBrightness() < 0.3, "dark builder background");
        check(background(stage.getScene()).equals(background(mainScene)), "main scene did not change with builder");

        Method mainToggle = GUI.class.getDeclaredMethod("toggleTheme");
        mainToggle.setAccessible(true);
        mainToggle.invoke(mainApp);
        check(!ThemeManager.isDark(), "main app toggle failed");
        check(background(stage.getScene()).equals(light), "main toggle did not update builder");
        check(toggle.getText().equals(ThemeManager.toggleLabel()), "builder toggle label is stale");
        ThemeManager.setDark(true);
    }

    private int registeredScenes() throws Exception {
        Field scenes = ThemeManager.class.getDeclaredField("scenes");
        scenes.setAccessible(true);
        return ((List<?>) scenes.get(null)).size();
    }

    private void captureAfterPulse(Stage stage, File file, Runnable next) {
        // Let CSS, text skins and native window layout settle before taking a real frame.
        PauseTransition settle = new PauseTransition(Duration.millis(250));
        settle.setOnFinished(event -> {
            try {
                var snapshot = stage.getScene().snapshot(null);
                Region canvas = (Region) get(builder, "workspace");
                for (var node : canvas.lookupAll(".label")) {
                    if (!(node instanceof javafx.scene.control.Label label)
                            || !(label.getText().equals("SELECT") || label.getText().equals("EXECUTE QUERY"))) continue;
                    var bounds = label.localToScene(label.getBoundsInLocal());
                    int white = 0;
                    for (int y = Math.max(0, (int) bounds.getMinY()); y < Math.min(snapshot.getHeight(), bounds.getMaxY()); y++) {
                        for (int x = Math.max(0, (int) bounds.getMinX()); x < Math.min(snapshot.getWidth(), bounds.getMaxX()); x++) {
                            Color pixel = snapshot.getPixelReader().getColor(x, y);
                            if (pixel.getRed() > 0.9 && pixel.getGreen() > 0.9 && pixel.getBlue() > 0.9) white++;
                        }
                    }
                    check(white > 20, "block text did not render after theme switch: " + label.getText());
                }
                ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", file);
                next.run();
            } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        });
        settle.play();
    }

    private void verifyResultThemes(Stage stage, File screenshot, Runnable finished) throws Exception {
        Button toggle = (Button) get(builder, "themeButton");
        String query = sql();
        Object selection = get(builder, "selected");
        call("showResultsFullscreen");
        Stage results = (Stage) Window.getWindows().stream()
                .filter(window -> window instanceof Stage s && s.getOwner() == stage
                        && s.getTitle().startsWith("NodeQL Results"))
                .findFirst().orElseThrow();
        check(background(results.getScene()).equals(background(stage.getScene())), "expanded results initial theme");

        toggle.fire();
        check(!ThemeManager.isDark(), "light toggle with results open");
        check(background(results.getScene()).equals(background(stage.getScene())), "expanded results must follow theme changes");
        Region canvas = (Region) get(builder, "workspace");
        check(((Color) canvas.getBackground().getFills().get(0).getFill()).getBrightness() > 0.9, "workspace stayed dark");
        checkBlockTextContrast(stage);
        check(sql().equals(query) && get(builder, "selected") == selection, "theme switch changed workspace state");
        check(((TableView<?>) get(builder, "resultTable")).getItems().size() == 3, "theme switch cleared results");
        int count = registeredScenes();
        NodeQlBuilderWindow other = new NodeQlBuilderWindow(mainApp,
                new TreeItem<>(new NodeObject("second.sqlite", true)), db, stage);
        other.show();
        Stage otherStage = (Stage) get(other, "stage");
        check(background(otherStage.getScene()).equals(background(stage.getScene())), "new builder must inherit current theme");
        ((Button) get(other, "themeButton")).fire();
        check(ThemeManager.isDark() && mainApp.isDarkTheme(), "second builder toggle must update app");
        check(background(stage.getScene()).equals(background(otherStage.getScene())), "open builders are not synchronized");
        check(background(results.getScene()).equals(background(stage.getScene())), "expanded results missed second toggle");
        otherStage.close();
        check(registeredScenes() == count, "closed builder scene leaked");
        check(!((Button) get(other, "themeButton")).textProperty().isBound(), "closed builder toggle binding leaked");
        results.close();
        check(registeredScenes() == count - 1, "closed results scene leaked");
        check(background(stage.getScene()).getBrightness() < 0.3, "dark theme did not restore");
        stage.toFront();
        toggle.fire();
        captureAfterPulse(stage, new File(screenshot.getParentFile(), "editor-light.png"), () -> {
            toggle.fire();
            captureAfterPulse(stage, screenshot, finished);
        });
    }

    private void checkBlockTextContrast(Stage stage) {
        javafx.scene.control.Label blockLabel = (javafx.scene.control.Label) stage.getScene().lookup(".block-label");
        check(blockLabel.getTextFill().equals(Color.WHITE), "light theme reduced palette block text contrast");
    }
    @Override public void start(Stage owner) {
        try {
            db = new InMemoryDatabase("NodeQL verification");
            db.getConnectionObject().createStatement().execute("CREATE TABLE film (id INTEGER, title TEXT)");
            db.getConnectionObject().createStatement().execute("INSERT INTO film VALUES (1, 'First'), (2, 'Second'), (3, 'Third')");
            // Exercise actual GUI theme callbacks without starting importers or reading user settings.
            mainApp = new GUI();
            mainThemeButton = new Button();
            Field toggleField = GUI.class.getDeclaredField("themeBtn");
            toggleField.setAccessible(true);
            toggleField.set(mainApp, mainThemeButton);
            mainThemeListener = (ChangeListener<Boolean>) get(mainApp, "sharedThemeListener");
            ThemeManager.darkProperty().addListener(mainThemeListener);
            mainScene = new Scene(new VBox(mainThemeButton));
            ThemeManager.register(mainScene);
            ThemeManager.setDark(false);
            builder = new NodeQlBuilderWindow(mainApp, new TreeItem<>(new NodeObject("film.sqlite", true)), db, owner);
            builder.show();
            verifyInitialTheme();
            Object select = add("SELECT");
            input(select, "table", "film");
            snap(select, 3880);
            Object where = add("WHERE");
            input(where, "column", "id");
            input(where, "operator", ">");
            input(where, "value", "1");
            snap(where, 3936);
            Object order = add("ORDER_BY");
            input(order, "column", "id");
            input(order, "order", "DESC");
            snap(order, 3986);
            check(sql().contains("WHERE id > 1"), "structured WHERE was not compiled");
            check(sql().contains("ORDER BY id DESC"), "ORDER BY was not compiled");
            check(((Region) order).getLayoutY() == 3986, "chain did not use per-block heights");
            Object reporter = add("COUNT");
            Stage active = (Stage) get(builder, "stage");
            active.getScene().getRoot().applyCss();
            active.getScene().getRoot().layout();
            javafx.scene.layout.StackPane target = ((Map<String, javafx.scene.layout.StackPane>) get(select, "inputSlots")).get("columns");
            javafx.scene.layout.Pane workspace = (javafx.scene.layout.Pane) get(builder, "workspace");
            javafx.geometry.Point2D drop = workspace.sceneToLocal(target.localToScene(target.getWidth() / 2, target.getHeight() / 2));
            ((Region) reporter).relocate(drop.getX() - 40, drop.getY() - 20);
            call("snapOrFloat", reporter);
            check(get(reporter, "reporterOwner") == select, "reporter drop-target hit testing");
            call("refreshSql");
            check(sql().contains("SELECT COUNT(*)"), "nested reporter not compiled");
            check(!((Boolean) get(reporter, "snapped")), "reporter incorrectly joined statement chain");
            Object snapshot = call("captureWorkspaceState");
            call("clearWorkspace");
            call("restoreWorkspaceState", snapshot);
            check(sql().contains("SELECT COUNT(*)"), "undo did not restore reporter input");
            List<?> blocks = (List<?>) get(builder, "blocks");
            reporter = blocks.get(3);
            select = blocks.get(0);
            check(get(reporter, "reporterOwner") == select, "reporter owner not restored");
            call("detachReporter", reporter);
            call("refreshSql");
            check(sql().contains("SELECT *"), "detach did not restore original input");
            input(select, "table_alias", "f");
            check(sql().contains("FROM film AS f"), "table alias missing");
            input(select, "table_alias", "");
            Object join = add("LEFT_JOIN");
            check(((Region) join).getPrefHeight() == 94, "join does not use 86px body + tab");
            Object terminal = add("COMMIT");
            check(((Region) terminal).getPrefHeight() == 48, "terminal has a bottom connector");
            Object sum = add("SUM");
            check(((Region) sum).getPrefHeight() == 44, "expression is not an oval 44px reporter");
            call("clearWorkspace");
            select = add("SELECT");
            input(select, "table", "film");
            snap(select, 3880);
            Object alias = add("ALIAS");
            input(alias, "alias", "timestamp_text");
            Object function = add("SQLITE_FUNCTION");
            input(function, "function", "datetime");
            input(function, "args", "0, 'unixepoch'");
            call("attachReporter", function, alias, "value");
            call("attachReporter", alias, select, "columns");
            call("refreshSql");
            check(sql().contains("datetime(0, 'unixepoch') AS \"timestamp_text\""), "nested function alias SQL");
            Object nestedSnapshot = call("captureWorkspaceState");
            call("clearWorkspace");
            call("restoreWorkspaceState", nestedSnapshot);
            check(sql().contains("datetime(0, 'unixepoch') AS \"timestamp_text\""), "nested reporter undo tree");
            try (var statement = db.getConnectionObject().createStatement(); var result = statement.executeQuery(sql())) {
                check(result.next() && result.getString(1).equals("1970-01-01 00:00:00"), "SQLite function execution");
            }
            select = ((List<?>) get(builder, "blocks")).get(0);
            input(select, "select_mode", "DISTINCT");
            check(sql().startsWith("SELECT DISTINCT"), "distinct mode");
            Object limit = add("LIMIT");
            input(limit, "count", "1");
            input(limit, "offset", "1");
            snap(limit, 3936);
            check(sql().contains("LIMIT 1 OFFSET 1"), "limit offset compilation");
            Object text = add("TEXT");
            input(text, "text", "O'Brien");
            check(call("reporterSql", text).equals("'O''Brien'"), "text literal quoting");
            call("clearWorkspace");
            select = add("SELECT");
            input(select, "table", "film");
            snap(select, 3880);
            reporter = add("COLUMN");
            input(reporter, "column", "*");
            call("attachReporter", reporter, select, "columns");
            order = add("ORDER_BY");
            input(order, "column", "id");
            input(order, "order", "DESC");
            snap(order, 3936);
            call("refreshSql");
            call("executeQuery");
            Timeline wait = new Timeline();
            int[] attempts = {0};
            wait.getKeyFrames().add(new KeyFrame(Duration.millis(200), event -> {
                try {
                    if (get(builder, "lastResult") == null && ++attempts[0] < 40) return;
                    wait.stop();
                    TableView<?> table = (TableView<?>) get(builder, "resultTable");
                    check(table.getItems().size() == 3, "inline query result row count");
                    check(table.getColumns().size() == 2, "inline query result columns");
                    check(((List<?>) table.getItems().get(0)).get(0).equals("3"), "result sort order");
                    Stage stage = (Stage) get(builder, "stage");
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    for (Object block : (List<?>) get(builder, "blocks")) {
                        Region content = (Region) get(block, "content");
                        check(content.getLayoutX() + content.getWidth() <= ((Region) block).getWidth() + 1,
                                "block content exceeds shape width");
                    }
                    File screenshot = new File(System.getProperty("nodeql.screenshot", "build/reports/nodeql/editor.png"));
                    java.nio.file.Files.createDirectories(screenshot.getAbsoluteFile().getParentFile().toPath());
                    verifyResultThemes(stage, screenshot, () -> {
                        try {
                            db.getConnectionObject().close();
                            stage.close();
                            ThemeManager.unregister(mainScene);
                            ThemeManager.darkProperty().removeListener(mainThemeListener);
                            check(registeredScenes() == 0, "builder or main test scene was not unregistered");
                            System.out.println("NODEQL VERIFICATION PASSED: " + checks + " checks; " + screenshot.getAbsolutePath());
                            Platform.exit();
                        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
                    });
                } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
            }));
            wait.setCycleCount(Timeline.INDEFINITE);
            wait.play();
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    public static void main(String[] args) { launch(args); }
}

class NodeQlSmokeTestLauncher {
    public static void main(String[] args) { Application.launch(NodeQlSmokeTest.class, args); }
}
