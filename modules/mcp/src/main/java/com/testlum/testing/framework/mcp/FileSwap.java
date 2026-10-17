package com.testlum.testing.framework.mcp;

import com.testlum.testing.framework.mcp.constant.ToolMessage;
import com.testlum.testing.framework.mcp.exception.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Puts a set of files in place together. Each new file is written beside its target first, each file that is
 * being replaced is copied to a sibling backup, and only then are the new files moved in. If any step fails the
 * originals are put back and the files this call created are removed. Whatever could not be cleaned up is named.
 */
final class FileSwap {

    private final List<Step> steps = new ArrayList<>();
    private final List<Path> stuck = new ArrayList<>();

    /** One target: its staged text, the backup of what it replaces (if anything) and whether it was moved in. */
    private static final class Step {
        private final Path target;
        private Path staged;
        private Path backup;
        private boolean moved;

        private Step(final Path target) {
            this.target = target;
        }
    }

    static void apply(final Map<Path, String> content) {
        new FileSwap().run(content);
    }

    private void run(final Map<Path, String> content) {
        try {
            for (var e : content.entrySet()) {
                stage(e.getKey(), e.getValue());
            }
            for (Step step : steps) {
                moveIn(step);
            }
        } catch (IOException ex) {
            rollBack();
            throw new ToolException(ex, ToolMessage.CANNOT_WRITE, "scenario files", ex.getMessage() + leftovers());
        }
        finish();
    }

    private void finish() {
        steps.forEach(s -> remove(s.backup));
        if (!stuck.isEmpty()) {
            throw new ToolException(ToolMessage.CANNOT_CLEAN_UP, stuck);
        }
    }

    private void stage(final Path target, final String text) throws IOException {
        Step step = new Step(target);
        steps.add(step);
        Files.createDirectories(target.getParent());
        step.staged = Files.createTempFile(target.getParent(), ".testlum-", ".tmp");
        Files.writeString(step.staged, text, StandardCharsets.UTF_8);
    }

    private void moveIn(final Step step) throws IOException {
        if (Files.exists(step.target)) {
            step.backup = Files.createTempFile(step.target.getParent(), ".testlum-", ".bak");
            Files.copy(step.target, step.backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(step.staged, step.target, StandardCopyOption.REPLACE_EXISTING);
        step.moved = true;
    }

    private void rollBack() {
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            restore(step);
            remove(step.staged);
            remove(step.backup);
        }
    }

    /** Only a target that was moved in has changed: it gets its backup back, or goes if it is new. */
    private void restore(final Step step) {
        if (!step.moved) {
            return;
        }
        try {
            if (step.backup != null) {
                Files.move(step.backup, step.target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(step.target);
            }
        } catch (IOException e) {
            stuck.add(step.target);
        }
    }

    private void remove(final Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            stuck.add(file);
        }
    }

    private String leftovers() {
        return stuck.isEmpty() ? "" : " (could not clean up: " + stuck + ")";
    }
}
