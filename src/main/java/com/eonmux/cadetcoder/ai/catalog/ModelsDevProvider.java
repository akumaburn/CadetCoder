package com.eonmux.cadetcoder.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A provider entry from the models.dev catalog: provider metadata plus its model map.
 * Mirrors opencode's ModelsDev.Provider shape.
 */
@JsonIgnoreProperties (ignoreUnknown = true)
public class ModelsDevProvider {

    private String id;
    private String name;
    /** API base hint from the catalog (may be null). */
    private String api;
    /** npm package opencode uses for this provider (informational). */
    private String npm;
    /** Documentation URL (informational). */
    private String doc;
    /** Environment variable names that supply credentials, e.g. ["ANTHROPIC_API_KEY"]. */
    private List<String> env;
    /** Models keyed by model id. */
    private Map<String, ModelsDevModel> models = new LinkedHashMap<>();

    public String getId()              { return id; }
    public void   setId(String id)     { this.id = id; }
    public String getName()            { return name; }
    public void   setName(String name) { this.name = name; }
    public String getApi()             { return api; }
    public void   setApi(String api)   { this.api = api; }
    public String getNpm()             { return npm; }
    public void   setNpm(String npm)   { this.npm = npm; }
    public String getDoc()             { return doc; }
    public void   setDoc(String doc)   { this.doc = doc; }
    public List<String> getEnv()       { return env; }
    public void         setEnv(List<String> env) { this.env = env; }
    public Map<String, ModelsDevModel> getModels() { return models; }
    public void setModels(Map<String, ModelsDevModel> models) {
        this.models = models != null ? models : new LinkedHashMap<>();
    }
}
