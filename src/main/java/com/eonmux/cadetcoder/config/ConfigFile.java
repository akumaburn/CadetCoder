package com.eonmux.cadetcoder.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk form of {@code config.json}.
 *
 * <h2>Why a key it does not know is not a failure</h2>
 *
 * <p>Jackson refuses, by default, a file containing any property it cannot map, and a refusal here
 * costs the user everything: the caller's only answer to a file it could not read is to carry on
 * from the shipped defaults, so one unfamiliar key withdrew the model, the provider, the API keys
 * and every security setting at the same moment. The key does not have to be a mistake. A file
 * written by a newer build has keys this one has never heard of, and a setting removed between
 * builds leaves one behind, so moving between versions was enough to lose the lot.</p>
 *
 * <p>What is understood is therefore kept and the rest is reported by name. Passing over a key in
 * silence would be the other half of the same problem: a setting the user believes is in effect and
 * that nothing in the tool is reading. A file that is not JSON at all still fails -- there is
 * nothing in it to keep.</p>
 */
public final class ConfigFile {

    private ConfigFile() {
    }

    /**
     * A configuration as it was read, and the keys the build did not recognise.
     *
     * @param config       the settings that were understood, over the shipped defaults
     * @param unrecognized the dotted path of every key that was passed over, in file order
     */
    public record Loaded(Configuration config, List<String> unrecognized) {
        public Loaded {
            unrecognized = List.copyOf(unrecognized);
        }
    }

    /**
     * Reads a configuration file.
     *
     * @param path the file to read
     * @return what it says, and what in it was not understood
     * @throws IOException if the file cannot be read or is not JSON describing a configuration
     */
    public static Loaded read(Path path) throws IOException {
        List<String>  unrecognized = new ArrayList<>();
        Configuration config       = readingMapper(unrecognized)
                .readValue(path.toFile(), Configuration.class);
        return new Loaded(config, unrecognized);
    }

    /**
     * Writes a configuration file, indented so that it stays editable by hand.
     *
     * @param path   the file to write
     * @param config the settings to record
     * @throws IOException if the file cannot be written
     */
    public static void write(Path path, Configuration config) throws IOException {
        writingMapper().writeValue(path.toFile(), config);
    }

    /**
     * Writes a configuration already rendered as JSON, in the same layout as {@link #write}.
     *
     * @param path     the file to write
     * @param rendered the settings to record, as {@link #rendered} gives them
     * @throws IOException if the file cannot be written
     */
    static void write(Path path, JsonNode rendered) throws IOException {
        writingMapper().writeValue(path.toFile(), rendered);
    }

    /**
     * A configuration as the JSON the file would hold.
     *
     * @param config the settings
     * @return their rendering; a copy, so changing it changes nothing in {@code config}
     */
    static JsonNode rendered(Configuration config) {
        return writingMapper().valueToTree(config);
    }

    /**
     * A mapper that collects unknown keys into {@code unrecognized} instead of refusing the file.
     *
     * <p>A mapper of its own per read, because the handler writes into that read's list.</p>
     */
    private static ObjectMapper readingMapper(List<String> unrecognized) {
        ObjectMapper mapper = writingMapper();
        mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                                                 JsonDeserializer<?> deserializer, Object bean,
                                                 String property) throws IOException {
                unrecognized.add(where(parser, property));
                parser.skipChildren();
                return true;
            }
        });
        return mapper;
    }

    private static ObjectMapper writingMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    /**
     * Where in the file a key was found, written the way the user would name it.
     *
     * <p>{@code context.maxFiles} rather than {@code maxFiles}, because the same name can appear in
     * more than one section and "the tool ignored maxFiles" does not say which one to go and fix.
     * The path comes from the parser rather than from a table of section names, so it cannot fall
     * out of step with the sections that actually exist.</p>
     */
    private static String where(JsonParser parser, String property) {
        String pointer = parser.getParsingContext().pathAsPointer().toString();
        String dotted  = pointer.replaceFirst("^/", "").replace('/', '.');
        return dotted.isEmpty() ? property : dotted;
    }
}
