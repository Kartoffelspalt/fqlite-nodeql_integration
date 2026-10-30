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
final class SchemaInput extends HBox {
    private final TextField editor;
    record Suggestion(String label, String sql) {}

    private final Supplier<List<Suggestion>> source;
    private final boolean projectionList;
    private final ContextMenu suggestions = new ContextMenu();
    private List<Suggestion> entries = List.of();
    private boolean choosing;

    SchemaInput(TextField editor, String kind, Supplier<List<Suggestion>> source, boolean projectionList) {
        super(3);
        this.editor = editor;
        this.source = source;
        this.projectionList = projectionList;
        getStyleClass().add("schema-input");
        Button browse = new Button("▾");
        browse.getStyleClass().add("schema-suggestions-button");
        browse.setAccessibleText("Suggest existing " + kind);
        browse.setTooltip(new Tooltip("Existing " + kind + " · custom SQL input is also allowed"));
        browse.setFocusTraversable(false);
        browse.setOnAction(event -> {
            entries = source.get();
            showSuggestions(false);
        });
        // Popup menus may take native window focus while the editor remains the scene's
        // focus owner. Hiding on editor.focused=false would immediately close the popup.
        ChangeListener<javafx.scene.Node> focusListener = (observable, previous, focused) -> {
            if (focused == editor && !choosing) {
                entries = source.get();
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
                entries = source.get();
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
        String query = filter ? editor.getText().substring(projectionStart(editor.getText())).strip() : "";
        if (query.startsWith("\"")) query = query.substring(1);
        if (query.endsWith("\"")) query = query.substring(0, query.length() - 1);
        String search = query.replace("\"\"", "\"").toLowerCase(Locale.ROOT);
        suggestions.getItems().clear();
        entries.stream().filter(entry -> entry.label().replace("\"", "").toLowerCase(Locale.ROOT).contains(search.replace("\"", "")))
                .limit(20).forEach(entry -> {
            MenuItem item = new MenuItem(entry.label());
            item.setOnAction(event -> {
                choosing = true;
                // Preserve earlier projections; replace only the final top-level list item.
                String prefix = editor.getText().substring(0, projectionStart(editor.getText()));
                editor.setText(prefix + (prefix.isEmpty() ? "" : " ") + entry.sql());
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
    private int projectionStart(String text) {
        if (!projectionList) return 0;
        int start = 0, depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '[') quote = ']';
            else if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) start = i + 1;
        }
        return start;
    }
}
