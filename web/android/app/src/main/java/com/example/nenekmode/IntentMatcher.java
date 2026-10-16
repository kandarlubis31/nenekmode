package com.example.nenekmode;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class IntentMatcher {

    private final List<IntentData> intents = new ArrayList<>();
    private final Map<String, Integer> sequentialStates = new HashMap<>();
    private final Random random = new Random();
    private String rawJsonString = "";

    public IntentMatcher(Context context) {
        loadIntents(context);
    }

    public String getJsonString() {
        return rawJsonString;
    }

    private void loadIntents(Context context) {
        try (InputStream is = context.getAssets().open("intents.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
            rawJsonString = jsonStr;

            JSONObject jsonObject = new JSONObject(jsonStr);
            JSONArray jsonArray = jsonObject.getJSONArray("intents");

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                IntentData intent = new IntentData();
                intent.tag = obj.getString("tag");
                intent.responseMode = obj.getString("response_mode");
                intent.priority = obj.optInt("priority", 0);
                intent.action = obj.optString("action", "");

                JSONArray patternsArr = obj.optJSONArray("patterns");
                if (patternsArr != null) {
                    for (int j = 0; j < patternsArr.length(); j++) {
                        intent.patterns.add(patternsArr.getString(j).toLowerCase());
                    }
                }

                JSONArray responsesArr = obj.optJSONArray("responses");
                if (responsesArr != null) {
                    for (int j = 0; j < responsesArr.length(); j++) {
                        intent.responses.add(responsesArr.getString(j));
                    }
                }

                intents.add(intent);
                sequentialStates.put(intent.tag, 0);
            }
        } catch (Exception ignored) {
        }
    }

    public MatchResult match(String input) {
        String lowerInput = input.toLowerCase();
        IntentData bestMatch = null;

        for (IntentData intent : intents) {
            boolean isMatch = false;
            for (String pattern : intent.patterns) {
                if (lowerInput.contains(pattern)) {
                    isMatch = true;
                    break;
                }
            }

            if (isMatch) {
                if (bestMatch == null || intent.priority > bestMatch.priority) {
                    bestMatch = intent;
                }
            }
        }

        if (bestMatch == null) {
            bestMatch = getFallbackIntent();
        }

        if (bestMatch == null) {
            MatchResult emptyResult = new MatchResult();
            emptyResult.tag = "unknown";
            emptyResult.response = "";
            emptyResult.action = "";
            return emptyResult;
        }

        MatchResult result = new MatchResult();
        result.tag = bestMatch.tag;
        result.action = bestMatch.action;
        result.response = selectResponse(bestMatch);

        return result;
    }

    private IntentData getFallbackIntent() {
        for (IntentData intent : intents) {
            if (intent.tag.equals("fallback")) {
                return intent;
            }
        }
        return null;
    }

    private String selectResponse(IntentData intent) {
        if (intent.responses.isEmpty()) return "";

        int size = intent.responses.size();
        if (intent.responseMode.equals("first")) {
            return intent.responses.get(0);
        } else if (intent.responseMode.equals("random")) {
            return intent.responses.get(random.nextInt(size));
        } else if (intent.responseMode.equals("sequential")) {
            Integer stateObj = sequentialStates.get(intent.tag);
            int currentIndex = stateObj != null ? stateObj : 0;
            String response = intent.responses.get(currentIndex);
            sequentialStates.put(intent.tag, (currentIndex + 1) % size);
            return response;
        }

        return intent.responses.get(0);
    }

    public static class IntentData {
        public String tag = "";
        public List<String> patterns = new ArrayList<>();
        public List<String> responses = new ArrayList<>();
        public String responseMode = "first";
        public int priority = 0;
        public String action = "";
    }

    public static class MatchResult {
        public String tag;
        public String response;
        public String action;
    }
}