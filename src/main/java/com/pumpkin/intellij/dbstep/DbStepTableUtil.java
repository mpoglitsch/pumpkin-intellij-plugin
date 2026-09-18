package com.pumpkin.intellij.dbstep;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * Shared raw-text table-editing helpers for the DB-step schema autocomplete (see {@code
 * DbStepCompletionContributor}/{@code DbStepEnterHandler}). Gherkin's own table PSI has no
 * "insert column"/"insert row" mutator (only {@code GherkinTableRow.deleteCell(int)}), so - like
 * every other table-building code in this plugin ({@code StoreContextValuesEnterHandler},
 * {@code PumpkinProcessInsertHandler.buildTableText}) - this edits the {@link Document} directly.
 */
final class DbStepTableUtil {

    private DbStepTableUtil() {}

    /**
     * Inserts a single-column, two-row (header + one blank data row) table skeleton at {@code
     * insertOffset}, with the caret left one character into the header cell - i.e. right after its
     * opening {@code "| "} - ready to type the first column spec. Matches this plugin's own "empty
     * cell renders as two spaces between pipes" convention (see {@code
     * PumpkinProcessInsertHandler.buildTableText}'s blank value cells).
     */
    static void createTableSkeleton(@NotNull Document document, @NotNull Editor editor,
                                    int insertOffset, @NotNull String indent) {
        String row = indent + "  |  |";
        document.insertString(insertOffset, "\n" + row + "\n" + row);

        int firstColumnOffset = insertOffset + 1 + indent.length() + "  | ".length();
        editor.getCaretModel().moveToOffset(firstColumnOffset);
    }

    /** The whitespace prefix of {@code step}'s own line, used to indent a newly-created table. */
    static @NotNull String indentFor(@NotNull Document document, @NotNull GherkinStep step) {
        return SwitchMethodEditor.indentOf(document.getText(), step.getTextOffset());
    }
}
