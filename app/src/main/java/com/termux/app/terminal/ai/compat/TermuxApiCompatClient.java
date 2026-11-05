package com.termux.app.terminal.ai.compat;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The compat client: {@code app_process <apk> com.termux.app.terminal.ai
 * .compat.TermuxApiCompatClient <Method> [am-args]}, stdin for the methods
 * whose pinned wrapper pipes content (Clipboard set, Notification, Toast,
 * TextToSpeech speak).
 *
 * Transient v1 wire only: one JSON UTF-8 line
 * {@code {"cmd":…,"args":{…}}}, shutdownOutput, read the complete v1
 * response. No protocol=2, no negotiation with side effects, no v2→v1
 * fallback, no broadcast fallback. {@code V2_ENABLED} stays false and
 * {@code INPUT_UNSUPPORTED} stays in AiV2Exchange — this client never opens
 * the v2 path.
 *
 * Exit codes: 0 success; 1 bridge/application error (incl. nested
 * data.error); 2 client usage (unknown method/option/type/value); 3
 * transport (connect/EOF/timeout/oversize); 4 invalid response. On any
 * nonzero exit the stdout is empty and stderr carries exactly one line from
 * the fixed code vocabulary of {@link CompatError} — never bridge text,
 * exception messages, paths or argv values. No retry after the request has
 * been sent.
 *
 * NOT ACTIVATED: this client is an internal foundation. Nothing installs or links
 * this entry point (installer/launcher/alias wiring is a follow-up layer), and this class
 * never writes under $PREFIX.
 */
public final class TermuxApiCompatClient {

    private TermuxApiCompatClient() {}

    /**
     * Socket path of the bridge for the production main. Kept in sync with
     * {@code TermuxAiSocketServer.SOCKET_FILE_PATH}; the future launcher will
     * pass it explicitly (system property or argv) so the two constants never
     * drift at runtime — flagged as a CI check.
     */
    static String defaultSocketPath() {
        String fromProperty = System.getProperty("termux.ai.socket");
        if (fromProperty != null && !fromProperty.isEmpty()) return fromProperty;
        return "/data/data/com.termux/files/home/.termux/app/termux-ai/ai.sock";
    }

    public static void main(String[] argv) {
        int exit = run(argv, System.in, System.out, System.err,
            new LocalSocketCompatTransport(defaultSocketPath()), CompatClock.SYSTEM);
        System.out.flush();
        System.exit(exit);
    }

    /**
     * Testable core. All I/O is injected; the harness uses a controlled
     * {@link CompatTransport} peer and clock.
     *
     * @return the process exit code.
     */
    public static int run(String[] argv, InputStream stdin, PrintStream stdout, PrintStream stderr,
                          CompatTransport transport, CompatClock clock) {
        if (argv.length == 0) {
            return fail(stderr, CompatError.E_UNKNOWN_METHOD);
        }
        CompatMethod method = CompatMethod.byName(argv[0]);
        if (method == null) {
            return fail(stderr, CompatError.E_UNKNOWN_METHOD);
        }
        final CompatAmArgs amArgs;
        final CompatMethod.Plan plan;
        final CompatMethod.NativeRequest request;
        try {
            amArgs = CompatAmArgs.parse(argv, 1);
            // Method, options and branch are validated FIRST; stdin is read
            // afterwards and only when this request's plan needs it:
            // Clipboard get, the TTS engines list and any explicit
            // content extra never condition success on a pipe that may never
            // produce input or close.
            plan = method.plan(amArgs);
            String stdinText = plan.readsStdin ? CompatStdin.readAll(stdin) : null;
            request = method.finish(plan, stdinText);
        } catch (CompatAmArgs.ParseException e) {
            return fail(stderr, e.code);
        } catch (CompatMethod.BuildException e) {
            return fail(stderr, e.code);
        } catch (CompatStdin.StdinException e) {
            return fail(stderr, e.code);
        } catch (IOException e) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        } catch (org.json.JSONException e) {
            // checked on Android: frontier catch with an enumerated error
            // and empty stdout (doubles are validated finite before put, so
            // this is defensive, never a raw crash)
            return fail(stderr, CompatError.E_INTERNAL);
        }

        // Request line: capped before the transport, escaping included.
        final String line;
        try {
            line = new JSONObject().put("cmd", request.cmd).put("args", request.args).toString();
        } catch (org.json.JSONException e) {
            return fail(stderr, CompatError.E_INTERNAL);
        }
        if (CompatLimits.utf8Length(line) > CompatLimits.MAX_REQUEST_BYTES) {
            return fail(stderr, CompatError.E_REQUEST_TOO_LARGE);
        }

        final byte[] response;
        try {
            response = transport.exchange(line, clock);
        } catch (CompatTransport.CompatTransportException e) {
            // the code is the whole diagnostic: no exception message, path
            // or peer text is ever echoed (requestSent stays an internal
            // no-retry marker, never a printed claim)
            return fail(stderr, e.code);
        }

        // Validate before any stdout: UTF-8, JSON object, boolean ok.
        String text;
        try {
            text = CompatStdin.decodeStrict(response);
        } catch (IOException e) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        }
        JSONObject envelope;
        try {
            envelope = new JSONObject(text);
        } catch (org.json.JSONException e) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        }
        try {
            if (!envelope.has("ok") || !(envelope.get("ok") instanceof Boolean)) {
                return fail(stderr, CompatError.E_INVALID_RESPONSE);
            }
        } catch (org.json.JSONException e) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        }
        final boolean ok;
        try {
            ok = envelope.getBoolean("ok");
        } catch (org.json.JSONException e) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        }
        if (!ok) {
            // ok:false → the code is the whole diagnostic; the bridge error
            // text is never repeated on stderr
            return fail(stderr, CompatError.E_BRIDGE_ERROR);
        }
        Object data = envelope.opt("data");
        if (!(data instanceof JSONObject)) {
            return fail(stderr, CompatError.E_INVALID_RESPONSE);
        }
        try {
            // the adapter validates against the request's own branch
            // schema, never a guess from the payload shape
            CompatResultAdapter.Outcome outcome = CompatResultAdapter.adapt(plan, (JSONObject) data);
            byte[] bytes = outcome.stdout;
            stdout.write(bytes, 0, bytes.length);
            stdout.flush();
            return 0;
        } catch (CompatResultAdapter.AdapterException e) {
            return fail(stderr, e.code);
        }
    }

    private static int fail(PrintStream stderr, CompatError code) {
        stderr.println(code.stderrLine());
        stderr.flush();
        return exitFor(code);
    }

    private static int exitFor(CompatError code) {
        switch (code) {
            case E_BRIDGE_ERROR:
                return 1;
            case E_UNKNOWN_METHOD:
            case E_UNSUPPORTED_OPTION:
            case E_BAD_TYPE:
            case E_BAD_USAGE:
            case E_BAD_VALUE:
            case E_STDIN_TOO_LARGE:
            case E_REQUEST_TOO_LARGE:
                return 2;
            case E_CONNECT:
            case E_EOF:
            case E_TIMEOUT:
            case E_RESPONSE_TOO_LARGE:
                return 3;
            default:
                return 4;
        }
    }

}
