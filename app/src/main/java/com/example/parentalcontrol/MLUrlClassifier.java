package com.example.parentalcontrol;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Machine Learning-based URL classifier for adult content detection
 * Implements the trained Naive Bayes model with TF-IDF vectorization
 */
public class MLUrlClassifier {
    private static final String TAG = "MLUrlClassifier";
    
    // Model configuration (from your training)
    private static final int NGRAM_MIN = 3;
    private static final int NGRAM_MAX = 3;
    private static final double ALPHA = 0.0001; // MultinomialNB alpha parameter
    
    // Pre-trained model parameters (loaded from assets)
    private Map<String, Double> featureWeights;
    private Map<String, Double> classLogPriors;
    private Set<String> vocabulary;
    private Set<String> stopWords;
    
    // Categories from your model
    private static final String[] CATEGORIES = {
        "Adult", "Arts", "Business", "Computers", "Games", 
        "Health", "Home", "Kids", "News", "Recreation", 
        "Reference", "Science", "Shopping", "Society", "Sports"
    };
    
    // Adult content threshold
    private static final double ADULT_THRESHOLD = 0.5;
    
    private boolean isModelLoaded = false;
    
    public MLUrlClassifier(Context context) {
        loadPreTrainedModel(context);
    }
    
    /**
     * Load the pre-trained model parameters from assets
     */
    private void loadPreTrainedModel(Context context) {
        try {
            Log.d(TAG, "Loading ML model parameters...");
            
            // Load model parameters from JSON files
            loadVocabulary(context);
            loadFeatureWeights(context);
            loadClassPriors(context);
            loadStopWords(context);
            
            isModelLoaded = true;
            Log.i(TAG, "ML model loaded successfully with " + vocabulary.size() + " features");
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading ML model", e);
            isModelLoaded = false;
            
            // Fallback to simple keyword-based detection
            initializeFallbackModel();
        }
    }
    
    private void loadVocabulary(Context context) throws Exception {
        vocabulary = new HashSet<>();
        InputStream is = context.getAssets().open("ml_model/vocabulary.json");
        String jsonStr = readInputStream(is);
        JSONArray vocabArray = new JSONArray(jsonStr);
        
        for (int i = 0; i < vocabArray.length(); i++) {
            vocabulary.add(vocabArray.getString(i));
        }
    }
    
    private void loadFeatureWeights(Context context) throws Exception {
        featureWeights = new HashMap<>();
        InputStream is = context.getAssets().open("ml_model/feature_weights.json");
        String jsonStr = readInputStream(is);
        JSONObject weightsObj = new JSONObject(jsonStr);
        
        // Load weights for Adult category specifically
        JSONObject adultWeights = weightsObj.getJSONObject("Adult");
        for (String feature : vocabulary) {
            if (adultWeights.has(feature)) {
                featureWeights.put(feature, adultWeights.getDouble(feature));
            }
        }
    }
    
    private void loadClassPriors(Context context) throws Exception {
        classLogPriors = new HashMap<>();
        InputStream is = context.getAssets().open("ml_model/class_priors.json");
        String jsonStr = readInputStream(is);
        JSONObject priorsObj = new JSONObject(jsonStr);
        
        for (String category : CATEGORIES) {
            if (priorsObj.has(category)) {
                classLogPriors.put(category, priorsObj.getDouble(category));
            }
        }
    }
    
    private void loadStopWords(Context context) throws Exception {
        stopWords = new HashSet<>();
        InputStream is = context.getAssets().open("ml_model/stopwords.json");
        String jsonStr = readInputStream(is);
        JSONArray stopWordsArray = new JSONArray(jsonStr);
        
        for (int i = 0; i < stopWordsArray.length(); i++) {
            stopWords.add(stopWordsArray.getString(i));
        }
    }
    
    private String readInputStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
    
    /**
     * Fallback model using simple keyword detection if ML model fails to load
     */
    private void initializeFallbackModel() {
        Log.w(TAG, "Using fallback keyword-based adult content detection");
        
        // Simple adult content keywords as fallback
        vocabulary = new HashSet<>(Arrays.asList(
            "porn", "sex", "xxx", "adult", "nude", "naked", "erotic",
            "explicit", "nsfw", "18+", "mature", "fetish", "webcam",
            "camgirl", "escort", "hookup", "dating", "milf", "teen",
            "amateur", "hardcore", "softcore", "bikini", "lingerie"
        ));
        
        stopWords = new HashSet<>(Arrays.asList(
            "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by"
        ));
    }
    
