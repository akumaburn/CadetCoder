package com.eonmux.cadetcoder.ai.parsing;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Parsing strategy that handles XML-style action tags.
 * This serves as a fallback when AI uses XML format instead of JSON or ACTION blocks.
 * 
 * Expected formats:
 * <action>command_name arg1 arg2</action>
 * <action command="command_name" file_path="path">reasoning</action>
 * <action><command>read</command><file_path>src/Main.java</file_path><reasoning>Read file</reasoning></action>
 */
public class XMLActionParser implements ParsingStrategy {
    
    // Simple action tag with content
    private static final Pattern SIMPLE_ACTION_PATTERN = Pattern.compile(
        "<action>\\s*(.+?)\\s*</action>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Action tag with attributes
    private static final Pattern ATTRIBUTED_ACTION_PATTERN = Pattern.compile(
        "<action\\s+([^>]+)>\\s*(.*?)\\s*</action>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Structured XML action with nested elements
    private static final Pattern STRUCTURED_ACTION_PATTERN = Pattern.compile(
        "<action>\\s*(<.+?>.*?</.*?>)+\\s*</action>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // The <action> wrapper and its children, so the children can be extracted on their own.
    private static final Pattern ACTION_WRAPPER_PATTERN = Pattern.compile(
        "\\s*<action>\\s*(.*?)\\s*</action>\\s*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // XML element pattern for extracting nested elements
    private static final Pattern XML_ELEMENT_PATTERN = Pattern.compile(
        "<(\\w+)>\\s*(.*?)\\s*</\\1>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    
    // Attribute pattern for parsing attributes
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
        "(\\w+)\\s*=\\s*[\"']([^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE
    );
    
    @Override
    public ParsedResponse parse(String response, ParsingContext context) {
        long startTime = System.currentTimeMillis();
        
        if (response == null || response.trim().isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.EMPTY_RESPONSE, 
                                            ParsedResponse.ParsingStrategy.XML_ACTION, response)
                .addError("Empty or null response")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        List<ParsedAction> actions = new ArrayList<>();
        
        // Try structured XML first (highest confidence)
        actions.addAll(parseStructuredXML(response, context));
        
        // Then try attributed actions
        if (actions.isEmpty()) {
            actions.addAll(parseAttributedActions(response, context));
        }
        
        // Finally try simple actions
        if (actions.isEmpty()) {
            actions.addAll(parseSimpleActions(response, context));
        }
        
        if (actions.isEmpty()) {
            return new ParsedResponse.Builder(ParsedResponse.ParseResult.FORMAT_ERROR, 
                                            ParsedResponse.ParsingStrategy.XML_ACTION, response)
                .addError("No valid XML action tags found")
                .addError("Expected format: <action>command arg1 arg2</action> or <action command=\"cmd\">reasoning</action>")
                .setProcessingTime(System.currentTimeMillis() - startTime)
                .build();
        }
        
        // Calculate overall confidence
        double overallConfidence = calculateOverallConfidence(actions);
        
        ParsedResponse.Builder responseBuilder = new ParsedResponse.Builder(
            ParsedResponse.ParseResult.SUCCESS, 
            ParsedResponse.ParsingStrategy.XML_ACTION, 
            response
        );
        
        for (ParsedAction action : actions) {
            responseBuilder.addAction(action);
        }
        
        return responseBuilder
            .setConfidence(overallConfidence)
            .setProcessingTime(System.currentTimeMillis() - startTime)
            .build();
    }
    
    private List<ParsedAction> parseStructuredXML(String response, ParsingContext context) {
        List<ParsedAction> actions = new ArrayList<>();
        
        Matcher structuredMatcher = STRUCTURED_ACTION_PATTERN.matcher(response);
        while (structuredMatcher.find()) {
            // The CHILDREN of <action>, not the whole match. extractXMLElements uses a
            // backreference, so given the wrapper it matches <action>...</action> itself first and
            // resumes past it -- finding exactly one element, named "action", and never the
            // <command> that is required. The structured branch could therefore never succeed.
            String actionContent = withoutActionWrapper(structuredMatcher.group());
            ParsedAction action = parseStructuredActionContent(actionContent, context);
            if (action != null) {
                actions.add(action);
            }
        }
        
        return actions;
    }
    
    private ParsedAction parseStructuredActionContent(String actionContent, ParsingContext context) {
        Map<String, String> elements = extractXMLElements(actionContent);
        
        // Command is required
        String command = elements.get("command");
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        command = command.toLowerCase().trim();
        
        // Extract parameters from various element names
        Map<String, Object> parameters = new HashMap<>();
        String reasoning = "";
        
        for (Map.Entry<String, String> entry : elements.entrySet()) {
            String elementName = entry.getKey().toLowerCase();
            String elementValue = entry.getValue();
            
            switch (elementName) {
                case "command":
                    // Already handled
                    break;
                case "reasoning":
                case "reason":
                case "explanation":
                    reasoning = elementValue;
                    break;
                case "file_path":
                case "filepath":
                case "path":
                case "file":
                    parameters.put("file_path", elementValue);
                    break;
                case "pattern":
                case "search":
                case "query":
                    parameters.put("pattern", elementValue);
                    break;
                case "content":
                case "text":
                case "data":
                    parameters.put("content", elementValue);
                    break;
                case "limit":
                case "max":
                case "count":
                    parameters.put("limit", tryParseInteger(elementValue));
                    break;
                default:
                    // Store unknown elements as parameters
                    parameters.put(elementName, elementValue);
                    break;
            }
        }
        
        // Validate the action
        ParsedAction.ValidationResult validation = validateAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);
        
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(0.8) // High confidence for structured XML
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "xml_structured")
            .addMetadata("elements_found", elements.keySet().toString())
            .build();
    }
    
    /** Strips a surrounding {@code <action>} … {@code </action>} so its children can be matched. */
    private static String withoutActionWrapper(String actionBlock) {
        Matcher wrapper = ACTION_WRAPPER_PATTERN.matcher(actionBlock);
        return wrapper.matches() ? wrapper.group(1) : actionBlock;
    }

    private Map<String, String> extractXMLElements(String xmlContent) {
        Map<String, String> elements = new HashMap<>();
        
        Matcher elementMatcher = XML_ELEMENT_PATTERN.matcher(xmlContent);
        while (elementMatcher.find()) {
            String elementName = elementMatcher.group(1).toLowerCase();
            String elementValue = elementMatcher.group(2).trim();
            elements.put(elementName, elementValue);
        }
        
        return elements;
    }
    
    private List<ParsedAction> parseAttributedActions(String response, ParsingContext context) {
        List<ParsedAction> actions = new ArrayList<>();
        
        Matcher attributedMatcher = ATTRIBUTED_ACTION_PATTERN.matcher(response);
        while (attributedMatcher.find()) {
            String attributes = attributedMatcher.group(1);
            String content = attributedMatcher.group(2);
            
            ParsedAction action = parseAttributedAction(attributes, content, context);
            if (action != null) {
                actions.add(action);
            }
        }
        
        return actions;
    }
    
    private ParsedAction parseAttributedAction(String attributesStr, String content, ParsingContext context) {
        Map<String, String> attributes = extractAttributes(attributesStr);
        
        // Command should be in attributes
        String command = attributes.get("command");
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        command = command.toLowerCase().trim();
        
        // Build parameters from attributes
        Map<String, Object> parameters = new HashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("command")) {
                parameters.put(key, entry.getValue());
            }
        }
        
        // Content is usually reasoning
        String reasoning = content.trim();
        
        // Validate the action
        ParsedAction.ValidationResult validation = validateAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);
        
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(0.7) // Medium confidence for attributed XML
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "xml_attributed")
            .addMetadata("attributes", attributes.toString())
            .build();
    }
    
    private Map<String, String> extractAttributes(String attributesStr) {
        Map<String, String> attributes = new HashMap<>();
        
        Matcher attributeMatcher = ATTRIBUTE_PATTERN.matcher(attributesStr);
        while (attributeMatcher.find()) {
            String name = attributeMatcher.group(1).toLowerCase();
            String value = attributeMatcher.group(2);
            attributes.put(name, value);
        }
        
        return attributes;
    }
    
    private List<ParsedAction> parseSimpleActions(String response, ParsingContext context) {
        List<ParsedAction> actions = new ArrayList<>();
        
        Matcher simpleMatcher = SIMPLE_ACTION_PATTERN.matcher(response);
        while (simpleMatcher.find()) {
            String actionContent = simpleMatcher.group(1).trim();
            ParsedAction action = parseSimpleActionContent(actionContent, context);
            if (action != null) {
                actions.add(action);
            }
        }
        
        return actions;
    }
    
    private ParsedAction parseSimpleActionContent(String actionContent, ParsingContext context) {
        // Try to infer command and arguments from the content
        String[] tokens = actionContent.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        
        String command = inferCommand(actionContent, context);
        if (command == null) {
            return null;
        }
        
        Map<String, Object> parameters = inferParameters(actionContent, command, context);
        String reasoning = actionContent; // Use full content as reasoning
        
        // Validate the action
        ParsedAction.ValidationResult validation = validateAction(command, parameters, context);
        String validationMessage = getValidationMessage(validation, command);
        
        return new ParsedAction.Builder(command)
            .setParameters(parameters)
            .setReasoning(reasoning)
            .setConfidence(0.5) // Lower confidence for simple inference
            .setValidation(validation, validationMessage)
            .addMetadata("parser", "xml_simple")
            .addMetadata("original_content", actionContent)
            .build();
    }
    
    private String inferCommand(String content, ParsingContext context) {
        String lowerContent = content.toLowerCase();
        
        // Look for explicit command keywords
        String[] commandKeywords = {"read", "write", "edit", "grep", "search", "bash", "execute", "analyze", "explain", "list", "ls"};
        for (String keyword : commandKeywords) {
            if (lowerContent.contains(keyword)) {
                return keyword;
            }
        }
        
        // Infer from action verbs
        if (lowerContent.contains("show") || lowerContent.contains("display") || lowerContent.contains("view")) {
            return "read";
        }
        if (lowerContent.contains("create") || lowerContent.contains("save")) {
            return "write";
        }
        if (lowerContent.contains("modify") || lowerContent.contains("change") || lowerContent.contains("update")) {
            return "edit";
        }
        if (lowerContent.contains("find") || lowerContent.contains("look for")) {
            return "grep";
        }
        if (lowerContent.contains("run") || lowerContent.contains("command")) {
            return "bash";
        }
        
        // Use context suggestion as fallback
        return context.suggestCommand();
    }
    
    private Map<String, Object> inferParameters(String content, String command, ParsingContext context) {
        Map<String, Object> parameters = new HashMap<>();
        
        // A path named in the ACTION wins over one merely mentioned in the request. Reading only
        // the request meant "<action>read src/Main.java</action>" dropped src/Main.java and read
        // whichever file the user had happened to name earlier -- a different file, silently.
        List<String> potentialPaths = ParsingContext.extractPotentialFilePaths(content);
        if (potentialPaths.isEmpty()) {
            potentialPaths = context.extractPotentialFilePaths();
        }
        if (!potentialPaths.isEmpty()) {
            parameters.put("file_path", potentialPaths.get(0));
        }
        
        // Try to extract quoted strings as patterns or content
        Pattern quotedPattern = Pattern.compile("[\"']([^\"']+)[\"']");
        Matcher quotedMatcher = quotedPattern.matcher(content);
        List<String> quotedStrings = new ArrayList<>();
        while (quotedMatcher.find()) {
            quotedStrings.add(quotedMatcher.group(1));
        }
        
        if (!quotedStrings.isEmpty()) {
            if (command.equals("grep") || command.equals("search")) {
                parameters.put("pattern", quotedStrings.get(0));
            } else if (command.equals("write") && quotedStrings.size() > 1) {
                parameters.put("content", quotedStrings.get(1));
            }
        }
        
        return parameters;
    }
    
    private Object tryParseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }
    
    private ParsedAction.ValidationResult validateAction(String command, Map<String, Object> parameters, 
                                                       ParsingContext context) {
        // Check if command is available
        if (!context.getAvailableCommands().isEmpty() && !context.isCommandAvailable(command)) {
            return ParsedAction.ValidationResult.INVALID_COMMAND;
        }
        
        // Basic parameter validation
        if (FilePathRule.requiresPath(command) && !FilePathRule.isSatisfiedBy(parameters)) {
            return ParsedAction.ValidationResult.REQUIRES_INFERENCE;
        }
        
        return ParsedAction.ValidationResult.VALID;
    }
    
    private String getValidationMessage(ParsedAction.ValidationResult validation, String command) {
        switch (validation) {
            case INVALID_COMMAND:
                return "Command '" + command + "' is not available";
            case REQUIRES_INFERENCE:
                return "Command '" + command + "' requires parameter inference";
            default:
                return null;
        }
    }
    
    private double calculateOverallConfidence(List<ParsedAction> actions) {
        if (actions.isEmpty()) return 0.0;
        
        double total = 0.0;
        for (ParsedAction action : actions) {
            total += action.getConfidence();
        }
        return total / actions.size();
    }
    
    @Override
    public String getStrategyName() {
        return "XML Action Parser";
    }
    
    @Override
    public int getPriority() {
        return 3; // After JSON, the ACTION block and the vendors' own tool-call notations
    }

    /**
     * An {@code <action>} tag is written, not inferred, so it stays a structured parse.
     *
     * <h2>Why this is stated rather than left to the default</h2>
     *
     * <p>The default answer is drawn from the priority number, and this strategy's number moved when
     * the tool-call notations took the slot above it. Left to the default, a written {@code <action>}
     * tag would have become a fallback -- skipped entirely whenever any other structured strategy had
     * already produced actions -- because of a renumbering that says nothing about how the action was
     * arrived at.</p>
     */
    @Override
    public boolean isFallbackStrategy() {
        return false;
    }
    
    @Override
    public boolean canHandle(String response) {
        return response != null && 
               (response.contains("<action>") || response.contains("<action "));
    }
    
    @Override
    public double getExpectedConfidence() {
        return 0.6; // Medium confidence for XML inference
    }
    
    @Override
    public String getParsingErrorDetails(String response) {
        return "Failed to parse XML action format. Expected formats:\n" +
               "<action>command arg1 arg2</action>\n" +
               "<action command=\"read\" file_path=\"file.txt\">Read the file</action>\n" +
               "<action><command>read</command><file_path>file.txt</file_path><reasoning>Read file</reasoning></action>";
    }
    
    @Override
    public String[] getExampleFormats() {
        return new String[] {
            "<action>read src/Main.java</action>",
            "<action command=\"grep\" pattern=\"class.*\" path=\"src/\">Find class definitions</action>",
            "<action><command>write</command><file_path>output.txt</file_path><content>Hello World</content><reasoning>Create output file</reasoning></action>"
        };
    }
}