package com.pumpkin.intellij.section;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GherkinSectionFoldingBuilder extends FoldingBuilderEx {

    private static final Pattern SECTION_START =
            Pattern.compile("^#\\s*Section:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_END =
            Pattern.compile("^#\\s*End\\s*$", Pattern.CASE_INSENSITIVE);

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document document, boolean quick) {
        if (!(root instanceof GherkinFile)) return new FoldingDescriptor[0];
        List<Marker> markers = collectMarkers(root);
        List<SectionNode> roots = matchSections(markers);
        List<FoldingDescriptor> descriptors = new ArrayList<>();
        ASTNode fileNode = root.getNode();
        collectDescriptors(roots, fileNode, document, descriptors);
        return descriptors.toArray(FoldingDescriptor[]::new);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "▸ ...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        PsiElement psi = node.getPsi();
        if (psi != null) {
            try {
                Project project = psi.getProject();
                return PumpkinSettingsState.getInstance(project).collapseSectionsByDefault;
            } catch (Exception ignored) {}
        }
        return true;
    }

    private static @NotNull List<Marker> collectMarkers(@NotNull PsiElement root) {
        List<Marker> result = new ArrayList<>();
        root.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                if (element.getFirstChild() != null) return;
                String text = element.getText().trim();
                Matcher sm = SECTION_START.matcher(text);
                if (sm.matches()) {
                    String sectionName = sm.group(1).trim();
                    result.add(new Marker(true, sectionName.isEmpty() ? "Unnamed" : sectionName, element));
                } else if (SECTION_END.matcher(text).matches()) {
                    result.add(new Marker(false, "", element));
                }
            }
        });
        return result;
    }

    private static @NotNull List<SectionNode> matchSections(@NotNull List<Marker> markers) {
        List<SectionNode> rootSections = new ArrayList<>();
        Deque<SectionNode> stack = new ArrayDeque<>();
        for (Marker marker : markers) {
            if (marker.isStart) {
                SectionNode node = new SectionNode(marker.name, marker.element);
                if (stack.isEmpty()) {
                    rootSections.add(node);
                } else {
                    stack.peek().children.add(node);
                }
                stack.push(node);
            } else {
                if (!stack.isEmpty()) {
                    SectionNode top = stack.pop();
                    top.close(marker.element);
                }
            }
        }
        return rootSections;
    }

    /**
     * Creates fold descriptors. Each top-level section gets its own FoldingGroup;
     * all of its descendants share that group.
     *
     * Sharing a group means:
     * - Every fold in the group shows its own placeholder when the group is collapsed,
     *   so child section placeholders (▸ Child) remain visible even when the parent
     *   section is collapsed.
     * - Expanding or collapsing any placeholder in the group expands/collapses them
     *   all, so clicking a child section placeholder also expands the parent.
     *
     * For each section that has children the fold is split into gap segments (the
     * content before, between and after the child sections). The \n immediately
     * before each child's line is left outside every gap, so it (plus any leading
     * whitespace on the child's line) remains visible and provides natural
     * indentation for nested placeholders.
     */
    private static void collectDescriptors(
            @NotNull List<SectionNode> sections,
            @NotNull ASTNode fileNode,
            @NotNull Document document,
            @NotNull List<FoldingDescriptor> out) {

        for (SectionNode section : sections) {
            if (!section.matched) continue;
            FoldingGroup group = FoldingGroup.newGroup("pumpkin-" + section.startOffset);
            collectSection(section, fileNode, document, group, out);
        }
    }

    private static void collectSection(
            @NotNull SectionNode section,
            @NotNull ASTNode fileNode,
            @NotNull Document document,
            @NotNull FoldingGroup group,
            @NotNull List<FoldingDescriptor> out) {

        List<SectionNode> matchedChildren = new ArrayList<>();
        for (SectionNode c : section.children) {
            if (c.matched) matchedChildren.add(c);
        }

        String placeholder = "▸ " + section.name;

        if (matchedChildren.isEmpty()) {
            addDescriptor(fileNode, section.startOffset, section.endOffset, group, placeholder, out);
            return;
        }

        // Split this section into gap segments around its children.
        // The first gap gets the section placeholder; subsequent gaps get an empty one.
        boolean firstGap = true;
        int gapStart = section.startOffset;

        for (SectionNode child : matchedChildren) {
            // End the gap just before the \n that precedes the child's line, so that
            // \n (and any leading whitespace on the child's line) stays visible and
            // provides the visual indentation for the child's placeholder.
            int childLine = document.getLineNumber(child.startOffset);
            int gapEnd = Math.max(document.getLineStartOffset(childLine) - 1, 0);

            if (gapStart < gapEnd) {
                addDescriptor(fileNode, gapStart, gapEnd, group, firstGap ? placeholder : "", out);
                firstGap = false;
            }
            gapStart = child.endOffset;
        }

        // Final gap: from end of last child to end of this section.
        if (gapStart < section.endOffset) {
            addDescriptor(fileNode, gapStart, section.endOffset, group, firstGap ? placeholder : "", out);
        }

        // Recurse into children using the SAME group so clicking any child
        // placeholder also collapses/expands the whole top-level section.
        for (SectionNode child : matchedChildren) {
            collectSection(child, fileNode, document, group, out);
        }
    }

    private static void addDescriptor(
            @NotNull ASTNode fileNode,
            int start,
            int end,
            @Nullable FoldingGroup group,
            @NotNull String placeholder,
            @NotNull List<FoldingDescriptor> out) {
        if (start < end && end <= fileNode.getTextLength()) {
            out.add(new FoldingDescriptor(fileNode, new TextRange(start, end), group, placeholder));
        }
    }

    private static final class Marker {
        final boolean isStart;
        final @NotNull String name;
        final @NotNull PsiElement element;

        Marker(boolean isStart, @NotNull String name, @NotNull PsiElement element) {
            this.isStart = isStart;
            this.name = name;
            this.element = element;
        }
    }

    private static final class SectionNode {
        final @NotNull String name;
        final int startOffset;
        int endOffset;
        boolean matched;
        final @NotNull List<SectionNode> children = new ArrayList<>();

        SectionNode(@NotNull String name, @NotNull PsiElement startElement) {
            this.name = name;
            // Gherkin lexer may attach the preceding \n to the comment token.
            // Find the actual '#' to avoid including that separator newline in the fold.
            String text = startElement.getText();
            int hashIdx = text.indexOf('#');
            this.startOffset = startElement.getTextOffset() + Math.max(hashIdx, 0);
        }

        void close(@NotNull PsiElement endElement) {
            // Strip trailing \n/\r so the fold ends just after the last non-separator
            // character of "# End". The trailing newline remains visible in the editor,
            // keeping consecutive collapsed sections on separate lines.
            String text = endElement.getText();
            int tokenStart = endElement.getTextOffset();
            int end = tokenStart + text.length();
            while (end > tokenStart) {
                char c = text.charAt(end - tokenStart - 1);
                if (c == '\n' || c == '\r') end--;
                else break;
            }
            this.endOffset = end;
            this.matched = true;
        }
    }
}
