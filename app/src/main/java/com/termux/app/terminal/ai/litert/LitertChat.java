package com.termux.app.terminal.ai.litert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The conversation the model is given, with its roles: a system instruction, a history and the one message that is
 * sent. It is parsed from OpenAI-style messages and checked, never guessed: a chat that cannot be sent is rejected.
 */
public final class LitertChat {

    public enum Role { USER, MODEL, TOOL }

    /** A tool call the model made: the arguments are a JSON object. */
    public static final class Call {
        /** The id the caller gave the call; empty when it gave none. */
        public final String id;
        public final String name;
        public final JSONObject arguments;

        public Call(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    public static final class Msg {
        public final Role role;
        public final String text;
        /** The calls a MODEL message made; empty otherwise. */
        public final List<Call> calls;
        /** For a TOOL message: the tool that produced the result. */
        public final String toolName;

        Msg(Role role, String text, List<Call> calls, String toolName) {
            this.role = role;
            this.text = text;
            this.calls = calls;
            this.toolName = toolName;
        }
    }

    /** The system instruction (system and developer messages, in order), or null when there is none. */
    public final String system;
    /** Everything before the message that is sent, in order. */
    public final List<Msg> history;
    /** The message the model answers: a user message or a tool result. */
    public final Msg last;
    /** The tools the model may call, each as the JSON description the SDK takes: name, description, parameters. */
    public final List<String> tools;

    private LitertChat(String system, List<Msg> history, Msg last, List<String> tools) {
        this.system = system;
        this.history = Collections.unmodifiableList(history);
        this.last = last;
        this.tools = Collections.unmodifiableList(tools);
    }

    /** A single user message and nothing else: what a plain prompt means. */
    public static LitertChat ofPrompt(String prompt) {
        return new LitertChat(null, new ArrayList<>(), new Msg(Role.USER, prompt, Collections.emptyList(), null), new ArrayList<>());
    }

    /**
     * @param messages OpenAI chat messages
     * @param tools    OpenAI tool definitions ({@code type: function}); may be null or empty
     */
    public static LitertChat parse(JSONArray messages, JSONArray tools) throws LitertFailure {
        if (messages == null || messages.length() == 0) throw LitertFailure.invalid("messages must be a non-empty array");
        StringBuilder system = new StringBuilder();
        List<Msg> all = new ArrayList<>();
        // The role of the last message that is not a system one, even when it is an empty assistant message that
        // is not replayed: a chat that ends on an assistant turn has nothing to answer.
        String lastRole = "";
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) throw LitertFailure.invalid("message " + i + " must be an object");
            String role = message.optString("role", "");
            if (role.isEmpty()) throw LitertFailure.invalid("message " + i + " has no role");
            if (!role.equals("system") && !role.equals("developer")) lastRole = role;
            switch (role) {
                case "system":
                case "developer": {
                    String text = content(message, i, false);
                    if (text.isEmpty()) break;
                    if (system.length() > 0) system.append("\n\n");
                    system.append(text);
                    break;
                }
                case "user":
                    all.add(new Msg(Role.USER, content(message, i, false), Collections.emptyList(), null));
                    break;
                case "assistant": {
                    String text = content(message, i, true);
                    List<Call> calls = calls(message, i);
                    // An assistant message with nothing in it carries no information: it is not replayed.
                    if (text.isEmpty() && calls.isEmpty()) break;
                    all.add(new Msg(Role.MODEL, text, calls, null));
                    break;
                }
                case "tool": {
                    String id = message.optString("tool_call_id", "");
                    if (id.isEmpty()) throw LitertFailure.invalid("message " + i + " is a tool result without a tool_call_id");
                    // The tool is the one the assistant called under that id: a result is never given a name that
                    // was made up, and an explicit name must agree with the call.
                    String name = nameOfCall(all, id);
                    if (name.isEmpty()) {
                        throw LitertFailure.invalid("message " + i + " is a result for the tool call " + id
                            + ", but no tool call with that id comes before it");
                    }
                    String given = message.optString("name", "");
                    if (!given.isEmpty() && !given.equals(name)) {
                        throw LitertFailure.invalid("message " + i + " names the tool " + given + ", which contradicts the call "
                            + id + " (" + name + ")");
                    }
                    all.add(new Msg(Role.TOOL, content(message, i, true), Collections.emptyList(), name));
                    break;
                }
                default:
                    throw LitertFailure.invalid("message " + i + " has an unsupported role: " + role);
            }
        }
        if (all.isEmpty()) {
            throw LitertFailure.invalid("the last message must be a user message or a tool result; there is none");
        }
        Msg last = all.remove(all.size() - 1);
        if (lastRole.equals("assistant") || last.role == Role.MODEL || (last.role == Role.USER && last.text.trim().isEmpty())) {
            throw LitertFailure.invalid("the last message must be a user message with text or a tool result");
        }
        return new LitertChat(system.length() == 0 ? null : system.toString(), all, last, tools(tools));
    }

