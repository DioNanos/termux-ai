package com.termux.app.terminal.ai.litert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The chat the model is given: roles are kept, never flattened into one prompt. */
public class LitertChatTest {

    private static JSONArray msgs(String json) throws Exception { return new JSONArray(json); }

    private static void rejects(String json, String part) throws Exception {
        try {
            LitertChat.parse(msgs(json), null);
            fail("expected a rejection of " + json);
        } catch (LitertFailure f) {
            assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
            assertTrue(f.getMessage(), f.getMessage().contains(part));
        }
    }

    @Test
    public void aPromptIsOneUserMessageAndNothingElse() {
        LitertChat chat = LitertChat.ofPrompt("hi");
        assertNull(chat.system);
        assertTrue(chat.history.isEmpty());
        assertEquals(LitertChat.Role.USER, chat.last.role);
        assertEquals("hi", chat.last.text);
    }

    @Test
    public void systemAndDeveloperMessagesBecomeTheSystemInstruction() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"developer\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hello\"}]"), null);
        assertEquals("be brief", chat.system);
        assertTrue(chat.history.isEmpty());
        assertEquals("hello", chat.last.text);
    }

    @Test
    public void severalSystemMessagesAreJoinedInOrderEvenMidConversation() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"system\",\"content\":\"one\"},{\"role\":\"user\",\"content\":\"a\"},"
                + "{\"role\":\"assistant\",\"content\":\"b\"},{\"role\":\"developer\",\"content\":\"two\"},"
                + "{\"role\":\"user\",\"content\":\"c\"}]"), null);
        assertEquals("one\n\ntwo", chat.system);
        assertEquals(2, chat.history.size());
    }

    @Test
    public void theHistoryKeepsItsRolesAndTheLastMessageIsTheOneThatIsSent() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"user\",\"content\":\"17+25\"},{\"role\":\"assistant\",\"content\":\"42\"},"
                + "{\"role\":\"user\",\"content\":\"and /4?\"}]"), null);
        assertEquals(2, chat.history.size());
        assertEquals(LitertChat.Role.USER, chat.history.get(0).role);
        assertEquals("17+25", chat.history.get(0).text);
        assertEquals(LitertChat.Role.MODEL, chat.history.get(1).role);
        assertEquals("42", chat.history.get(1).text);
        assertEquals(LitertChat.Role.USER, chat.last.role);
        assertEquals("and /4?", chat.last.text);
    }

    @Test
    public void textPartsAreJoinedWithANewlineAndOtherPartsAreRejected() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}]"), null);
        assertEquals("text parts are separate blocks: joined with a newline", "a\nb", chat.last.text);
        rejects("[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]}]", "text");
    }

    @Test
    public void anAssistantToolCallAndItsResultKeepTheirLinkAndTheToolName() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"user\",\"content\":\"weather?\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Rome\\\"}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"sunny\"}]"), null);
        assertEquals(2, chat.history.size());
        LitertChat.Msg model = chat.history.get(1);
        assertEquals(LitertChat.Role.MODEL, model.role);
        assertEquals("", model.text);
        assertEquals(1, model.calls.size());
        assertEquals("get_weather", model.calls.get(0).name);
        assertEquals("Rome", model.calls.get(0).arguments.getString("city"));
        assertEquals(LitertChat.Role.TOOL, chat.last.role);
        assertEquals("get_weather", chat.last.toolName);
        assertEquals("sunny", chat.last.text);
    }

    private static final String CALL = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}";

    @Test
    public void aToolResultNeedsItsCallInTheHistoryAndTheNameComesFromThatCall() throws Exception {
        LitertChat ok = LitertChat.parse(msgs("[{\"role\":\"user\",\"content\":\"q\"}," + CALL
            + ",{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"data\"}]"), null);
        assertEquals("read", ok.last.toolName);
        // a name that agrees with the call is fine
        LitertChat named = LitertChat.parse(msgs("[{\"role\":\"user\",\"content\":\"q\"}," + CALL
            + ",{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"name\":\"read\",\"content\":\"data\"}]"), null);
        assertEquals("read", named.last.toolName);
    }

    @Test
    public void anOrphanToolResultIsRejectedAndNeverGetsAMadeUpName() throws Exception {
        // no call with that id at all
        rejects("[{\"role\":\"tool\",\"tool_call_id\":\"call_x\",\"content\":\"r\"}]", "no tool call");
        // an explicit name does not stand in for the missing call
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"tool\",\"tool_call_id\":\"x9\",\"name\":\"read\",\"content\":\"d\"}]", "no tool call");
        // the call is there but under another id
        rejects("[{\"role\":\"user\",\"content\":\"q\"}," + CALL + ",{\"role\":\"tool\",\"tool_call_id\":\"call_2\",\"content\":\"d\"}]", "no tool call");
    }

    @Test
    public void aToolNameThatContradictsItsCallIsRejected() throws Exception {
        rejects("[{\"role\":\"user\",\"content\":\"q\"}," + CALL
            + ",{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"name\":\"write\",\"content\":\"d\"}]", "contradicts");
    }

    @Test
    public void aChatThatEndsWithAnAssistantMessageIsRejectedEvenWhenItIsEmpty() throws Exception {
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"\"}]", "last message");
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":null}]", "last message");
        // a system message after the last turn does not change what the last turn is
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":\"a\"},{\"role\":\"system\",\"content\":\"s\"}]", "last message");
    }

    @Test
    public void anEmptyAssistantMessageIsDropped() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(
            "[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"\"},{\"role\":\"user\",\"content\":\"b\"}]"), null);
        assertEquals(1, chat.history.size());
    }

    @Test
    public void badChatsAreRejectedNotGuessed() throws Exception {
        rejects("[]", "messages");
        rejects("[{\"role\":\"system\",\"content\":\"only\"}]", "last message");
        rejects("[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"}]", "last message");
        rejects("[{\"role\":\"wizard\",\"content\":\"a\"}]", "role");
        rejects("[{\"content\":\"a\"}]", "role");
        rejects("[\"text\"]", "message");
        rejects("[{\"role\":\"user\",\"content\":5}]", "content");
        rejects("[{\"role\":\"user\",\"content\":\"   \"}]", "last message");
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"tool\",\"content\":\"r\"}]", "tool_call_id");
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
            + "[{\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":\"{broken\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]",
            "arguments");
    }

    @Test
    public void toolCallArgumentsMayBeGivenAsAnObjectInsteadOfAString() throws Exception {
        JSONArray wire = msgs("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
            + "[{\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":{\"a\":1}}}]},{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]");
        LitertChat chat = LitertChat.parse(wire, null);
        assertEquals(1, chat.history.get(1).calls.get(0).arguments.getInt("a"));
        assertEquals("t", chat.last.toolName);
    }

    private static final String TOOLS = "[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
        + "\"description\":\"Weather of a city\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}},"
        + "{\"type\":\"function\",\"function\":{\"name\":\"noop\"}}]";
    private static final String ONE_USER = "[{\"role\":\"user\",\"content\":\"q\"}]";

    @Test
    public void toolDefinitionsBecomeTheDescriptionsTheSdkTakes() throws Exception {
        LitertChat chat = LitertChat.parse(msgs(ONE_USER), new JSONArray(TOOLS));
        assertEquals(2, chat.tools.size());
        JSONObject first = new JSONObject(chat.tools.get(0));
        assertEquals("get_weather", first.getString("name"));
        assertEquals("Weather of a city", first.getString("description"));
        assertEquals("city", first.getJSONObject("parameters").getJSONArray("required").getString(0));
        JSONObject second = new JSONObject(chat.tools.get(1));
        assertEquals("noop", second.getString("name"));
        assertEquals("a tool without parameters takes an empty object", "object", second.getJSONObject("parameters").getString("type"));
    }

    @Test
    public void noToolsMeansAnEmptyList() throws Exception {
        assertTrue(LitertChat.parse(msgs(ONE_USER), null).tools.isEmpty());
        assertTrue(LitertChat.parse(msgs(ONE_USER), new JSONArray("[]")).tools.isEmpty());
    }

    @Test
    public void badToolDefinitionsAreRejected() throws Exception {
        for (String bad : new String[] {
            "[{\"type\":\"retrieval\"}]", "[{\"type\":\"function\"}]", "[{\"type\":\"function\",\"function\":{\"name\":\"\"}}]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"bad name\"}}]", "[\"f\"]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"parameters\":\"x\"}}]",
            "[{\"type\":\"function\",\"function\":{\"name\":\"a\"}},{\"type\":\"function\",\"function\":{\"name\":\"a\"}}]"}) {
            try {
                LitertChat.parse(msgs(ONE_USER), new JSONArray(bad));
                fail("expected a rejection of " + bad);
            } catch (LitertFailure f) {
                assertEquals(LitertErrorCode.INVALID_ARGUMENT, f.code);
                assertTrue(f.getMessage(), f.getMessage().contains("tool"));
            }
        }
    }

    @Test
    public void deeplyNestedArgumentsAreRefusedBeforeAnythingRecurses() throws Exception {
        StringBuilder deep = new StringBuilder("{\\\"a\\\":");
        for (int i = 0; i < 200; i++) deep.append('[');
        for (int i = 0; i < 200; i++) deep.append(']');
        deep.append('}');
        rejects("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
            + "[{\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":\"" + deep + "\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]",
            "nested too deeply");
        // a string that merely contains brackets is not nesting
        LitertChat ok = LitertChat.parse(msgs("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
            + "[{\"id\":\"c\",\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"a\\\":\\\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\\\"}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]"), null);
        assertEquals(1, ok.history.get(1).calls.size());
        // a schema nested without end is refused too
        StringBuilder schema = new StringBuilder();
        for (int i = 0; i < 200; i++) schema.append("{\"x\":");
        schema.append("1");
        for (int i = 0; i < 200; i++) schema.append('}');
        try {
            LitertChat.parse(msgs(ONE_USER), new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"parameters\":" + schema + "}}]"));
            fail("expected a rejection of the deep schema");
        } catch (LitertFailure f) {
            assertTrue(f.getMessage(), f.getMessage().contains("nested too deeply"));
        }
    }

    /** The same vectors are checked on the serve side (shell suite): both sides join text parts with a newline by position. */
    @Test
    public void textPartsAreJoinedByPositionSoEmptyPartsKeepTheirSeparators() throws Exception {
        String[][] vectors = {
            {"[\"\",\"a\"]", "\na"},
            {"[\"\",\"\",\"a\"]", "\n\na"},
            {"[\"a\",\"\"]", "a\n"},
            {"[\"a\",\"b\",\"c\"]", "a\nb\nc"},
            {"[\"\"]", ""},
            {"[\" a\",\"b \"]", " a\nb "},
        };
        for (String[] vector : vectors) {
            JSONArray texts = new JSONArray(vector[0]);
            JSONArray parts = new JSONArray();
            for (int i = 0; i < texts.length(); i++) parts.put(new JSONObject().put("type", "text").put("text", texts.getString(i)));
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", parts))
                .put(new JSONObject().put("role", "user").put("content", "q"));
            // a system message is not trimmed or skipped for being short: its text is what the parts say
            assertEquals(vector[0], vector[1].isEmpty() ? null : vector[1], LitertChat.parse(messages, null).system);
        }
    }
}