    /**
     * Classify a URL and determine if it's adult content
     */
    public boolean isAdultContent(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        try {
            if (isModelLoaded) {
                return classifyWithMLModel(url);
            } else {
                return classifyWithFallback(url);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error classifying URL: " + url, e);
            return classifyWithFallback(url); // Fallback on error
        }
    }
    
    /**
     * Classify using the full ML model (Naive Bayes with TF-IDF)
     */
    private boolean classifyWithMLModel(String url) {
        // Extract features using TF-IDF with 3-grams (similar to your training)
        Map<String, Double> features = extractTfidfFeatures(url);
        
        // Calculate log probability for Adult category
        double adultLogProb = classLogPriors.getOrDefault("Adult", 0.0);
        
        for (Map.Entry<String, Double> feature : features.entrySet()) {
            String term = feature.getKey();
            double tfidf = feature.getValue();
            
            if (featureWeights.containsKey(term)) {
                // Naive Bayes: log P(term|Adult) * TF-IDF
                adultLogProb += Math.log(featureWeights.get(term) + ALPHA) * tfidf;
            }
        }
        
        // Convert to probability
        double adultProb = Math.exp(adultLogProb);
        
        boolean isAdult = adultProb > ADULT_THRESHOLD;
        
        Log.d(TAG, "ML Classification - URL: " + url + 
               ", Adult Probability: " + String.format("%.4f", adultProb) + 
               ", Decision: " + (isAdult ? "ADULT" : "SAFE"));
        
        return isAdult;
    }
    
    /**
     * Extract TF-IDF features with 3-grams (replicating your training pipeline)
     */
    private Map<String, Double> extractTfidfFeatures(String url) {
        Map<String, Double> features = new HashMap<>();
        
        // Preprocess URL (lowercase, clean)
        String cleanUrl = preprocessUrl(url);
        
        // Generate 3-grams
        List<String> ngrams = generateNgrams(cleanUrl, NGRAM_MIN, NGRAM_MAX);
        
        // Calculate term frequencies
        Map<String, Integer> termFreq = new HashMap<>();
        for (String ngram : ngrams) {
            if (vocabulary.contains(ngram) && !stopWords.contains(ngram)) {
                termFreq.put(ngram, termFreq.getOrDefault(ngram, 0) + 1);
            }
        }
        
        // Calculate TF-IDF (simplified - using just TF for real-time classification)
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            int tf = entry.getValue();
            
            // Simplified TF-IDF: TF * log(vocabulary_size / (term_occurrences + 1))
            double tfidf = tf * Math.log((double) vocabulary.size() / (tf + 1.0));
            features.put(term, tfidf);
        }
        
        return features;
    }
    
    /**
     * Preprocess URL for feature extraction
     */
    private String preprocessUrl(String url) {
        // Remove protocol
        String cleaned = url.replaceAll("^https?://", "");
        
        // Remove www prefix
        cleaned = cleaned.replaceAll("^www\\.", "");
        
        // Replace special characters with spaces
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9]", " ");
        
        // Convert to lowercase
        cleaned = cleaned.toLowerCase();
        
        // Remove extra whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }
    
    /**
     * Generate character-level n-grams
     */
    private List<String> generateNgrams(String text, int minN, int maxN) {
        List<String> ngrams = new ArrayList<>();
        
        for (int n = minN; n <= maxN; n++) {
            for (int i = 0; i <= text.length() - n; i++) {
                String ngram = text.substring(i, i + n);
                ngrams.add(ngram);
            }
        }
        
        return ngrams;
    }
    
    /**
     * Fallback classification using simple keyword matching
     */
    private boolean classifyWithFallback(String url) {
        String lowerUrl = url.toLowerCase();
        
        for (String keyword : vocabulary) {
            if (lowerUrl.contains(keyword)) {
                Log.d(TAG, "Fallback Classification - URL: " + url + 
                       " contains adult keyword: " + keyword);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get classification confidence for debugging
     */
    public double getAdultContentConfidence(String url) {
        if (!isModelLoaded || url == null || url.isEmpty()) {
            return 0.0;
        }
        
        try {
            Map<String, Double> features = extractTfidfFeatures(url);
            double adultLogProb = classLogPriors.getOrDefault("Adult", 0.0);
            
            for (Map.Entry<String, Double> feature : features.entrySet()) {
                String term = feature.getKey();
                double tfidf = feature.getValue();
                
                if (featureWeights.containsKey(term)) {
                    adultLogProb += Math.log(featureWeights.get(term) + ALPHA) * tfidf;
                }
            }
            
            return Math.exp(adultLogProb);
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating confidence for URL: " + url, e);
            return 0.0;
        }
    }
    
    /**
     * Check if the ML model is loaded and ready
     */
    public boolean isModelReady() {
        return isModelLoaded;
    }
    
    /**
     * Get model statistics for debugging
     */
    public String getModelStats() {
        if (!isModelLoaded) {
            return "Model not loaded - using fallback";
        }
        
        return String.format("ML Model Stats: Vocabulary=%d, Features=%d, Classes=%d", 
                           vocabulary.size(), 
                           featureWeights.size(), 
                           classLogPriors.size());
    }
}