    /** Most tools a request may offer: the definitions travel in every prompt, so they are bounded. */
    static final int MAX_TOOLS = 128;
    private static final java.util.regex.Pattern TOOL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private static List<String> tools(JSONArray raw) throws LitertFailure {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) return out;
        if (raw.length() > MAX_TOOLS) throw LitertFailure.invalid("at most " + MAX_TOOLS + " tools may be offered");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject tool = raw.optJSONObject(i);
            if (tool == null || !"function".equals(tool.optString("type", "function"))) {
                throw LitertFailure.invalid("tool " + i + " must be an object of type function");
            }
            JSONObject function = tool.optJSONObject("function");
            String name = function == null ? "" : function.optString("name", "");
            if (!TOOL_NAME.matcher(name).matches()) {
                throw LitertFailure.invalid("tool " + i + " needs a function name of 1 to 128 characters of A-Z a-z 0-9 . _ : -");
            }
            if (!names.add(name)) throw LitertFailure.invalid("tool name " + name + " is offered twice");
            Object parameters = function.opt("parameters");
            if (nestsTooDeeply(parameters, 0)) throw LitertFailure.invalid("tool " + name + " parameters are nested too deeply");
            if (parameters != null && !JSONObject.NULL.equals(parameters) && !(parameters instanceof JSONObject)) {
                throw LitertFailure.invalid("tool " + name + " parameters must be a JSON schema object");
            }
            try {
                JSONObject description = new JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description", ""))
                    .put("parameters", parameters instanceof JSONObject
                        ? parameters
                        : new JSONObject().put("type", "object").put("properties", new JSONObject()));
                out.add(description.toString());
            } catch (JSONException e) {
                throw LitertFailure.invalid("tool " + name + " cannot be described: " + e.getMessage());
            }
        }
        return out;
    }

    /** The deepest nesting accepted in tool arguments and schemas: real ones are a few levels deep. */
    static final int MAX_JSON_DEPTH = 32;

    /** Whether parsed JSON nests deeper than the limit; the walk stops at the limit, so it cannot run away. */
    private static boolean nestsTooDeeply(Object value, int depth) {
        if (depth > MAX_JSON_DEPTH) return true;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) if (nestsTooDeeply(object.opt(keys.next()), depth + 1)) return true;
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (nestsTooDeeply(array.opt(i), depth + 1)) return true;
        }
        return false;
    }

    /** Whether JSON text nests deeper than the limit, counted before it is parsed (the parser recurses). */
    private static boolean nestsTooDeeply(String json) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (ch == '\\') i++;
                else if (ch == '"') inString = false;
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                if (++depth > MAX_JSON_DEPTH) return true;
            } else if (ch == '}' || ch == ']') {
                depth--;
            }
        }
        return false;
    }

    private static String nameOfCall(List<Msg> before, String id) {
        for (int i = before.size() - 1; i >= 0; i--) {
            Msg m = before.get(i);
            if (m.role != Role.MODEL) continue;
            for (int c = 0; c < m.calls.size(); c++) {
                if (id.equals(m.calls.get(c).id)) return m.calls.get(c).name;
            }
        }
        return "";
    }

    private static String content(JSONObject message, int index, boolean mayBeEmpty) throws LitertFailure {
        Object raw = message.opt("content");
        if (raw == null || JSONObject.NULL.equals(raw)) {
            if (mayBeEmpty) return "";
            throw LitertFailure.invalid("message " + index + " has no content");
        }
        if (raw instanceof String) return (String) raw;
        if (raw instanceof JSONArray) {
            JSONArray parts = (JSONArray) raw;
            StringBuilder text = new StringBuilder();
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part == null || !"text".equals(part.optString("type")) || !(part.opt("text") instanceof String)) {
                    throw LitertFailure.invalid("message " + index + " has a content part that is not text; only text is supported");
                }
                // By position, as serve does: an empty part still leaves its separator.
                if (p > 0) text.append('\n');
                text.append(part.optString("text"));
            }
            return text.toString();
        }
        throw LitertFailure.invalid("message " + index + " content must be a string or an array of text parts");
    }

    private static List<Call> calls(JSONObject message, int index) throws LitertFailure {
        JSONArray raw = message.optJSONArray("tool_calls");
        if (raw == null || raw.length() == 0) return Collections.emptyList();
        List<Call> calls = new ArrayList<>();
        for (int c = 0; c < raw.length(); c++) {
            JSONObject call = raw.optJSONObject(c);
            JSONObject function = call == null ? null : call.optJSONObject("function");
            String name = function == null ? "" : function.optString("name", "");
            if (name.isEmpty()) throw LitertFailure.invalid("message " + index + " tool call " + c + " has no function name");
            Object arguments = function.opt("arguments");
            JSONObject parsed;
            try {
                if (arguments == null || JSONObject.NULL.equals(arguments)) parsed = new JSONObject();
                else if (arguments instanceof JSONObject) {
                    if (nestsTooDeeply(arguments, 0)) throw LitertFailure.invalid("message " + index + " tool call " + c + " arguments are nested too deeply");
                    parsed = (JSONObject) arguments;
                }
                else if (arguments instanceof String) {
                    String text = (String) arguments;
                    if (nestsTooDeeply(text)) throw LitertFailure.invalid("message " + index + " tool call " + c + " arguments are nested too deeply");
                    parsed = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
                }
                else throw new JSONException("not an object");
            } catch (JSONException e) {
                throw LitertFailure.invalid("message " + index + " tool call " + c + " has arguments that are not a JSON object");
            }
            calls.add(new Call(call.optString("id", ""), name, parsed));
        }
        return calls;
    }
}
