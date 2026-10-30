package fqlite.nodeql;

import fqlite.base.ThemeManager;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Optional schema suggestions around an ordinary, freely editable SQL input. */
final class TableNameInput extends HBox {
    private final TextField editor;
    private final Supplier<List<String>> tableSource;
    private final ContextMenu suggestions = new ContextMenu();
    private List<String> tables = List.of();
    private boolean choosing;

    TableNameInput(TextField editor, Supplier<List<String>> tableSource) {
        super(3);
        this.editor = editor;
        this.tableSource = tableSource;
        getStyleClass().add("table-name-input");
        Button browse = new Button("▾");
        browse.getStyleClass().add("table-suggestions-button");
        browse.setAccessibleText("Suggest existing tables");
        browse.setTooltip(new Tooltip("Existing tables and views · custom SQL input is also allowed"));
        browse.setFocusTraversable(false);
        browse.setOnAction(event -> {
            tables = tableSource.get();
            showSuggestions(false);
        });
        // Popup menus may take native window focus while the editor remains the scene's
        // focus owner. Hiding on editor.focused=false would immediately close the popup.
        ChangeListener<javafx.scene.Node> focusListener = (observable, previous, focused) -> {
            if (focused == editor && !choosing) {
                tables = tableSource.get();
                showSuggestions(false);
            } else if (focused != editor && focused != browse) suggestions.hide();
        };
        editor.textProperty().addListener((observable, old, value) -> {
            if (!choosing && editor.getScene() != null && editor.getScene().getFocusOwner() == editor) {
                showSuggestions(true);
            }
        });
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && suggestions.isShowing()) {
                suggestions.hide();
                event.consume();
            } else if ((event.getCode() == KeyCode.SPACE && event.isShortcutDown())
                    || (event.getCode() == KeyCode.DOWN && !suggestions.isShowing())) {
                tables = tableSource.get();
                showSuggestions(false);
                event.consume();
            }
        });
        editor.sceneProperty().addListener((observable, old, scene) -> {
            if (old != null) old.focusOwnerProperty().removeListener(focusListener);
            if (scene == null) suggestions.hide();
            else scene.focusOwnerProperty().addListener(focusListener);
        });
        suggestions.setOnShowing(event -> ThemeManager.register(suggestions.getScene()));
        suggestions.setOnHidden(event -> ThemeManager.unregister(suggestions.getScene()));
        getChildren().addAll(editor, browse);
    }

    private void showSuggestions(boolean filter) {
        if (editor.getScene() == null || editor.getScene().getWindow() == null
                || !editor.getScene().getWindow().isShowing()) return;
        String query = filter ? editor.getText().strip() : "";
        if (query.startsWith("\"")) query = query.substring(1);
        if (query.endsWith("\"")) query = query.substring(0, query.length() - 1);
        String search = query.replace("\"\"", "\"").toLowerCase(Locale.ROOT);
        suggestions.getItems().clear();
        tables.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(search)).limit(20).forEach(name -> {
            MenuItem item = new MenuItem(name);
            item.setOnAction(event -> {
                choosing = true;
                // Schema names are identifiers, not SQL expressions. Quote even keywords/spaces.
                editor.setText("\"" + name.replace("\"", "\"\"") + "\"");
                suggestions.hide();
                editor.requestFocus();
                editor.positionCaret(editor.getLength());
                choosing = false;
            });
            suggestions.getItems().add(item);
        });
        if (suggestions.getItems().isEmpty()) suggestions.hide();
        else if (!suggestions.isShowing()) suggestions.show(editor, Side.BOTTOM, 0, 2);
    }
}
