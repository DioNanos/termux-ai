package com.termux.app.terminal.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A model service that records every call and answers from a script. */
final class FakeGenAiPort implements GenAiPort {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<ModelSelection> statusSelections = Collections.synchronizedList(new ArrayList<>());
    final List<ModelSelection> generateSelections = Collections.synchronizedList(new ArrayList<>());
    final List<ModelSelection> downloadSelections = Collections.synchronizedList(new ArrayList<>());
    final List<GenParams> generateParams = Collections.synchronizedList(new ArrayList<>());
    final List<GenParams> streamParams = Collections.synchronizedList(new ArrayList<>());
    final Map<ModelSelection, Integer> status = new HashMap<>();
    final Map<ModelSelection, String> names = new HashMap<>();
    int defaultStatus = AiCoreEngine.STATUS_AVAILABLE;
    Exception generateFailure;
    Exception downloadFailure;
    Exception nameFailure;
    GenResult generateResult = new GenResult("answer", "stop");
    Capabilities capabilities = new Capabilities(4096, true, false, null, true);

    @Override public int checkStatus(ModelSelection selection) {
        calls.add("checkStatus " + selection);
        statusSelections.add(selection);
        return status.getOrDefault(selection, defaultStatus);
    }

    @Override public String baseModelName(ModelSelection selection) throws Exception {
        calls.add("baseModelName " + selection);
        if (nameFailure != null) throw nameFailure;
        return names.getOrDefault(selection, "model-" + selection);
    }

    @Override public Capabilities capabilities(ModelSelection selection) {
        calls.add("capabilities " + selection);
        return capabilities;
    }

    @Override public void download(ModelSelection selection, ProgressListener listener) throws Exception {
        calls.add("download " + selection);
        downloadSelections.add(selection);
        if (downloadFailure != null) throw downloadFailure;
        listener.onBytes(10);
    }

    @Override public GenResult generate(ModelSelection selection, GenParams params) throws Exception {
        calls.add("generate " + selection);
        generateSelections.add(selection);
        generateParams.add(params);
        if (generateFailure != null) throw generateFailure;
        return generateResult;
    }

    @Override public GenResult generateStream(ModelSelection selection, GenParams params, ChunkListener listener) throws Exception {
        calls.add("stream " + selection);
        streamParams.add(params);
        listener.onChunk("a");
        listener.onChunk("b");
        return new GenResult("ab", "stop");
    }

    int modelCalls() { return generateSelections.size() + downloadSelections.size() + streamParams.size(); }
}
