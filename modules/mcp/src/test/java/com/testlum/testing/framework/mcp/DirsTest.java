package com.testlum.testing.framework.mcp;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirsTest {

    private final Dirs dirs = new Dirs("/work", "C:\\Users\\me\\tests");

    private String resolve(final String dir) throws Exception {
        String json = dir == null ? "{}" : "{\"dir\":\"" + dir.replace("\\", "\\\\") + "\"}";
        return dirs.resolve(JsonMapper.builder().build().readTree(json));
    }

    @Test
    void noDirMeansTheMountedFolder() throws Exception {
        assertEquals("/work", resolve(null));
        assertEquals("/work", resolve(""));
    }

    @Test
    void relativePathsAreUnderIt() throws Exception {
        assertEquals("/work/scenarios", resolve("scenarios"));
    }

    @Test
    void hostPathsUnderTheMountMapBack() throws Exception {
        assertEquals("/work", resolve("C:\\Users\\me\\tests"));
        assertEquals("/work/data", resolve("c:/users/me/tests/data"));
    }

    @Test
    void otherHostPathsAreRefusedWithTheRule() {
        String msg = assertThrows(Tool.ToolError.class, () -> resolve("C:\\Other")).getMessage();
        assertTrue(msg.contains("/work"), msg);
    }

    @Test
    void absoluteContainerPathsPassThrough() throws Exception {
        assertEquals("/work/x", resolve("/work/x"));
    }
}
