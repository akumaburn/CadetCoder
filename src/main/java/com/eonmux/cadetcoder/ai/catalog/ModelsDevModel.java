package com.eonmux.cadetcoder.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single model entry from the models.dev catalog (the same model database used
 * by opencode). Unknown fields are ignored so the catalog stays forward-compatible
 * with new models.dev attributes.
 */
@JsonIgnoreProperties (ignoreUnknown = true)
public class ModelsDevModel {

    private String id;
    private String name;
    private String family;

    @JsonProperty ("release_date")
    private String releaseDate;

    @JsonProperty ("last_updated")
    private String lastUpdated;

    private String knowledge;

    private boolean attachment;
    private boolean reasoning;
    private boolean temperature;

    @JsonProperty ("tool_call")
    private boolean toolCall;

    @JsonProperty ("open_weights")
    private boolean openWeights;

    /** One of: active (implicit when absent), alpha, beta, deprecated. */
    private String status;

    private Cost       cost;
    private Limit      limit;
    private Modalities modalities;

    @JsonIgnoreProperties (ignoreUnknown = true)
    public static class Cost {
        private double input;
        private double output;

        @JsonProperty ("cache_read")
        private double cacheRead;

        @JsonProperty ("cache_write")
        private double cacheWrite;

        public double getInput()      { return input; }
        public void   setInput(double input) { this.input = input; }
        public double getOutput()     { return output; }
        public void   setOutput(double output) { this.output = output; }
        public double getCacheRead()  { return cacheRead; }
        public void   setCacheRead(double cacheRead) { this.cacheRead = cacheRead; }
        public double getCacheWrite() { return cacheWrite; }
        public void   setCacheWrite(double cacheWrite) { this.cacheWrite = cacheWrite; }
    }

    @JsonIgnoreProperties (ignoreUnknown = true)
    public static class Limit {
        private long context;
        private long input;
        private long output;

        public long getContext() { return context; }
        public void setContext(long context) { this.context = context; }
        public long getInput()   { return input; }
        public void setInput(long input) { this.input = input; }
        public long getOutput()  { return output; }
        public void setOutput(long output) { this.output = output; }
    }

    @JsonIgnoreProperties (ignoreUnknown = true)
    public static class Modalities {
        private List<String> input;
        private List<String> output;

        public List<String> getInput()  { return input; }
        public void          setInput(List<String> input) { this.input = input; }
        public List<String> getOutput() { return output; }
        public void          setOutput(List<String> output) { this.output = output; }
    }

    public String getId()              { return id; }
    public void   setId(String id)     { this.id = id; }
    public String getName()            { return name; }
    public void   setName(String name) { this.name = name; }
    public String getFamily()          { return family; }
    public void   setFamily(String family) { this.family = family; }
    public String getReleaseDate()     { return releaseDate; }
    public void   setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public String getLastUpdated()     { return lastUpdated; }
    public void   setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getKnowledge()       { return knowledge; }
    public void   setKnowledge(String knowledge) { this.knowledge = knowledge; }
    public boolean isAttachment()      { return attachment; }
    public void    setAttachment(boolean attachment) { this.attachment = attachment; }
    public boolean isReasoning()       { return reasoning; }
    public void    setReasoning(boolean reasoning) { this.reasoning = reasoning; }
    public boolean isTemperature()     { return temperature; }
    public void    setTemperature(boolean temperature) { this.temperature = temperature; }
    public boolean isToolCall()        { return toolCall; }
    public void    setToolCall(boolean toolCall) { this.toolCall = toolCall; }
    public boolean isOpenWeights()     { return openWeights; }
    public void    setOpenWeights(boolean openWeights) { this.openWeights = openWeights; }
    public String getStatus()          { return status; }
    public void   setStatus(String status) { this.status = status; }
    public Cost   getCost()            { return cost; }
    public void   setCost(Cost cost)   { this.cost = cost; }
    public Limit  getLimit()           { return limit; }
    public void   setLimit(Limit limit) { this.limit = limit; }
    public Modalities getModalities()  { return modalities; }
    public void       setModalities(Modalities modalities) { this.modalities = modalities; }
}
